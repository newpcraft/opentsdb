// This file is part of OpenTSDB.
// Copyright (C) 2019-2020  The OpenTSDB Authors.
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

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.google.common.reflect.TypeToken;

import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.types.numeric.MutableNumericSummaryValue;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.query.QueryIterator;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.pojo.RateOptions;

/**
 * Handles rates over summary data.
 * 
 * TODO - may need some cleanup, particularly implementing proper "Drops" if all
 * summaries for a value are nan or null.
 * 
 * TODO - see if we need to reset counts that are 0 to 1.
 *
 * @since 3.0
 */
public class RateNumericSummaryIterator implements QueryIterator {
  private static final long TO_NANOS = 1000000000L;
  
  /** A sequence of data points to compute rates. */
  private TypedTimeSeriesIterator<? extends TimeSeriesDataType> source;
  
  /** Options for calculating rates. */
  private final RateConfig config;
  
  /** IDs cached to avoid lookups per value. */
  private final int count_id;
  
  /** The previous raw value to calculate the rate. */
  private MutableNumericSummaryValue prev_data;
  
  /** The rate that will be returned at the {@link #next} call. */
  private final MutableNumericSummaryValue next_rate = 
      new MutableNumericSummaryValue();
  
  /** Users see this rate after they called next. */
  private final MutableNumericSummaryValue prev_rate = 
      new MutableNumericSummaryValue();

  /** Whether or not the iterator has another real or filled value. */
  private boolean has_next;
  
  /** The data interval used when asCount is enabled. */
  private long data_interval;
  
  public RateNumericSummaryIterator(final QueryNode node, 
                                    final QueryResult result,
                                    final Map<String, TimeSeries> sources) {
    this(node, result, sources == null ? null : sources.values());
  }
  
  public RateNumericSummaryIterator(final QueryNode node, 
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
        // we shouldn't be here without a configured data interval as we don't 
        // know the underlying data reporting interval and we can't infer it. So
        // no counts for you!
        data_interval = 1;
      }
      if (data_interval <= 0) {
        data_interval = 1;
      }
    }
    count_id = result.rollupConfig().getIdForAggregator("count");
    
    final Optional<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> optional =
        sources.iterator().next().iterator(NumericSummaryType.TYPE);
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
    return NumericSummaryType.TYPE;
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
      final TimeSeriesValue<NumericSummaryType> next = 
          (TimeSeriesValue<NumericSummaryType>) source.next();
      if (next.value() == null || next.value().summariesAvailable().isEmpty()) {
        // If the upstream sent a null (ex:downsample), create a null entry here too.
        next_rate.reset(next);
        has_next = true;
        return;
      }

      if (prev_data == null || prev_data.value() == null) {
        prev_data = new MutableNumericSummaryValue(next);
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
      
      final long diff = ((next_epoch - prev_epoch) * TO_NANOS) + (next_nanos - prev_nanos);
      final double time_delta = (double) diff / (double) config.duration().toNanos();
      
      next_rate.resetTimestamp(next.timestamp());
      boolean reset = false;
      for (final int summary : next.value().summariesAvailable()) {
        reset = runRate(summary, next, time_delta);
        if (reset) {
          break;
        }
      }
      if (reset) {
        for (final int summary : next.value().summariesAvailable()) {
          next_rate.resetValue(summary, Double.NaN);
        }
      }
      prev_data.reset(next);
      has_next = true;
      break;
    }
  }
  
  boolean runRate(final int summary, 
               final TimeSeriesValue<NumericSummaryType> next, 
               final double time_delta) {
    final NumericType n = next.value().value(summary);
    final NumericType prev = prev_data.value(summary);
    
    if (config.getRateToCount()) {
      // TODO - support longs
      // TODO - make sure this is right.
      final double value = n.toDouble() * data_interval;
      next_rate.resetValue(summary, value);
      has_next = true;
      return false;
    }
    
    // delta code
    if (config.getDeltaOnly()) {
      // TODO - look at the reset values
      if ((!prev.isInteger() && Double.isNaN(prev.doubleValue()))) {
        if (config.isCounter()) {
          next_rate.resetValue(summary, Double.NaN);
          prev_data.resetValue(summary, n);
          return false;
        }
      }
      
      if (prev.isInteger() && n.isInteger()) {
        if (summary == count_id) {
          next_rate.resetValue(summary, n.longValue());
          return false;
        }
        
        long delta = n.longValue() - prev.longValue();
        if (config.isCounter() && delta < 0) {
          if (config.getDropResets()) {
            next_rate.resetValue(summary, Double.NaN);
            prev_data.resetValue(summary, n);
            return true;
          }
        }
        
        next_rate.resetValue(summary, delta);
        prev_data.resetValue(summary, n);
        has_next = true;
        return false;
      } else {
        if (summary == count_id) {
          next_rate.resetValue(summary, n.doubleValue());
          return false;
        }
        
        double delta = n.toDouble() - prev.toDouble();
        if (config.isCounter() && delta < 0) {
          if (config.getDropResets()) {
            next_rate.resetValue(summary, Double.NaN);
            prev_data.resetValue(summary, n);
            return true;
          }
        }
        
        next_rate.resetValue(summary, delta);
        prev_data.resetValue(summary, n);
        has_next = true;
        return false;
      }
    }
    
    // got a rate!
    if (prev.isInteger() && n.isInteger()) {
      if (summary == count_id) {
        next_rate.resetValue(summary, n.longValue());
        return false;
      }
      // longs
      long value_delta = n.longValue() - prev.longValue();
      if (config.isCounter() && value_delta < 0) {
        if (config.getDropResets()) {
          next_rate.resetValue(summary, Double.NaN);
          prev_data.resetValue(summary, n);
          return true;
        }
        value_delta = config.getCounterMax() - prev.longValue() +
            n.longValue();
        
        final double rate = (double) value_delta / time_delta;
        if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE && 
            rate > config.getResetValue()) {
          next_rate.resetValue(summary, 0.0D);
          return true;
        } else {
          next_rate.resetValue(summary, rate);
        }
      } else {
        final double rate = (double) value_delta / time_delta;
        if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE && 
            rate > config.getResetValue()) {
          next_rate.resetValue(summary, 0.0D);
          return true;
        } else {
          next_rate.resetValue(summary, rate);
        }
      }
      prev_data.resetValue(summary, n);
    } else {
      if (summary == count_id) {
        next_rate.resetValue(summary, n.doubleValue());
        return false;
      }
      double value_delta = n.toDouble() - prev.toDouble();
      if (config.isCounter() && value_delta < 0) {
        if (config.getDropResets()) {
          next_rate.resetValue(summary, Double.NaN);
          prev_data.resetValue(summary, n);
          return true;
        }
        
        value_delta = config.getCounterMax() - prev.toDouble() +
            n.toDouble();
        
        final double rate = value_delta / time_delta;
        if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE && 
            rate > config.getResetValue()) {
          next_rate.resetValue(summary, 0.0D);
          return true;
        } else {
          next_rate.resetValue(summary, rate);
        }
      } else {
        final double rate = value_delta / time_delta;
        if (config.getResetValue() > RateOptions.DEFAULT_RESET_VALUE && 
            rate > config.getResetValue()) {
          next_rate.resetValue(summary, 0.0D);
          return true;
        } else {
          next_rate.resetValue(summary, rate);
        }
      }
      prev_data.resetValue(summary, n);
    }
    return false;
  }

  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("rate: ")
       .append(", options=").append(config)
       .append(", prev_data=[").append(prev_data)
       .append("], next_rate=[").append(next_rate)
       .append("], prev_rate=[").append(prev_rate)
       .append("], source=[").append(source).append("]");
    return buf.toString();
  }

}
