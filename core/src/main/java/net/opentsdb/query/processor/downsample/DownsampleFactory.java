// This file is part of OpenTSDB.
// Copyright (C) 2017-2021  The OpenTSDB Authors.
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
package net.opentsdb.query.processor.downsample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;
import net.opentsdb.configuration.ConfigurationCallback;
import net.opentsdb.configuration.ConfigurationEntrySchema;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.types.numeric.NumericArrayType;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.exceptions.QueryExecutionException;
import net.opentsdb.query.QueryIteratorFactory;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryNodeFactory;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.query.interpolation.QueryInterpolatorConfig;
import net.opentsdb.query.interpolation.QueryInterpolatorFactory;
import net.opentsdb.query.plan.DefaultQueryPlanner;
import net.opentsdb.query.plan.QueryPlanner;
import net.opentsdb.query.processor.BaseQueryNodeFactory;
import net.opentsdb.query.processor.downsample.DownsampleConfig.Builder;
import net.opentsdb.utils.DateTime;
import net.opentsdb.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Simple class for generating Downsample processors.
 * 
 * @since 3.0
 */
public class DownsampleFactory extends BaseQueryNodeFactory<DownsampleConfig, Downsample> {
  private static final Logger LOG = LoggerFactory.getLogger(
      DownsampleFactory.class);
  
  public static final String TYPE = "Downsample";
  
  public static final String AUTO_KEY = "tsd.query.downsample.auto.config";
  public static final String ARRAY_PROCESS_KEY = "tsd.query.downsample.array.process";
  
  public static final TypeReference<Map<String, String>> AUTO_REF = 
      new TypeReference<Map<String, String>>() { };
  
  /** The auto downsample intervals.
   * TODO - dumbth. Better to use a tree map so we can jump to the proper entry
   * first.
   */
  private volatile List<Pair<Long, String>> intervals;
  
  private boolean processAsArrays;
  
  /**
   * Default ctor.
   */
  public DownsampleFactory() {
    super();
    registerIteratorFactory(NumericType.TYPE, new NumericIteratorFactory());
    registerIteratorFactory(NumericSummaryType.TYPE, 
        new NumericSummaryIteratorFactory());
    registerIteratorFactory(NumericArrayType.TYPE, 
        new NumericArrayIteratorFactory());
  }
  
  @Override
  public String type() {
    return TYPE;
  }
  
  @Override
  public Deferred<Object> initialize(final TSDB tsdb, final String id) {
    this.id = Strings.isNullOrEmpty(id) ? TYPE : id;
    
    // default intervals
    intervals = Lists.newArrayListWithExpectedSize(6);
    intervals.add(new Pair<Long, String>(86_400L * 365L * 1000L, "1w")); // 1y
    intervals.add(new Pair<Long, String>(86_400L * 30L * 1000L, "1d")); // 1n
    intervals.add(new Pair<Long, String>(86_400L * 7L * 1000L, "6h")); // 1w
    intervals.add(new Pair<Long, String>(86_400L * 3L * 1000L, "1h")); // 3d
    intervals.add(new Pair<Long, String>(3_600L * 12L * 1000L, "15m")); // 12h
    intervals.add(new Pair<Long, String>(3_600L * 6L * 1000L, "1m")); // 6h
    intervals.add(new Pair<Long, String>(0L, "1m")); // default
    
    registerConfigs(tsdb);
    
    return Deferred.fromResult(null);
  }

  @Override
  public Downsample newNode(final QueryPipelineContext context,
                            final DownsampleConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null.");
    }
    return new Downsample(this, context, config);
  }
  
  @Override
  public DownsampleConfig parseConfig(final ObjectMapper mapper,
                                     final TSDB tsdb,
                                     final JsonNode node) {
    Builder builder = new Builder();
    JsonNode n = node.get("interval");
    if (n != null) {
      builder.setInterval(n.asText());
    }
    
    n = node.get("minInterval");
    if (n != null && !n.isNull()) {
      builder.setMinInterval(n.asText());
    }
    
    n = node.get("reportingInterval");
    if (n != null && !n.isNull()) {
      builder.setReportingInterval(n.asText());
    }
    
    n = node.get("id");
    if (n != null) {
      builder.setId(n.asText());
    }
    
    n = node.get("timezone");
    if (n != null) {
      builder.setTimeZone(n.asText());
    }

    n = node.get("processAsArrays");
    if (n != null) {
      builder.setProcessAsArrays(n.asBoolean());
    } else {
      // If this flag (process_as_arrays) is not part of the query, then use the default value from
      // the tsd configs
      builder.setProcessAsArrays(processAsArrays);
    }
    
    n = node.get("aggregator");
    if (n != null) {
      builder.setAggregator(n.asText());
    }
    
    n = node.get("infectiousNan");
    if (n != null) {
      builder.setInfectiousNan(n.asBoolean());
    }
    
    n = node.get("runAll");
    if (n != null) {
      builder.setRunAll(n.asBoolean());
    }
    
    n = node.get("fill");
    if (n != null) {
      builder.setFill(n.asBoolean());
    }
    
    n = node.get("start");
    if (n != null) {
      builder.setStart(n.asText());
    }
    
    n = node.get("end");
    if (n != null) {
      builder.setEnd(n.asText());
    }
    
    n = node.get("interpolatorConfigs");
    for (final JsonNode config : n) {
      JsonNode type_json = config.get("type");
      final QueryInterpolatorFactory factory = tsdb.getRegistry().getPlugin(
          QueryInterpolatorFactory.class, 
          type_json == null || type_json.isNull() ? 
             null : type_json.asText());
      if (factory == null) {
        throw new IllegalArgumentException("Unable to find an "
            + "interpolator factory for: " + 
            (type_json == null || type_json.isNull() ? "Default" :
             type_json.asText()));
      }
      
      final QueryInterpolatorConfig interpolator_config = 
          factory.parseConfig(mapper, tsdb, config);
      builder.addInterpolatorConfig(interpolator_config);
    }
    
    n = node.get("sources");
    if (n != null && !n.isNull()) {
      try {
        builder.setSources(mapper.treeToValue(n, List.class));
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Failed to parse json", e);
      }
    }
    
    builder.setIntervals(intervals);
    
    return builder.build();
  }
  
  @Override
  public void setupGraph(final QueryPipelineContext context, 
                         DownsampleConfig config,
                         final QueryPlanner plan) {
    // For downsampling we need to set the config start and end times
    // to the query start and end times. The config will then align them.
    if (config.startTime() != null) {
      // we've been here
      // and we need to find our sources if we have a rollup as well as set the 
      // padding.
      final List<QueryNodeConfig> sources = Lists.newArrayList(
          plan.terminalSourceNodes(config));
      for (final QueryNodeConfig source : sources) {
        if (!(source instanceof TimeSeriesDataSourceConfig)) {
          continue;
        }
        
        if (((TimeSeriesDataSourceConfig) source).getSummaryAggregations() == null ||
            ((TimeSeriesDataSourceConfig) source).getSummaryAggregations().isEmpty()) {
          final TimeSeriesDataSourceConfig.Builder new_source = 
              (TimeSeriesDataSourceConfig.Builder) source.toBuilder();
          new_source.setSummaryInterval(config.getInterval());
          if (config.getAggregator().equalsIgnoreCase("avg")) {
            new_source.addSummaryAggregation("sum");
            new_source.addSummaryAggregation("count");
          } else {
            new_source.addSummaryAggregation(config.getAggregator());
          }
          plan.replace(source, new_source.build());
        }      
      }
      return;
    }

    final List<QueryNodeConfig> sources = Lists.newArrayList(
            plan.terminalSourceNodes(config));
    TimeStamp start = null;
    TimeStamp end = null;

    // TODO - rework this a bit. Cloning of the DownsampleConfig is hinky and
    // handling of multiple sources is wrong. Need to only post it once back to
    // the graph as well.
    for (final QueryNodeConfig source : sources) {
      if (!(source instanceof TimeSeriesDataSourceConfig)) {
        continue;
      }

      final TimeSeriesDataSourceConfig tsConfig =
              (TimeSeriesDataSourceConfig) source;
      if (start == null) {
        start = tsConfig.startTimestamp();
        end = tsConfig.endTimestamp();
      } else {
        if (start.compare(Op.GT, tsConfig.startTimestamp())) {
          start = tsConfig.startTimestamp();
        }
        if (end.compare(Op.LT, tsConfig.endTimestamp())) {
          end = tsConfig.endTimestamp();
        }
      }

      Builder builder = DownsampleConfig.newBuilder();
      DownsampleConfig.cloneBuilder(config, builder);
      DownsampleConfig newConfig = builder
              .setStart(Long.toString(start.msEpoch()))
              .setEnd(Long.toString(end.msEpoch()))
              .setId(config.getId())
              .setResultIds(((DefaultQueryPlanner) plan).compileResultIds(config))
              .build();

      if (tsConfig.getSummaryAggregations() == null ||
          tsConfig.getSummaryAggregations().isEmpty()) {
        final TimeSeriesDataSourceConfig.Builder new_source =
                (TimeSeriesDataSourceConfig.Builder) source.toBuilder();
        new_source.setSummaryInterval(newConfig.getInterval());
        if (config.getAggregator().equalsIgnoreCase("avg")) {
          new_source.addSummaryAggregation("sum");
          new_source.addSummaryAggregation("count");
        } else {
          new_source.addSummaryAggregation(newConfig.getAggregator());
        }
        plan.replace(source, new_source.build());
      }

      plan.replace(config, newConfig);
      config = newConfig;
      break;
    }
  }
  
  /**
   * Returns the intervals for this factory.
   * <b>WARNING:</b> Do NOT modify the list or entries.
   * @return The non-null intervals list.
   */
  public List<Pair<Long, String>> intervals() {
    return intervals;
  }
  
  /**
   * Returns the proper auto interval based on the query width and the interval
   * config.
   * @param delta A non-negative delta in milliseconds.
   * @param intervals The non-null reference to auto intervals.
   * @param min_interval An optional min interval.
   * @return The configured auto downsample interval.
   * @throws IllegalStateException if the downsampler is not configured properly.
   */
  public static String getAutoInterval(final long delta, 
                                       final List<Pair<Long, String>> intervals,
                                       final String min_interval) {
    for (final Pair<Long, String> interval : intervals) {
      if (delta >= interval.getKey()) {
        if (!Strings.isNullOrEmpty(min_interval)) {
          final long min = DateTime.parseDuration(min_interval);
          final long iv = DateTime.parseDuration(interval.getValue());
          if (min > iv) {
            return min_interval;
          }
        }
        return interval.getValue();
      }
    }
    throw new IllegalStateException("The host is miss configured and was "
        + "unable to find a default auto downsample interval.");
  }

  public static String getAutoInterval(final TSDB tsdb,
                                       final long delta,
                                       final String min_interval) {
    // can happen as the DS hasn't been initialized yet.
    final QueryNodeFactory factory =
            tsdb.getRegistry().getQueryNodeFactory("downsample");
    if (factory == null) {
      throw new QueryExecutionException("Downsample was set to 'auto' " +
              "but no downsample factory could be found.", 400);
    }
    final List<Pair<Long, String>> intervals =
            ((DownsampleFactory) factory).intervals();
    if (intervals == null) {
      throw new QueryExecutionException("Downsample was set to 'auto' " +
              "but no intervals were configured.", 400);
    }
    return getAutoInterval(delta, intervals, min_interval);
  }
  
  @Override
  public <T extends TimeSeriesDataType>  TypedTimeSeriesIterator newTypedIterator(
      final TypeToken<T> type,
      final Downsample node,
      final QueryResult result,
      final Collection<TimeSeries> sources) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (node == null) {
      throw new IllegalArgumentException("Node cannot be null.");
    }
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("Sources cannot be null or empty.");
    }
    
    final TimeSeries series = sources.iterator().next();
    if (series == null) {
      return null;
    }
    
    if (series.types().contains(type)) {
      return super.newTypedIterator(type, node, result, sources);
    }
    
    if (series.types().contains(NumericType.TYPE) && type == NumericArrayType.TYPE) {
      if (((DownsampleConfig) node.config()).getProcessAsArrays() && 
          ((DownsampleConfig) node.config()).getFill()) {
        return new DownsampleNumericToNumericArrayIterator(
           node, result, sources.iterator().next());
      } else {
        throw new IllegalArgumentException("Coding bug: The caller asked for " 
            + type + " but the source only has: " + series.types());
      }
    }
    
    return super.newTypedIterator(type, node, result, sources);
  }
  
  /** A callback for the auto downsample config. */
  class SettingsCallback implements ConfigurationCallback<Object> {
    @Override
    public void update(final String key, final Object value) {
      if (key.equals(AUTO_KEY)) {
        if (value == null || ((Map<String, String>) value).isEmpty()) {
          return;
        }
        
        @SuppressWarnings("unchecked")
        final Map<String, String> new_intervals = (Map<String, String>) value;
        if (new_intervals.isEmpty()) {
          LOG.error("The auto downsample config is empty. Using the defaults.");
          return;
        }
        if (new_intervals.get("0") == null) {
          LOG.error("The auto downsample config is missing the '0' config. "
              + "Using the defaults.");
          return;
        }
      
        final List<Pair<Long, String>> pairs = 
            Lists.newArrayListWithExpectedSize(new_intervals.size());
        for (final Entry<String, String> entry : new_intervals.entrySet()) {
          try {
            final long interval = entry.getKey().equals("0") ? 0 :
              DateTime.parseDuration(entry.getKey());
            DateTime.parseDuration(entry.getValue()); // validation
            pairs.add(new Pair<Long, String>(interval, entry.getValue()));
          } catch (Exception e) {
            LOG.error("Failed to parse entry: " + entry + ". Using defaults", e);
            return;
          }
        }

        Collections.sort(pairs, REVERSE_PAIR_CMP);
        intervals = pairs;
        LOG.info("Updated auto downsample intervals: " + intervals);
      } else if (key.equals(ARRAY_PROCESS_KEY)) {
        if (value != null) {
          processAsArrays = Boolean.valueOf(value.toString());
        }
      }
    }
  }
  
  void registerConfigs(final TSDB tsdb) {
    if (!tsdb.getConfig().hasProperty(AUTO_KEY)) {
      tsdb.getConfig().register(
          ConfigurationEntrySchema.newBuilder()
          .setKey(AUTO_KEY)
          .setType(AUTO_REF)
          .setDescription("A map of 1 or more pairs of auto downsample steps "
              + "where the key is a TSDB style duration and the value is "
              + "another duration to use as the downsample. The query duration "
              + "is compared against the key duration and if it is greater than "
              + "or equal to the key, then the value duration is substituted. "
              + "There must be at least one key of '0' that is treated as the "
              + "default duration.")
          .isDynamic()
          .isNullable()
          .setSource(getClass().getName())
          .build());
    }
    if (!tsdb.getConfig().hasProperty(ARRAY_PROCESS_KEY)) {
      tsdb.getConfig().register(ARRAY_PROCESS_KEY, true, true,
          "Flag to determine whether to process the data source as arrays");
    }

    tsdb.getConfig().bind(ARRAY_PROCESS_KEY, new SettingsCallback());
    this.processAsArrays = tsdb.getConfig().getBoolean(ARRAY_PROCESS_KEY);
    tsdb.getConfig().bind(AUTO_KEY, new SettingsCallback());
  }
  
  /**
   * A comparator for the pair keys in reverse numeric order.
   */
  static class ReversePairComparator implements Comparator<Pair<Long, ?>> {

    @Override
    public int compare(final Pair<Long, ?> a, final Pair<Long, ?> b) {
      return -a.getKey().compareTo(b.getKey());
    }
    
  }
  static final ReversePairComparator REVERSE_PAIR_CMP = new ReversePairComparator();
  
  /**
   * The default numeric iterator factory.
   */
  protected class NumericIteratorFactory implements QueryIteratorFactory<Downsample, NumericType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericIterator(node, result, sources.iterator().next());
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericIterator(node, result, sources.values().iterator().next());
    }

    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericType.SINGLE_LIST;
    }
        
  }

  /**
   * Handles summaries.
   */
  protected class NumericSummaryIteratorFactory implements QueryIteratorFactory<Downsample, NumericSummaryType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericSummaryIterator(node, result, sources.iterator().next());
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericSummaryIterator(node, result, sources.values().iterator().next());
    }
    
    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericSummaryType.SINGLE_LIST;
    }
  }
  
  /**
   * Handles arrays.
   */
  protected class NumericArrayIteratorFactory implements QueryIteratorFactory<Downsample, NumericArrayType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericArrayIterator(node, result, sources.iterator().next());
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final Downsample node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new DownsampleNumericArrayIterator(node, result, sources.values().iterator().next());
    }
    
    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericArrayType.SINGLE_LIST;
    }
  }
  
}
