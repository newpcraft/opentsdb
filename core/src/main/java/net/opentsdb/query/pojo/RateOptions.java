// This file is part of OpenTSDB.
// Copyright (C) 2015-2020  The OpenTSDB Authors.
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
package net.opentsdb.query.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import net.opentsdb.configuration.Configuration;
import net.opentsdb.core.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryNodeConfigOptions;
import net.opentsdb.query.QueryResultId;
import net.opentsdb.query.processor.rate.RateFactory;
import net.opentsdb.utils.DateTime;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides additional options that will be used when calculating rates. These
 * options are useful when working with metrics that are raw counter values, 
 * where a counter is defined by a value that always increases until it hits
 * a maximum value and then it "rolls over" to start back at 0.
 * <p>
 * These options will only be utilized if the query is for a rate calculation
 * and if the "counter" options is set to true.
 * @since 2.0
 */
@JsonInclude(Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = RateOptions.Builder.class)
public class RateOptions extends Validatable implements QueryNodeConfig<RateOptions.Builder, RateOptions> {
  public static final long DEFAULT_RESET_VALUE = 0;
  public static final String DEFAULT_INTERVAL = "1s";
  public static final long DEFAULT_COUNTER_MAX = Long.MAX_VALUE;
  public static final Integer PREVIOUS_INTERVALS = 1;

  /** The ID of this config. */
  private String id;
  
  /** The class of an {@link QueryNode} implementation. */
  private final String type;
  
  /** An optional list of downstream sources. */
  private final List<String> sources;
  
  /**
   * If true, then when calculating a rate of change assume that the metric
   * values are counters and thus non-zero, always increasing and wrap around at
   * some maximum. */
  private boolean counter;
  
  /** Whether or not to simply drop rolled-over or reset data points */
  private boolean drop_resets;

  /**
   * If calculating a rate of change over a metric that is a counter, then this
   * value specifies the maximum value the counter will obtain before it rolls
   * over. This value will default to Long.MAX_VALUE.
   */
  private long counter_max = DEFAULT_COUNTER_MAX;

  /**
   * Specifies the the rate change value which, if exceeded, will be considered
   * a data anomaly, such as a system reset of the counter, and the rate will be
   * returned as a zero value for a given data point.
   */
  private long reset_value;
  
  /** The rate interval in duration format. Default is 1 seconds as per TSDB 1/2 */
  private String interval = DEFAULT_INTERVAL;

  private Duration duration;
  private ChronoUnit units;
  
  protected final Map<String, String> overrides;
  
  protected List<QueryResultId> result_ids; 
  
  /** Used for Jackson non-default serdes. */
  protected RateOptions() {
    type = null;
    sources = null;
    overrides = null;
  }
  
  /**
   * Ctor
   */
  protected RateOptions(final Builder builder) {
    id = builder.id;
    type = builder.type;
    sources = builder.sources == null ? Collections.emptyList() : 
      builder.sources;
    counter = builder.counter;
    drop_resets = builder.dropResets;
    counter_max = builder.counterMax;
    reset_value = builder.resetValue;
    interval = builder.interval;
    overrides = builder.overrides;
    result_ids = builder.data_sources;
    
    final long interval_part = DateTime.getDurationInterval(interval);
    units = DateTime.unitsToChronoUnit(DateTime.getDurationUnits(interval));
    duration = Duration.of(interval_part, units);
  }
  
  /** @return Whether or not the counter flag is set */
  public boolean isCounter() {
    return counter;
  }

  /** @return The counter max value */
  public long getCounterMax() {
    return counter_max;
  }

  /** @return The optional reset value for anomaly suppression */
  public long getResetValue() {
    return reset_value;
  }

  /** @return Whether or not to drop rolled-over or reset counters */
  public boolean getDropResets() {
    return drop_resets;
  }
  
  /** @return The rate interval in duration format. Default is 1 seconds as 
   * per TSDB 1/2. */
  public String getInterval() {
    return interval;
  }

  @Override
  public String getId() {
    return id;
  }
  
  @Override
  public String getType() {
    return type;
  }

  @Override
  public List<String> getSources() {
    return sources;
  }
  
  /** @return The duration of the rate to convert to. E.g. per second or per
   * 8 seconds, etc. */
  public Duration duration() {
    return duration;
  }
  
  /** @return The parsed units of the interval. */
  public ChronoUnit units() {
    return units;
  }

  @Override
  public Builder toBuilder() {
    return new Builder()
        .setInterval(interval)
        .setType(type)
        .setInterval(interval)
        .setDropResets(drop_resets)
        .setCounter(counter)
        .setCounterMax(counter_max)
        .setResetValue(reset_value)
        .setSources(sources != null ? Lists.newArrayList(sources) : null)
        .setResultIds(result_ids != null ? Lists.newArrayList(result_ids) : null)
        .setOverrides(overrides != null ? Maps.newHashMap(overrides) : null)
        .setType(type)
        .setId(id);
  }

  @Override
  public boolean pushDown() {
    return true;
  }

  @Override
  public boolean joins() {
    return false;
  }

  @Override
  public <T> T nodeOption(QueryNodeConfigOptions option) {
    if (option == QueryNodeConfigOptions.PREVIOUS_INTERVALS) {
      return (T) PREVIOUS_INTERVALS;
    }
    return null;
  }

  @Override
  public boolean nodeFlag(QueryNodeConfigOptions option) {
    return false;
  }

  @Override
  public boolean readCacheable() {
    return true;
  }
  
  /**
   * Generates a String version of the rate option instance in a format that 
   * can be utilized in a query.
   * @return string version of the rate option instance.
   */
  public String toString() {
    StringBuilder buf = new StringBuilder();
    buf.append('{');
    buf.append(counter);
    buf.append(',').append(counter_max);
    buf.append(',').append(reset_value);
    buf.append('}');
    return buf.toString();
  }

  /** Validates the config
   * @throws IllegalArgumentException if one or more parameters were invalid
   */
  public void validate(final TSDB tsdb) {
    if (Strings.isNullOrEmpty(interval)) {
      throw new IllegalArgumentException("Interval cannot be null or empty.");
    }
    DateTime.parseDuration2(interval);
  }
  
  @Override
  public Map<String, String> getOverrides() {
    return overrides;
  }
  
  @Override
  public String getString(final Configuration config, final String key) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    String value = overrides == null ? null : overrides.get(key);
    if (Strings.isNullOrEmpty(value)) {
      if (config.hasProperty(key)) {
        return config.getString(key);
      }
    }
    return value;
  }
  
  @Override
  public int getInt(final Configuration config, final String key) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    String value = overrides == null ? null : overrides.get(key);
    if (Strings.isNullOrEmpty(value)) {
      if (config.hasProperty(key)) {
        return config.getInt(key);
      }
      throw new IllegalArgumentException("No value for key '" + key + "'");
    }
    return Integer.parseInt(value);
  }
  
  @Override
  public long getLong(final Configuration config, final String key) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    String value = overrides == null ? null : overrides.get(key);
    if (Strings.isNullOrEmpty(value)) {
      if (config.hasProperty(key)) {
        return config.getInt(key);
      }
      throw new IllegalArgumentException("No value for key '" + key + "'");
    }
    return Long.parseLong(value);
  }
  
  @Override
  public boolean getBoolean(final Configuration config, final String key) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    String value = overrides == null ? null : overrides.get(key);
    if (Strings.isNullOrEmpty(value)) {
      if (config.hasProperty(key)) {
        return config.getBoolean(key);
      }
      throw new IllegalArgumentException("No value for key '" + key + "'");
    }
    value = value.trim().toLowerCase();
    return value.equals("true") || value.equals("1") || value.equals("yes");
  }
  
  @Override
  public double getDouble(final Configuration config, final String key) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    String value = overrides == null ? null : overrides.get(key);
    if (Strings.isNullOrEmpty(value)) {
      if (config.hasProperty(key)) {
        return config.getInt(key);
      }
      throw new IllegalArgumentException("No value for key '" + key + "'");
    }
    return Double.parseDouble(value);
  }
  
  @Override
  public boolean hasKey(final String key) {
    if (Strings.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    return overrides == null ? false : overrides.containsKey(key);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
   final RateOptions options = (RateOptions) o;
   return Objects.equal(counter, options.counter)
       && Objects.equal(drop_resets, options.drop_resets)
       && Objects.equal(counter_max, options.counter_max)
       && Objects.equal(reset_value, options.reset_value)
       && Objects.equal(interval, options.interval)
       && Objects.equal(id, options.id);
  }
  
  @Override
  public int hashCode() {
    return buildHashCode().asInt();
  }
  
  /** @return A HashCode object for deterministic, non-secure hashing */
  public HashCode buildHashCode() {
    Hasher hasher = Const.HASH_FUNCTION().newHasher();
    hasher.putBoolean(counter)
    .putBoolean(drop_resets)
    .putLong(counter_max)
    .putLong(reset_value)
    .putString(interval, Const.UTF8_CHARSET);
    
    if (id !=null) {
      hasher.putString(id, Const.UTF8_CHARSET);
    }
    return hasher.hash();
  }
  
  @Override
  public int compareTo(final RateOptions other) {
    return ComparisonChain.start()
        .compareTrueFirst(counter, other.counter)
        .compareTrueFirst(drop_resets, other.drop_resets)
        .compare(counter_max, other.counter_max)
        .compare(reset_value, other.reset_value)
        .compare(interval, other.interval)
        .result();
  }
  
  @Override
  public List<QueryResultId> resultIds() {
    return result_ids == null ? Collections.emptyList() : result_ids;
  }
  
  @Override
  public boolean markedCacheable() {
    return false;
  }
  
  @Override
  public void markCacheable(final boolean cacheable) {
    // no-op
  }
  
  /** @return A new builder to construct a RateOptions from. */
  public static Builder newBuilder() {
    return new Builder();
  }
  
  /**
   * A builder for the rate options config for a query.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public static final class Builder implements QueryNodeConfig.Builder<Builder, RateOptions> {
    @JsonProperty
    private String id;
    @JsonProperty
    private String type;
    @JsonProperty
    private List<String> sources;
    @JsonProperty
    private boolean counter;
    @JsonProperty
    private boolean dropResets;
    @JsonProperty
    private long counterMax = DEFAULT_COUNTER_MAX;
    @JsonProperty
    private long resetValue = DEFAULT_RESET_VALUE;
    @JsonProperty
    private String interval = DEFAULT_INTERVAL;
    @JsonProperty
    protected Map<String, String> overrides;
    protected List<QueryResultId> data_sources;
    
    Builder() {
      setType(RateFactory.TYPE);
    }
    
    public Builder setId(final String id) {
      this.id = id;
      return this;
    }
    
    /**
     * @param type The class of the implementation.
     * @return The builder.
     */
    public Builder setType(final String type) {
      this.type = type;
      return this;
    }
    
    /**
     * @param sources An optional list of sources consisting of the IDs 
     * of a nodes in the graph.
     * @return The builder.
     */
    public Builder setSources(final List<String> sources) {
      this.sources = sources;
      return this;
    }
    
    /**
     * @param source A source to pull from for this node.
     * @return The builder.
     */
    public Builder addSource(final String source) {
      if (sources == null) {
        sources = Lists.newArrayListWithExpectedSize(1);
      }
      sources.add(source);
      return this;
    }
    
    public Builder setCounter(final boolean counter) {
      this.counter = counter;
      return this;
    }
    
    public Builder setDropResets(final boolean drop_resets) {
      this.dropResets = drop_resets;
      return this;
    }
    
    public Builder setCounterMax(final long counter_max) {
      this.counterMax = counter_max;
      return this;
    }
    
    public Builder setResetValue(final long counter_reset) {
      this.resetValue = counter_reset;
      return this;
    }
    
    public Builder setInterval(final String interval) {
      this.interval = interval;
      return this;
    }

    public Builder setOverrides(final Map<String, String> overrides) {
      this.overrides = overrides;
      return this;
    }
    
    public Builder addOverride(final String key, final String value) {
      if (overrides == null) {
        overrides = Maps.newHashMap();
      }
      overrides.put(key, value);
      return this;
    }

    public Builder setResultIds(final List<QueryResultId> data_sources) {
      this.data_sources = data_sources;
      return self();
    }
    
    public Builder addResultId(final QueryResultId source) {
      if (data_sources == null) {
        data_sources = Lists.newArrayList(source);
      } else {
        data_sources.add(source);
      }
      return self();
    }
    
    public RateOptions build() {
      return new RateOptions(this);
    }

    @Override
    public Builder self() {
      return this;
    }
  }
  
}
