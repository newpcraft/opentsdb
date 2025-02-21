// This file is part of OpenTSDB.
// Copyright (C) 2014-2020 The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.query.processor.rate;

import com.google.common.reflect.TypeToken;

import gnu.trove.iterator.TLongIntIterator;
import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TLongIntHashMap;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.query.QueryIterator;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.pojo.RateOptions;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Iterator that generates rates from a sequence of adjacent data points.
 * 
 * TODO - proper interval conversion. May not work for > 1hr
 * 
 * @since 3.0
 */
public class RateNumericIterator implements QueryIterator {
  private static final long TO_NANOS = 1000000000L;
  
  /** A sequence of data points to compute rates. */
  private TypedTimeSeriesIterator<? extends TimeSeriesDataType> source;
  
  /** Options for calculating rates. */
  private final RateConfig config;
  
  /** The previous raw value to calculate the rate. */
  private MutableNumericValue prev_data;

  /** The rate that will be returned at the {@link #next} call. */
  private final MutableNumericValue next_rate = new MutableNumericValue();
  
  /** Users see this rate after they called next. */
  private final MutableNumericValue prev_rate = new MutableNumericValue();

  /** Whether or not the iterator has another real or filled value. */
  private boolean has_next;
  
  /** The data interval used when asCount is enabled. */
  private long data_interval;
  
  /**
   * Constructs a {@link RateNumericIterator} instance.
   * @param node The non-null query node.
   * @param result The non-null result.
   * @param sources The non-null map of sources.
   */
  public RateNumericIterator(final QueryNode node, 
                             final QueryResult result,
                             final Map<String, TimeSeries> sources) {
    this(node, result, sources == null ? null : sources.values());
  }
  
  /**
   * Constructs a {@link RateNumericIterator} instance.
   * @param node The non-null query node.
   * @param result The non-null result.
   * @param sources The non-null collection of sources.
   */
  public RateNumericIterator(final QueryNode node, 
                             final QueryResult result,
                             final Collection<TimeSeries> sources) {
    if (node == null) {
      throw new IllegalArgumentException("Query node cannot be null.");
    }
    if (sources == null) {
      throw new IllegalArgumentException("Sources cannot be null.");
    }
    if (node.config() == null) {
      throw new IllegalArgumentException("Node config cannot be null.");
    }
    config = (RateConfig) node.config();
    if (config.getRateToCount()) {
      if (config.dataIntervalMs() > 0) {
        data_interval = (config.dataIntervalMs() / 1000) / 
            config.duration().get(ChronoUnit.SECONDS);
      } else {
        // find it!
        computeDataInterval(sources.iterator().next());
      }
      if (data_interval <= 0) {
        data_interval = 1;
      }
    }
    
    final Optional<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> optional =
        sources.iterator().next().iterator(NumericType.TYPE);
    if (optional.isPresent()) {
      this.source = optional.get();
      populateNextRate();
    } else {
      this.source = null;
    }
  }

  /** @return True if there is a valid next value. */
  @Override
  public boolean hasNext() {
    return has_next;
  }
  
  @Override
  public TimeSeriesValue<?> next() {
    prev_rate.reset(next_rate);
    populateNextRate();
    return prev_rate;
  }
  
  @Override
  public TypeToken<? extends TimeSeriesDataType> getType() {
    return NumericType.TYPE;
  }
  
  @Override
  public void close() {
    try {
      source.close();
    } catch (IOException e) {
      // Don't bother logging.
      e.printStackTrace();
    }
    source = null;
  }
  
  /**
   * Populate the next rate.
   */
  private void populateNextRate() {
    has_next = false;
    
    while (source.hasNext()) {
      final TimeSeriesValue<NumericType> next = 
          (TimeSeriesValue<NumericType>) source.next();
      if (next.value() == null || (!next.value().isInteger() && 
          (Double.isNaN(next.value().doubleValue())))) {
        // If the upstream sent a null (ex:downsample), create a null entry here too..
        next_rate.reset(next);
        has_next = true;
        return;
      }

      if (prev_data == null || prev_data.value() == null) {
        prev_data = new MutableNumericValue(next);
        continue;
      }
      
      // validation similar to TSDB 2.x
      if (next.timestamp().compare(Op.LTE, prev_data.timestamp())) {
        throw new IllegalStateException("Next timestamp [" + next.timestamp() 
          + " ] cannot be less than or equal to the previous [" + 
            prev_data.timestamp() + "] timestamp.");
      }
      
      long prev_epoch = prev_data.timestamp().epoch();
      long prev_nanos = prev_data.timestamp().nanos();
      
      long next_epoch = next.timestamp().epoch();
      long next_nanos = next.timestamp().nanos();
      
      if (next_nanos < prev_nanos) {
        next_nanos *= TO_NANOS;
        next_epoch--;
      }
      
      long diff = ((next_epoch - prev_epoch) * TO_NANOS) + (next_nanos - prev_nanos);
      double time_delta = (double) diff / (double) config.duration().toNanos();
      if (config.getRateToCount()) {
        if (time_delta < data_interval) {
          next_rate.reset(next.timestamp(), (next.value().toDouble() * time_delta));
        } else {
          next_rate.reset(next.timestamp(), (next.value().toDouble() * data_interval));
        }
        prev_data.reset(next);
        has_next = true;
        return;
      }
      
      // delta code
      if (config.getDeltaOnly()) {
        // TODO - look at the rest values
        if (prev_data.isInteger() && next.value().isInteger()) {
          long delta = next.value().longValue() - prev_data.longValue();
          if (config.isCounter() && delta < 0) {
            if (config.getDropResets()) {
              prev_data.reset(next);
              continue;
            }
          }
          
          next_rate.reset(next.timestamp(), delta);
          prev_data.reset(next);
          has_next = true;
          break;
        } else {
          double delta = next.value().toDouble() - prev_data.toDouble();
          if (config.isCounter() && delta < 0) {
            if (config.getDropResets()) {
              prev_data.reset(next);
              continue;
            }
          }
          
          next_rate.reset(next.timestamp(), delta);
          prev_data.reset(next);
          has_next = true;
          break;
        }
      }
      
      // got a rate!
      if (prev_data.value().isInteger() && next.value().isInteger()) {
        // longs
        long value_delta = next.value().longValue() - prev_data.longValue();
        if (config.isCounter() && value_delta < 0) {
          if (config.getDropResets()) {
            prev_data.reset(next);
            continue;
          }
          
          value_delta = config.getCounterMax() - prev_data.longValue() +
              next.value().longValue();
          
          final double rate = (double) value_delta / time_delta;
          if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE
            && rate > config.getResetValue()) {
            next_rate.reset(next.timestamp(), 0.0D);
          } else {
            next_rate.reset(next.timestamp(), rate);
          }
        } else {
          final double rate = (double) value_delta / time_delta;
          if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE
            && rate > config.getResetValue()) {
            next_rate.reset(next.timestamp(), 0.0D);
          } else {
            next_rate.reset(next.timestamp(), rate);
          }
        }
      } else {
        double value_delta = next.value().toDouble() - prev_data.toDouble();
        if (config.isCounter() && value_delta < 0) {
          if (config.getDropResets()) {
            prev_data.reset(next);
            continue;
          }
          
          value_delta = config.getCounterMax() - prev_data.toDouble() +
              next.value().toDouble();
          
          final double rate = value_delta / time_delta;
          if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE
            && rate > config.getResetValue()) {
            next_rate.reset(next.timestamp(), 0.0D);
          } else {
            next_rate.reset(next.timestamp(), rate);
          }
        } else {
          final double rate = value_delta / time_delta;
          if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE
            && rate > config.getResetValue()) {
            next_rate.reset(next.timestamp(), 0.0D);
          } else {
            next_rate.reset(next.timestamp(), rate);
          }
          next_rate.reset(next.timestamp(), rate);
        }
      }
      
      prev_data.reset(next);
      has_next = true;
      break;
    }
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("RateSpan: ")
       .append(", options=").append(config)
       .append(", prev_data=[").append(prev_data)
       .append("], next_rate=[").append(next_rate)
       .append("], prev_rate=[").append(prev_rate)
       .append("], source=[").append(source).append("]");
    return buf.toString();
  }

  /**
   * Iterates over the series to find the most common interval and uses that 
   * as the assumed data reporting interval.
   * @param series The non-null series to pull an iterator from.
   */
  private void computeDataInterval(final TimeSeries series) {
    final Optional<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> optional =
        series.iterator(NumericType.TYPE);
    if (!optional.isPresent()) {
      return;
    }
    
    final TypedTimeSeriesIterator<? extends TimeSeriesDataType> iterator = 
        optional.get();
    TimeStamp last = null;
    final TLongIntMap distribution = new TLongIntHashMap();
    while (iterator.hasNext()) {
      final TimeSeriesValue<? extends TimeSeriesDataType> value = iterator.next();
      if (last == null) {
        last = value.timestamp().getCopy();
        continue;
      }
      
      long prev_epoch = last.epoch();
      long prev_nanos = last.nanos();
      
      long next_epoch = value.timestamp().epoch();
      long next_nanos = value.timestamp().nanos();
      
      if (next_nanos < prev_nanos) {
        next_nanos *= TO_NANOS;
        next_epoch--;
      }
      
      final long diff = ((next_epoch - prev_epoch) * TO_NANOS) + 
          (next_nanos - prev_nanos);
      if (distribution.containsKey(diff)) {
        distribution.increment(diff);
      } else {
        distribution.put(diff, 1);
      }
      last.update(value.timestamp());
    }
    
    long diff = 0;
    int count = 0;
    final TLongIntIterator it = distribution.iterator();
    // TODO - if there is exactly 1 distribution per interval (shouldn't happen)
    // then we should find the min.
    while (it.hasNext()) {
      it.advance();
      if (it.value() > count) {
        count = it.value();
        diff = it.key();
      }
    }
    data_interval = diff / 1000000000 / config.duration().get(ChronoUnit.SECONDS);
  }
}
