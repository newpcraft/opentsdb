// This file is part of OpenTSDB.
// Copyright (C) 2018-2021  The OpenTSDB Authors.
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
package net.opentsdb.storage.schemas.tsdb1x;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.DeferredGroupException;

import net.opentsdb.auth.AuthState;
import net.opentsdb.common.Const;
import net.opentsdb.configuration.ConfigurationException;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.BaseTimeSeriesStringId;
import net.opentsdb.data.LowLevelTimeSeriesData;
import net.opentsdb.data.PartialTimeSeriesSet;
import net.opentsdb.data.TimeSeriesByteId;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesDatum;
import net.opentsdb.data.TimeSeriesDatumId;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.data.TimeSeriesSharedTagsAndTimeData;
import net.opentsdb.data.TimeSeriesDatumStringId;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.types.numeric.NumericByteArraySummaryType;
import net.opentsdb.data.types.numeric.NumericLongArrayType;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.meta.MetaDataStorageSchema;
import net.opentsdb.pools.ByteArrayPool;
import net.opentsdb.pools.LongArrayPool;
import net.opentsdb.pools.ObjectPool;
import net.opentsdb.query.filter.QueryFilter;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.rollup.RollupConfig;
import net.opentsdb.rollup.RollupInterval;
import net.opentsdb.stats.Span;
import net.opentsdb.storage.TimeSeriesDataConsumer;
import net.opentsdb.storage.WriteStatus;
import net.opentsdb.storage.WriteStatus.WriteState;
import net.opentsdb.storage.DatumIdValidator;
import net.opentsdb.uid.IdOrError;
import net.opentsdb.uid.NoSuchUniqueId;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueIdFactory;
import net.opentsdb.uid.UniqueIdStore;
import net.opentsdb.uid.UniqueIdType;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.Bytes.ByteMap;
import net.opentsdb.utils.Exceptions;
import net.opentsdb.utils.XXHash;

/**
 * The interface for an OpenTSDB version 1 and version 2 schema where 
 * we supported HBase/Bigtable style data stores with row keys 
 * consisting of optional salt bucket, metric UID, normalized timestamp
 * and tag key + tag value UIDs. Columns encode various data types with
 * qualifiers and value byte arrays.
 * 
 * @since 3.0
 */
public class Schema implements TimeSeriesDataConsumer {

  public static final byte APPENDS_PREFIX = 5;
  
  public static final String QUERY_BYTE_LIMIT_KEY = "tsd.query.limits.bytes";
  public static final long QUERY_BYTE_LIMIT_DEFAULT = 0;
  public static final String QUERY_DP_LIMIT_KEY = "tsd.query.limits.data_points";
  public static final long QUERY_DP_LIMIT_DEFAULT = 0;
  public static final String QUERY_REVERSE_KEY = "tsd.query.time.descending";
  public static final String QUERY_KEEP_FIRST_KEY = "tsd.query.duplicates.keep_earliest";
  public static final String TIMELESS_SALTING_KEY = "tsd.storage.salt.timeless";
  public static final String OLD_SALTING_KEY = "tsd.storage.salt.old";
  
  /** Max time delta (in seconds) we can store in a column qualifier.  */
  public static final short MAX_RAW_TIMESPAN = 3600;
  
  /** Number of bytes on which a timestamp is encoded.  */
  public static final short TIMESTAMP_BYTES = 4;
  public static final String METRIC_TYPE = "metric";
  public static final String TAGK_TYPE = "tagk";
  public static final String TAGV_TYPE = "tagv";
  
  private final TSDB tsdb;
  private final String id;
  private final Tsdb1xDataStore data_store;
  private final UniqueIdStore uid_store;
  
  private final UniqueId metrics;
  private final UniqueId tag_names;
  private final UniqueId tag_values;
  
  protected int metric_width;
  protected int tagk_width;
  protected int tagv_width;
  protected int salt_buckets;
  protected int salt_width;
  
  protected final boolean timeless_salting;
  protected final boolean old_salting;

  protected final Map<TypeToken<?>, ObjectPool> pool_cache;
  
  protected Map<TypeToken<?>, Codec> codecs;
  protected Map<TypeToken<?>, ThreadLocal<Codec>> encoders;
  
  protected MetaDataStorageSchema meta_schema;
  
  protected DatumIdValidator id_validator;
  
  protected SchemaFactory factory;

  public Schema(final SchemaFactory factory, final TSDB tsdb, final String id) {
    this.factory = factory;
    this.tsdb = tsdb;
    this.id = id;
    setConfig();
    
    String key = configKey("data.store");
    final String store_name = tsdb.getConfig().getString(key);
    
    final Tsdb1xDataStoreFactory store_factory = tsdb.getRegistry()
          .getPlugin(Tsdb1xDataStoreFactory.class, store_name);
    if (store_factory == null) {
      throw new ConfigurationException("No factory found for: " + store_name);
    }
    data_store = store_factory.newInstance(tsdb, id, this);
    if (data_store == null) {
      throw new IllegalStateException("Store factory " + store_factory 
          + " returned a null data store instance.");
    }
    
    // TODO there's a better way. For now the data store will dump it
    // in the shared objects map.
    key = Strings.isNullOrEmpty(id) ? "default_uidstore" : id + "_uidstore";
    uid_store = (UniqueIdStore) tsdb.getRegistry().getSharedObject(key);
    if (uid_store == null) {
      throw new IllegalStateException("Unable to locate a UID store in "
          + "the shared object repo with the ID: " + key);
    }
    
    key = configKey("uid.cache.type.metric");
    String value = tsdb.getConfig().getString(key);
    if (Strings.isNullOrEmpty(value)) { 
      throw new ConfigurationException("Null value for config key: " + key);
    }
    UniqueIdFactory uid_factory = (UniqueIdFactory) tsdb.getRegistry()
        .getPlugin(UniqueIdFactory.class, value);
    if (uid_factory == null) {
      throw new IllegalStateException("Unable to locate a "
          + "registered UniqueIdFactory with the ID: " + value);
    }
    metrics = uid_factory.newInstance(tsdb, id, UniqueIdType.METRIC, uid_store);
    if (metrics == null) {
      throw new IllegalStateException("Factory " + uid_factory 
          + " returned a null UniqueId instance.");
    }
    
    key = configKey("uid.cache.type.tagk");
    value = tsdb.getConfig().getString(key);
    if (Strings.isNullOrEmpty(value)) { 
      throw new ConfigurationException("Null value for config key: " + key);
    }
    uid_factory = (UniqueIdFactory) tsdb.getRegistry()
        .getPlugin(UniqueIdFactory.class, value);
    if (uid_factory == null) {
      throw new IllegalStateException("Unable to locate a "
          + "registered UniqueIdFactory with the ID: " + value);
    }
    tag_names = uid_factory.newInstance(tsdb, id, UniqueIdType.TAGK, uid_store);
    if (tag_names == null) {
      throw new IllegalStateException("Factory " + uid_factory 
          + " returned a null UniqueId instance.");
    }
    
    key = configKey("uid.cache.type.tagv");
    value = tsdb.getConfig().getString(key);
    if (Strings.isNullOrEmpty(value)) { 
      throw new ConfigurationException("Null value for config key: " + key);
    }
    uid_factory = (UniqueIdFactory) tsdb.getRegistry()
        .getPlugin(UniqueIdFactory.class, value);
    if (uid_factory == null) {
      throw new IllegalStateException("Unable to locate a "
          + "registered UniqueIdFactory with the ID: " + value);
    }
    tag_values = uid_factory.newInstance(tsdb, id, UniqueIdType.TAGV, uid_store);
    if (tag_values == null) {
      throw new IllegalStateException("Factory " + uid_factory 
          + " returned a null UniqueId instance.");
    }
        
    if (!tsdb.getConfig().hasProperty(QUERY_BYTE_LIMIT_KEY)) {
      tsdb.getConfig().register(QUERY_BYTE_LIMIT_KEY, 
          QUERY_BYTE_LIMIT_DEFAULT, true, 
          "The number of bytes allowed in a single query result or segment");
    }
    if (!tsdb.getConfig().hasProperty(QUERY_DP_LIMIT_KEY)) {
      tsdb.getConfig().register(QUERY_DP_LIMIT_KEY, 
          QUERY_DP_LIMIT_DEFAULT, true, 
          "The number of data points or values allowed in a single "
          + "query result or segment");
    }
    if (!tsdb.getConfig().hasProperty(QUERY_REVERSE_KEY)) {
      tsdb.getConfig().register(QUERY_REVERSE_KEY, false, true,
          "Results are iterated and returned in descending time order "
          + "instead of ascending time order.");
    }
    if (!tsdb.getConfig().hasProperty(QUERY_KEEP_FIRST_KEY)) {
      tsdb.getConfig().register(QUERY_KEEP_FIRST_KEY, false, true,
          "Whether or not to keep the earliest value (true) when "
          + "de-duplicating or to keep the latest version (false).");
    }
    if (!tsdb.getConfig().hasProperty(TIMELESS_SALTING_KEY)) {
      tsdb.getConfig().register(TIMELESS_SALTING_KEY, true, false,
          "Whether or not timestamps are incorporated into the salting "
          + "calculations. When true, time is not incorporated, when false "
          + "it is included. NOTE: For almost all uses, leave this as true.");
    }
    if (!tsdb.getConfig().hasProperty(OLD_SALTING_KEY)) {
      tsdb.getConfig().register(OLD_SALTING_KEY, false, false,
          "Whether or not to enable the old, stringified salting "
          + "calculation. DO NOT SET THIS TO TRUE!");
    }
    
    timeless_salting = tsdb.getConfig().getBoolean(TIMELESS_SALTING_KEY);
    old_salting = tsdb.getConfig().getBoolean(OLD_SALTING_KEY);
    codecs = Maps.newHashMapWithExpectedSize(2);
    codecs.put(NumericType.TYPE, new NumericCodec());
    codecs.put(NumericSummaryType.TYPE, new NumericSummaryCodec());

    encoders = Maps.newHashMapWithExpectedSize(2);
    encoders.put(NumericType.TYPE, ThreadLocal.withInitial(() -> new NumericCodec()));
    encoders.put(NumericSummaryType.TYPE,
            ThreadLocal.withInitial(() -> new NumericSummaryCodec()));

    meta_schema = tsdb.getRegistry()
        .getDefaultPlugin(MetaDataStorageSchema.class);
    
    id_validator = tsdb.getRegistry().getDefaultPlugin(
        DatumIdValidator.class);
    pool_cache = Maps.newConcurrentMap();
  }

  @Override
  public void write(AuthState state, TimeSeriesDatum datum, WriteCallback callback) {
    // TODO - redoo since we can chain now
    final String error = id_validator.validate(datum.id());
    if (error != null) {
      if (callback != null) {
        callback.failAll(WriteStatus.REJECTED);
      }
      return;
    }
    data_store.write(state, datum, callback);
  }

  @Override
  public void write(AuthState state, TimeSeriesSharedTagsAndTimeData data, WriteCallback callback) {
    // TODO - redo!
    final List<WriteStatus> status = Lists.newArrayList();
    final List<TimeSeriesDatum> forwards = Lists.newArrayList();
    String error = null;
    int errors = 0;
    for (final TimeSeriesDatum datum : data) {
      error = id_validator.validate(datum.id());
      if (error != null) {
        status.add(WriteStatus.rejected(error));
        errors++;
      } else {
        status.add(null);
        forwards.add(datum);
      }
    }

    if (errors < 1) {
      data_store.write(state, data, callback);
      return;
    } else if (forwards.isEmpty()) {
      // don't even bother calling downstream.
      if (callback != null) {
        callback.failAll(WriteStatus.REJECTED);
      }
      return;
    }

    // aww, have to follow the complex path
    data_store.write(state,
            TimeSeriesSharedTagsAndTimeData.fromCollection(forwards),
            callback);
  }

  @Override
  public void write(AuthState state, LowLevelTimeSeriesData data, WriteCallback callback) {
    // TODO - pass this info through the validator.
    data_store.write(state, data, callback);
  }

  /**
   * Strips the salt and timestamp out of a key to get the TSUID of the
   * series.
   * @param key A non-null and non-empty byte array.
   * @return A non-null byte array without the salt and timestamp.
   * @throws IllegalArgumentException if the key was null or encoded 
   * improperly.
   */
  public byte[] getTSUID(final byte[] key) {
    if (Bytes.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    int offset = salt_width + metric_width + TIMESTAMP_BYTES;
    if (key.length <= offset) {
      throw new IllegalArgumentException("Key was too short.");
    }
    final byte[] timeless = new byte[key.length - salt_width - TIMESTAMP_BYTES];
    System.arraycopy(key, salt_width, timeless, 0, metric_width);
    System.arraycopy(key, offset, timeless, metric_width, key.length - offset);
    return timeless;
  }
  
  /**
   * For the case where we don't need the TSUID, just the hash of it, we can
   * call this to get a determinstic value.
   * @param key The full row key.
   * @return Just the hash.
   */
  public long getTSUIDHash(final byte[] key) {
    if (Bytes.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    int offset = salt_width + metric_width + TIMESTAMP_BYTES;
    if (key.length <= offset) {
      throw new IllegalArgumentException("Key was too short.");
    }
    long hash = XXHash.hash(key, salt_width, metric_width);
    return XXHash.updateHash(hash, key, offset, key.length - offset);
  }
  
  /**
   * Retrieve the row timestamp from the row key.
   * @param key A non-null and non-empty byte array.
   * @param timestamp The non-null timestamp to update.
   * @return A non-null timestamp object.
   * @throws IllegalArgumentException if the key was null or encoded 
   * improperly.
   */
  public void baseTimestamp(final byte[] key, final TimeStamp timestamp) {
    if (Bytes.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("Key cannot be null or empty.");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("Timestamp cannot be null");
    }
    if (key.length < salt_width + metric_width + TIMESTAMP_BYTES) {
      throw new IllegalArgumentException("Key was too short.");
    }
    timestamp.update(Bytes.getUnsignedInt(key, salt_width + metric_width), 0);
  }
  
  public long baseTimestamp(final byte[] key) {
    return Bytes.getUnsignedInt(key, salt_width + metric_width);
  }
  
  /**
   * The width of the requested UID type in bytes.
   * @param type A non-null type.
   * @return The width.
   * @throws IllegalArgumentException if the type was null or of a type
   * not supported by this schema.
   */
  public int uidWidth(final UniqueIdType type) {
    if (type == null) {
      throw new IllegalArgumentException("The type cannot be null.");
    }
    switch(type) {
    case METRIC:
      return metric_width;
    case TAGK:
      return tagk_width;
    case TAGV:
      return tagv_width;
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }
  
  /**
   * Resolves metric, tag keys and tag values in the filter tree wherever
   * they're found.
   * @param filter A non-null filter to resolve.
   * @param span An optional tracing span.
   * @return A deferred resolving to the resolved filterr if successful 
   * or an exception if something went pear shaped.
   */
  public Deferred<ResolvedQueryFilter> resolveUids(final QueryFilter filter, 
                                                   final Span span) {
    if (filter == null) {
      throw new IllegalArgumentException("Filter cannot be null.");
    }
    return new FilterUidResolver(this, filter).resolve(span);
  }
 
  /**
   * Converts the given string to it's UID value based on the type.
   * @param type A non-null UID type.
   * @param name A non-null and non-empty string.
   * @param span An optional tracing span.
   * @return A deferred resolving to the UID if successful or an exception.
   * @throws IllegalArgumentException if the type was null or the string
   * was null or empty.
   */
  public Deferred<byte[]> getId(final UniqueIdType type, 
                                final String name,
                                final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null");
    }
    if (Strings.isNullOrEmpty(name)) {
      throw new IllegalArgumentException("Name cannot be null or empty.");
    }
    
    // not tracing here since we're just a router
    switch(type) {
    case METRIC:
      return metrics.getId(name, span);
    case TAGK:
      return tag_names.getId(name, span);
    case TAGV:
      return tag_values.getId(name, span);
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }
  
  /**
   * Converts the list of strings to their IDs, maintaining order.
   * @param type A non-null UID type.
   * @param names A non-null and non-empty list of strings.
   * @param span An optional tracing span.
   * @return A deferred resolving to the list of UIDs in order if 
   * successful or an exception.
   * @throws IllegalArgumentException if the type was null or the
   * IDs was null or an ID in the list was null or empty.
   */
  public Deferred<List<byte[]>> getIds(final UniqueIdType type, 
                                       final List<String> names,
                                       final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null");
    }
    if (names == null || names.isEmpty()) {
      throw new IllegalArgumentException("Names cannot be null or empty.");
    }
    
    // not tracing here since we're just a router
    switch(type) {
    case METRIC:
      return metrics.getIds(names, span);
    case TAGK:
      return tag_names.getIds(names, span);
    case TAGV:
      return tag_values.getIds(names, span);
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }
  
  /**
   * Converts the UID to the equivalent string name.
   * @param type A non-null UID type.
   * @param id A non-null and non-empty byte array UID.
   * @param span An optional tracing span.
   * @return A deferred resolving to the string if successful or an
   * exception.
   * @throws IllegalArgumentException if the type was null or the ID 
   * null or empty.
   */
  public Deferred<String> getName(final UniqueIdType type, 
                                  final byte[] id,
                                  final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null");
    }
    if (Bytes.isNullOrEmpty(id)) {
      throw new IllegalArgumentException("ID cannot be null or empty.");
    }
 
    // not tracing here since we're just a router
    switch(type) {
    case METRIC:
      return metrics.getName(id, span);
    case TAGK:
      return tag_names.getName(id, span);
    case TAGV:
      return tag_values.getName(id, span);
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }
  
  /**
   * Converts the list of UIDs to the equivalent string name maintaining
   * order.
   * @param type A non-null UID type.
   * @param ids A deferred resolving to a list of the strings in order
   * if successful or an exception.
   * @param span An optional tracing span.
   * @throws IllegalArgumentException if the type was null or the strings
   * list was null or any string in the list was null or empty.
   * @return
   */
  public Deferred<List<String>> getNames(final UniqueIdType type, 
                                         final List<byte[]> ids,
                                         final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null");
    }
    if (ids == null || ids.isEmpty()) {
      throw new IllegalArgumentException("IDs cannot be null or empty.");
    }
 
    // not tracing here since we're just a router
    switch(type) {
    case METRIC:
      return metrics.getNames(ids, span);
    case TAGK:
      return tag_names.getNames(ids, span);
    case TAGV:
      return tag_values.getNames(ids, span);
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }

  /**
   * Checks and adds the value to the cache if it does not exist. The check
   * involved is that if the value exists with a DIFFERENT mapping then we'll
   * throw an exception.
   * @param type The non-null type.
   * @param name The non-null name for the UID.
   * @param id The non-null UID to map to.
   * @throws IllegalStateException if the mapping is different.
   */
  public void addToCache(final UniqueIdType type,
                         final String name,
                         final byte[] id) {
    switch(type) {
      case METRIC:
        metrics.addToCache(name, id);
        break;
      case TAGK:
        tag_names.addToCache(name, id);
        break;
      case TAGV:
        tag_values.addToCache(name, id);
        break;
      default:
        throw new IllegalArgumentException("This data store does not "
                + "handle Unique IDs of type: " + type);
    }
  }

  /** @return The number of buckets to spread data into. */
  public int saltBuckets() {
    return salt_buckets;
  }
  
  /** @return The width of the salt prefix in row keys. */
  public int saltWidth() {
    return salt_width;
  }
  
  /** @return The width of metric UIDs. */
  public int metricWidth() {
    return metric_width;
  }
  
  /** @return The width of tag key UIDs. */
  public int tagkWidth() {
    return tagk_width;
  }
  
  /** @return The width of tag value UIDs. */
  public int tagvWidth() {
    return tagv_width;
  }
  
  /**
   * Sets the proper salt bucket in the row key byte array. The array
   * must be pre-configured with the proper number of bytes for the salt.
   * @param row_key A non-null and non-empty row key.
   */
  public void prefixKeyWithSalt(final byte[] row_key) {
    if (salt_width < 1) {
      return;
    }
    if ((row_key.length < salt_width + metric_width) ||    
        (Bytes.memcmp(row_key, new byte[salt_width + metric_width], 
            salt_width, metric_width) == 0)) {    
        //Metric id is 0, which means it is a global row. Don't prefix it with salt   
        return;
      }
    
    if (timeless_salting) {
      // we want the metric and tags, not the timestamp
      int hash = 1;
      for (int i = salt_width; i < row_key.length; i++) {
        hash = 31 * hash + row_key[i];
        if (i + 1 == salt_width + metric_width) {
          i = salt_width + metric_width + Const.TIMESTAMP_BYTES - 1;
        }
      }
      int modulo = hash % salt_buckets;
      if (modulo < 0) {
        // make sure we return a positive salt.
        modulo = modulo * -1;
      }
      prefixKeyWithSalt(row_key, modulo);
    } else if (old_salting) {
      // DON'T DO THIS! Don't USE IT!
      int modulo = (new String(Arrays.copyOfRange(row_key, salt_width,    
          row_key.length), Const.ASCII_US_CHARSET)).hashCode() % salt_buckets;   
      if (modulo < 0) {
        // make sure we return a positive salt.
        modulo = modulo * -1;   
      }
      prefixKeyWithSalt(row_key, modulo);
    } else {
      int hash = 1;
      for (int i = salt_width; i < row_key.length; i++) {
        hash = 31 * hash + row_key[i];
      }
      int modulo = hash % salt_buckets;
      if (modulo < 0) {
        // make sure we return a positive salt.
        modulo = modulo * -1;
      }
      prefixKeyWithSalt(row_key, modulo);
    }
  }
  
  /**
   * Sets the bucket on the row key. The row key must be pre-allocated
   * the proper number of salt bytes at the start of the array.
   * @param row_key A non-null and non-empty row key.
   * @param bucket A 0 based positive bucket ID.
   */
  public void prefixKeyWithSalt(final byte[] row_key, final int bucket) {
    if (salt_width == 1) {
      row_key[0] = (byte) bucket;
      return;
    }
    
    int shift = 0;
    for (int i = 0; i < salt_width; i++) {
      row_key[salt_width - i - 1] = 0;
      row_key[salt_width - i - 1] = (byte) (bucket >>> shift);
      shift += 8;
    }
  }
  
  public RollupConfig rollupConfig() {
    return factory.rollupConfig();
  }
  
  /**
   * Sets the time in a raw data table row key.
   * 
   * @param row The row to modify.
   * @param base_time The base time to store.
   * @throws IllegalArgumentException if the row was null, empty or too
   * short.
   * @since 2.3
   */
  public void setBaseTime(final byte[] row, final int base_time) {
    if (Bytes.isNullOrEmpty(row)) {
      throw new IllegalArgumentException("Row cannot be null or empty.");
    }
    if (row.length < salt_width + metric_width + TIMESTAMP_BYTES) {
      throw new IllegalArgumentException("Row is too short.");
    }
    Bytes.setInt(row, base_time, salt_width + metric_width);
  }
  
  /** @return Whether or not timeless salting is enabled. */
  public boolean timelessSalting() {
    return timeless_salting;
  }
  
  public net.opentsdb.storage.schemas.tsdb1x.Span<? extends TimeSeriesDataType> newSpan(
      final TypeToken<? extends TimeSeriesDataType> type, 
      final boolean reversed) {
    final Codec codec = codecs.get(type);
    if (codec == null) {
      throw new IllegalArgumentException("No codec loaded for type: " + type);
    }
    return codec.newSequences(reversed);
  }
  
  public RowSeq newRowSeq(final byte prefix, 
      final long base_time) {
    // TODO - implement
    return null;
//    final Codec codec = codecs.get(type);
//    if (codec == null) {
//      throw new IllegalArgumentException("No codec loaded for type: " + type);
//    }
//    return codec.newRowSeq(base_time);
  }

  /**
   * Gets or creates just the tags for a row key given a low level interface to
   * a time series buffer that is already advanced to the proper tag set. The
   * {@link IdOrError#id()} field will contain the byte array of tag key and
   * value combos to use in the row key.
   *
   * @param auth A non-null auth object for authorization.
   * @param metric The non-null metric for logging.
   * @param tags The non-null tags to encode.
   * @param span An optional tracing span.
   * @return A deferred resolving to an IdOrError object or an exception
   *         if something went pear shaped.
   */
  public Deferred<IdOrError> createRowTags(final AuthState auth,
                                           final String metric,
                                           final Map<String, String> tags,
                                           final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".createRowTags")
              .start();
    } else {
      child = null;
    }

    // TODO - inefficient but tested.
    final List<String> tagk_strings =
            Lists.newArrayListWithCapacity(tags.size());
    final List<String> tagv_strings =
            Lists.newArrayListWithCapacity(tags.size());
    final List<IdOrError> tagk_results =
            Lists.newArrayListWithCapacity(tags.size());
    final List<IdOrError> tagv_results =
            Lists.newArrayListWithCapacity(tags.size());
    for (Entry<String, String> entry : tags.entrySet()) {
      tagk_strings.add(entry.getKey());
      tagv_strings.add(entry.getValue());
    }

    class GroupCB implements Callback<IdOrError, ArrayList<Object>> {

      @Override
      public IdOrError call(final ArrayList<Object> ignored) throws Exception {
        // needs to be sorted on the tag keys
        final ByteMap<byte[]> map = new ByteMap<byte[]>();

        WriteState state = WriteState.OK;
        String error = null;
        for (int i = 0; i < tagk_results.size(); i++) {
          final IdOrError tagk = tagk_results.get(i);
          final IdOrError tagv = tagv_results.get(i);

          if (tagk.id() != null && tagv.id() != null) {
            // good pair!
            if (state == WriteState.OK) {
              map.put(tagk.id(), tagv.id());
            }
          } else {
            if (tagk.state().ordinal() >= state.ordinal()) {
              state = tagk.state();
              error = tagk.error();
            }
            if (tagv.state().ordinal() >= state.ordinal()) {
              state = tagv.state();
              error = tagv.error();
            }
          }
        }

        if (state != WriteState.OK) {
          if (child != null) {
            child.setErrorTags()
                    .setTag("state", state.toString())
                    .setTag("message", error)
                    .finish();
          }
          switch (state) {
            case RETRY:
              return IdOrError.wrapRetry(error);
            case REJECTED:
              return IdOrError.wrapRejected(error);
            default:
              return IdOrError.wrapError(error);
          }
        }

        // assume correct sizes
        int idx = 0;
        byte[] tags = new byte[(map.size() * tagk_width) +
                (map.size() * tagv_width)];
        for (final Entry<byte[], byte[]> entry : map.entrySet()) {
          System.arraycopy(entry.getKey(), 0, tags, idx, tagk_width);
          idx += tagk_width;
          System.arraycopy( entry.getValue(), 0, tags, idx, tagv_width);
          idx += tagv_width;
        }

        return IdOrError.wrapId(tags);
      }

    }

    class TagKCB implements Callback<Object, List<IdOrError>> {

      @Override
      public Object call(final List<IdOrError> results) throws Exception {
        tagk_results.addAll(results);
        return null;
      }

    }

    class TagVCB implements Callback<Object, List<IdOrError>> {

      @Override
      public Object call(final List<IdOrError> results) throws Exception {
        tagv_results.addAll(results);
        return null;
      }

    }

    final MetricAndTagsDatumID id_wrapper = new MetricAndTagsDatumID();
    // TODO - for other types that have a tag set but maybe not a metric.
    id_wrapper.metric = metric;
    id_wrapper.tags = tags;
    final List<Deferred<Object>> deferreds = Lists.newArrayListWithCapacity(2);
    deferreds.add(uid_store.getOrCreateIds(auth, UniqueIdType.TAGK,
            tagk_strings, id_wrapper, child)
            .addCallback(new TagKCB()));
    deferreds.add(uid_store.getOrCreateIds(auth, UniqueIdType.TAGV,
            tagv_strings, id_wrapper, child)
            .addCallback(new TagVCB()));
    return Deferred.group(deferreds).addBoth(new GroupCB());
  }

  /**
   * Generates the row key for a given datum, incorporating the optional
   * salt, the timestamp, metric and tags as per the original OpenTSDB
   * schema.
   * <p>
   * If the datum is null, an NPE is thrown. If the value for the datum
   * is null, then we return an {@link IdOrError} with an 
   * {@link WriteState#ERROR} status.
   * <p>
   * Note that this method can assign UIDs if allowed. If fetch or 
   * assignment fails then an {@link IdOrError} with 
   * {@link WriteState#REJECTED} is returned. 
   * 
   * @param auth A non-null auth object for authorization.
   * @param datum A non-null datum object with a non null value.
   * @param interval An optional rollup interval for summaries.
   * @param span An optional tracing span.
   * @return A deferred resolving to an IdOrError object or an exception
   * if something went pear shaped.
   */
  public Deferred<IdOrError> createRowKey(final AuthState auth, 
                                          final TimeSeriesDatum datum,
                                          final DefaultRollupInterval interval,
                                          final Span span) {
    if (datum.value() == null) {
      return Deferred.fromResult(IdOrError.wrapError("Null values are not allowed"));
    }
    
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".createRowKey")
          .start();
    } else {
      child = null;
    }
    
    final int tags_size = ((TimeSeriesDatumStringId) datum.id()).tags() != null ? 
        ((TimeSeriesDatumStringId) datum.id()).tags().size() : 0;
    
    final byte[] row_key = new byte[salt_width + 
                                    metric_width + 
                                    TIMESTAMP_BYTES +
                                    tags_size * (tagk_width + tagv_width)];
    
    // set the timestamp and kick out negatives.
    if (interval != null && datum.value().type() == NumericSummaryType.TYPE) {
      // TODO - handle summaries
    } else {
      long base_time = datum.value().timestamp().epoch();
      if (base_time < 0) {
        return Deferred.fromResult(IdOrError.wrapRejected("Unix epoch "
            + "timestamp cannot have a negative value."));
      }
      base_time = base_time - (base_time % MAX_RAW_TIMESPAN);
      System.arraycopy(Bytes.fromInt((int) base_time), 0, row_key, 
          salt_width + metric_width, TIMESTAMP_BYTES);
    }
    
    // TODO - If summary we may need to add tags to the ID.

    final List<String> tagk_strings =
        Lists.newArrayListWithExpectedSize(tags_size);
    final List<String> tagv_strings =
        Lists.newArrayListWithExpectedSize(tags_size);
    final List<IdOrError> tagk_results =
        Lists.newArrayListWithExpectedSize(tags_size);
    final List<IdOrError> tagv_results =
        Lists.newArrayListWithExpectedSize(tags_size);
    final List<Deferred<Object>> deferreds = 
        Lists.newArrayListWithExpectedSize(2);
    
    class GroupCB implements Callback<IdOrError, ArrayList<Object>> {

      @Override
      public IdOrError call(final ArrayList<Object> ignored) throws Exception {
        // needs to be sorted on the tag keys
        final ByteMap<byte[]> map = new ByteMap<byte[]>();
        
        WriteState state = WriteState.OK;
        String error = null;
        for (int i = 0; i < tagk_results.size(); i++) {
          final IdOrError tagk = tagk_results.get(i);
          final IdOrError tagv = tagv_results.get(i);
          
          if (tagk.id() != null && tagv.id() != null) {
            // good pair!
            if (state == WriteState.OK) {
              map.put(tagk.id(), tagv.id());
            }
          } else {
            if (tagk.state().ordinal() >= state.ordinal()) {
              state = tagk.state();
              error = tagk.error();
            }
            if (tagv.state().ordinal() >= state.ordinal()) {
              state = tagv.state();
              error = tagv.error();
            }
          }
        }
        
        if (state != WriteState.OK) {
          if (child != null) {
            child.setErrorTags()
            .setTag("state", state.toString())
            .setTag("message", error)
            .finish();
          }
          switch (state) {
          case RETRY:
            return IdOrError.wrapRetry(error);
          case REJECTED:
            return IdOrError.wrapRejected(error);
          default:
            return IdOrError.wrapError(error);  
          }
        }
        
        // assume correct sizes
        int idx = salt_width + metric_width + TIMESTAMP_BYTES;
        for (final Entry<byte[], byte[]> entry : map.entrySet()) {
          System.arraycopy(entry.getKey(), 0, row_key, idx, tagk_width);
          idx += tagk_width;
          System.arraycopy( entry.getValue(), 0, row_key, idx, tagv_width);
          idx += tagv_width;
        }
        
        prefixKeyWithSalt(row_key);
        return IdOrError.wrapId(row_key);
      }
      
    }
    
    class TagKCB implements Callback<Object, List<IdOrError>> {

      @Override
      public Object call(final List<IdOrError> results) throws Exception {
        tagk_results.addAll(results);
        return null;
      }
      
    }
    
    class TagVCB implements Callback<Object, List<IdOrError>> {

      @Override
      public Object call(final List<IdOrError> results) throws Exception {
        tagv_results.addAll(results);
        return null;
      }
      
    }
    
    class MetricCB implements Callback<Deferred<IdOrError>, IdOrError> {

      @Override
      public Deferred<IdOrError> call(final IdOrError result) throws Exception {
        if (result.id() == null) {
          if (child != null) {
            child.setErrorTags()
            .setTag("state", result.state().toString())
            .setTag("message", result.error())
            .finish();
          }
          return Deferred.fromResult(result);
        }
        
        // good, copy. Assume it's the proper width.
        System.arraycopy(result.id(), 0, row_key, salt_width, metric_width);
        
        // bail if we don't have any tags.
        if (tags_size < 0) {
          if (child != null) {
            child.setSuccessTags()
            .finish();
          }
         
          prefixKeyWithSalt(row_key);
          return Deferred.fromResult(IdOrError.wrapId(row_key));
        }
        
        // populate the string lists
        for (final Entry<String, String> entry :
          ((TimeSeriesDatumStringId) datum.id()).tags().entrySet()) {
          tagk_strings.add(entry.getKey());
          tagv_strings.add(entry.getValue());
        }
        
        deferreds.add(uid_store.getOrCreateIds(auth, UniqueIdType.TAGK, 
            tagk_strings, datum.id(), child)
              .addCallback(new TagKCB()));
        deferreds.add(uid_store.getOrCreateIds(auth, UniqueIdType.TAGV, 
            tagv_strings, datum.id(), child)
              .addCallback(new TagVCB()));
        return Deferred.group(deferreds).addBoth(new GroupCB());
      }
      
    }
    
    return uid_store.getOrCreateId(auth, UniqueIdType.METRIC, 
        ((TimeSeriesDatumStringId) datum.id()).metric(), datum.id(), child)
          .addCallbackDeferring(new MetricCB());
  }

  /**
   * Gets or creates the UID for the given metric in the Id.
   * @param auth A non-null auth object for authorization.
   * @param id The non-null ID with the metric set.
   * @param span An optional tracing span.
   * @return A deferred resolving to an IdOrError object or an exception
   *         if something went pear shaped.
   */
  public Deferred<IdOrError> createRowMetric(final AuthState auth,
                                             final TimeSeriesDatumStringId id,
                                             final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".createRowKey")
              .start();
    } else {
      child = null;
    }

    return uid_store.getOrCreateId(auth, UniqueIdType.METRIC,
            id.metric(), id, child);
  }

  /**
   * Gets or creates the UID for the given metric.
   * @param auth A non-null auth object for authorization.
   * @param metric The non-null metric to get or assign the UID for.
   * @param tags The non-null tag map for filtering and validation.
   * @param span An optional tracing span.
   * @return A deferred resolving to an IdOrError object or an exception
   *         if something went pear shaped.
   */
  public Deferred<IdOrError> createRowMetric(final AuthState auth,
                                             final String metric,
                                             final Map<String, String> tags,
                                             final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".createRowKey")
              .start();
    } else {
      child = null;
    }

    MetricAndTagsDatumID id = new MetricAndTagsDatumID();
    id.metric = metric;
    id.tags = tags;
    return uid_store.getOrCreateId(auth, UniqueIdType.METRIC,
            metric, id, child);
  }

  /**
   * Looks for the proper encoder and returns a THREAD LOCAL version of that
   * encoder if found.
   * @param type The non-null type to look for.
   * @return A THREAD LOCAL encoder if found, null if not.
   */
  public Codec getEncoder(TypeToken<? extends TimeSeriesDataType> type) {
    final ThreadLocal<Codec> codecs = encoders.get(type);
    if (codecs == null) {
      return null;
    }
    return codecs.get();
  }

  /** @return The meta schema if implemented and assigned, null if not. */
  public MetaDataStorageSchema metaSchema() {
    return meta_schema;
  }

  /**
   * A super simple prefix based suggest API from TSDB 1 to 2x.
   * @param type The non-null type.
   * @param query An optional prefix query. Always anchored at the start. If
   *              null then just return up to max entries.
   * @param max   An optional number of results to return. If less than or equal
   *              to zero, we return a default of ??? 24 I think?
   * @return A Deferred resolving to an error or a list of alphabetically sorted
   * strings matching the query.
   */
  public Deferred<List<String>> suggest(final UniqueIdType type,
                                        final String query,
                                        final int max) {
    return uid_store.suggest(type, query, max);
  }

  String configKey(final String suffix) {
    return "tsd.storage." + (Strings.isNullOrEmpty(id) ? "" : id + ".")
      + suffix;
  }
  
  void setConfig() {
    String key = configKey("uid.width.metric");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, 3, false, 
          "The width, in bytes, of UIDs for metrics.");
    }
    metric_width = tsdb.getConfig().getInt(key);
    
    key = configKey("uid.width.tagk");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, 3, false, 
          "The width, in bytes, of UIDs for tag keys.");
    }
    tagk_width = tsdb.getConfig().getInt(key);
    
    key = configKey("uid.width.tagv");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, 3, false, 
          "The width, in bytes, of UIDs for tag values.");
    }
    tagv_width = tsdb.getConfig().getInt(key);
    
    key = configKey("uid.cache.type.metric");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, "LRU", false, 
          "The name of the UniqueId factory used for caching metric UIDs.");
    }
    
    key = configKey("uid.cache.type.tagk");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, "LRU", false, 
          "The name of the UniqueId factory used for caching tagk UIDs.");
    }
    
    key = configKey("uid.cache.type.tagv");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, "LRU", false, 
          "The name of the UniqueId factory used for caching tagv UIDs.");
    }
    
    key = configKey("salt.buckets");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, 20, false, 
          "The number of salt buckets to spread data into.");
    }
    salt_buckets = tsdb.getConfig().getInt(key);
    
    key = configKey("salt.width");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, 0, false, 
          "The width, in bytes, of the salt prefix in row keys.");
    }
    salt_width = tsdb.getConfig().getInt(key);
    
    key = configKey("data.store");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, null, false, 
          "The name of the data store factory to load and associate "
              + "with this schema.");
    }
    
    key = configKey("rollups.enable");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, false, false, 
          "Whether or not rollups are enabled for this schema.");
    }
    
    key = configKey("rollups.config");
    if (!tsdb.getConfig().hasProperty(key)) {
      tsdb.getConfig().register(key, null, false, 
          "The path to a JSON file containing the rollup configuration.");
    }
  }
  
  @VisibleForTesting
  Tsdb1xDataStore dataStore() {
    return data_store;
  }
  
  @VisibleForTesting
  UniqueIdStore uidStore() {
    return uid_store;
  }
  
  @VisibleForTesting
  UniqueId metrics() {
    return metrics;
  }
  
  @VisibleForTesting
  UniqueId tagNames() {
    return tag_names;
  }
  
  @VisibleForTesting
  UniqueId tagValues() {
    return tag_values;
  }
  
  TSDB tsdb() {
    return tsdb;
  }
  
  public Deferred<TimeSeriesStringId> resolveByteId(final TimeSeriesByteId id, 
                                                    final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".decode")
          .start();
    } else {
      child = null;
    }
    
    final List<byte[]> tagks = Lists.newArrayListWithCapacity(id.tags().size());
    final List<byte[]> tagvs = Lists.newArrayListWithCapacity(id.tags().size());
    final List<String> tagk_strings = Lists.newArrayListWithCapacity(id.tags().size());
    final List<String> tagv_strings = Lists.newArrayListWithCapacity(id.tags().size());
    for (final Entry<byte[], byte[]> pair : id.tags().entrySet()) {
      tagks.add(pair.getKey());
      tagvs.add(pair.getValue());
    }
    
    // resolve the tag keys
    final BaseTimeSeriesStringId.Builder builder = 
        BaseTimeSeriesStringId.newBuilder();
    if (id.alias() != null) {
      builder.setAlias(new String(id.alias(), Const.UTF8_CHARSET));
    }
    if (id.namespace() != null) {
      builder.setNamespace(new String(id.namespace(), Const.UTF8_CHARSET));
    }
    class FinalCB implements Callback<TimeSeriesStringId, ArrayList<Object>> {
      @Override
      public TimeSeriesStringId call(final ArrayList<Object> ignored) throws Exception {
        if (tagk_strings != null) {
          for (int i = 0; i < tagk_strings.size(); i++) {
            builder.addTags(tagk_strings.get(i), tagv_strings.get(i));
          }
        }
        
        final TimeSeriesStringId id = builder.build();
        if (child != null) {
          child.setSuccessTags()
               .finish();
        }
        return id;
      }
    }
    
    class AggDisjointTagCB implements Callback<Object, List<String>> {
      final boolean is_disjoint; // false => agg tags
      
      AggDisjointTagCB(final boolean is_disjoint) {
        this.is_disjoint = is_disjoint;
      }
      
      @Override
      public Object call(final List<String> names) throws Exception {
        for (int i = 0; i < names.size(); i++) {
          if (Strings.isNullOrEmpty(names.get(i))) {
            throw new NoSuchUniqueId(Schema.TAGK_TYPE, 
                is_disjoint ? id.disjointTags().get(i) : id.aggregatedTags().get(i));
          }
          if (is_disjoint) {
            builder.addDisjointTag(names.get(i));
          } else {
            builder.addAggregatedTag(names.get(i));
          }
        }
        return null;
      }
    }
    
    class TagKeyCB implements Callback<Object, List<String>> {
      @Override
      public Object call(final List<String> names) throws Exception {
        for (int i = 0; i < names.size(); i++) {
          if (Strings.isNullOrEmpty(names.get(i))) {
            throw new NoSuchUniqueId(Schema.TAGK_TYPE, tagks.get(i));
          }
          tagk_strings.add(names.get(i));
        }
        return null;
      }
    }
    
    class TagValueCB implements Callback<Object, List<String>> {
      @Override
      public Object call(final List<String> values) throws Exception {
        for (int i = 0; i < values.size(); i++) {
          if (Strings.isNullOrEmpty(values.get(i))) {
            throw new NoSuchUniqueId(Schema.TAGV_TYPE, tagvs.get(i));
          }
          tagv_strings.add(values.get(i));
        }
        return null;
      }
    }
    
    class MetricCB implements Callback<Object, String> {
      @Override
      public Object call(final String metric) throws Exception {
        if (Strings.isNullOrEmpty(metric)) {
          throw new NoSuchUniqueId(Schema.METRIC_TYPE, id.metric());
        }
        builder.setMetric(metric);
        return null;
      }
    }
    
    class ErrCB implements Callback<Object, Exception> {
      @Override
      public Object call(final Exception ex) throws Exception {
        if (child != null) {
          child.setErrorTags()
               .log("Exception", 
                   (ex instanceof DeferredGroupException) ? 
                       Exceptions.getCause((DeferredGroupException) ex) : ex)
               .finish();
        }
        if (ex instanceof DeferredGroupException) {
          final Exception t = (Exception) Exceptions
              .getCause((DeferredGroupException) ex);
          throw t;
        }
        throw ex;
      }
    }
    
    final List<Deferred<Object>> deferreds = 
        Lists.newArrayListWithCapacity(3);
    try {
      // resolve the metric
      if (id.skipMetric()) {
        builder.setMetric(new String(id.metric(), Const.UTF8_CHARSET));
      } else {
        deferreds.add(getName(UniqueIdType.METRIC, id.metric(), 
            child != null ? child : span)
              .addCallback(new MetricCB()));
      }
      
      if (!id.tags().isEmpty()) {
        deferreds.add(this.getNames(UniqueIdType.TAGK, tagks, child)
            .addCallback(new TagKeyCB()));
        deferreds.add(this.getNames(UniqueIdType.TAGV, tagvs, child)
            .addCallback(new TagValueCB()));
      }
      
      if (!id.aggregatedTags().isEmpty()) {
        deferreds.add(this.getNames(UniqueIdType.TAGK, id.aggregatedTags(), child)
            .addCallback(new AggDisjointTagCB(false)));
      }
      
      if (!id.disjointTags().isEmpty()) {
        deferreds.add(this.getNames(UniqueIdType.TAGK, id.disjointTags(), child)
            .addCallback(new AggDisjointTagCB(true)));
      }
      
      return Deferred.group(deferreds)
          .addCallbacks(new FinalCB(), new ErrCB());
    } catch (Exception e) {
      return Deferred.fromError(e);
    }
  }
  
  /**
   * Helper to build a PTS from pooled objects.
   * @param type The type of time series data.
   * @param base_timestamp The base timestamp.
   * @param id_hash The hash of the ID.
   * @param set The set to associate with this PTS.
   * @param interval An optional interval.
   * @return A non-null PTS.
   */
  public Tsdb1xPartialTimeSeries newSeries(
      final TypeToken<? extends TimeSeriesDataType> type,
      final TimeStamp base_timestamp, 
      final long id_hash, 
      final PartialTimeSeriesSet set,
      final RollupInterval interval) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (base_timestamp == null) {
      throw new IllegalArgumentException("Base timestamp cannot be null.");
    }
    if (set == null) {
      throw new IllegalArgumentException("Set cannot be null.");
    }
    
    ObjectPool type_pool = pool_cache.get(type);
    ObjectPool array_pool = null;
    if (type_pool == null) {
       // see if we can grab it from the registry
      if (type == NumericLongArrayType.TYPE) {
        type_pool = tsdb.getRegistry().getObjectPool(
            Tsdb1xNumericPartialTimeSeriesPool.TYPE);
      } else if (type == NumericByteArraySummaryType.TYPE) {
        type_pool = tsdb.getRegistry().getObjectPool(
            Tsdb1xNumericSummaryPartialTimeSeriesPool.TYPE);
      }
      
      if (type_pool == null) {
        throw new IllegalStateException("Unable to find an object pool for "
            + "type: " + type);
      }
      // race but no biggie
      pool_cache.putIfAbsent(type, type_pool);
    }
    
    if (type == NumericLongArrayType.TYPE) {
      array_pool = pool_cache.get(LongArrayPool.TYPE_TOKEN);
      if (array_pool == null) {
        array_pool = tsdb.getRegistry().getObjectPool(LongArrayPool.TYPE);
        pool_cache.putIfAbsent(LongArrayPool.TYPE_TOKEN, array_pool);
      }
    } else if (type == NumericByteArraySummaryType.TYPE) {
      array_pool = pool_cache.get(ByteArrayPool.TYPE_TOKEN);
      if (array_pool == null) {
        array_pool = tsdb.getRegistry().getObjectPool(ByteArrayPool.TYPE);
        pool_cache.putIfAbsent(ByteArrayPool.TYPE_TOKEN, array_pool);
      }
    }
    
    final Tsdb1xPartialTimeSeries series = (Tsdb1xPartialTimeSeries) 
        type_pool.claim().object();
    series.reset(base_timestamp, id_hash, array_pool, set, interval);
    return series;
  }

  class MetricAndTagsDatumID implements TimeSeriesDatumStringId {
    String metric;
    Map<String, String> tags;

    @Override
    public TypeToken<? extends TimeSeriesId> type() {
      return Const.TS_STRING_ID;
    }

    @Override
    public long buildHashCode() {
      throw new UnsupportedOperationException("TODO");
    }

    @Override
    public String namespace() {
      return null;
    }

    @Override
    public String metric() {
      return metric;
    }

    @Override
    public Map<String, String> tags() {
      return tags;
    }

    @Override
    public int compareTo(TimeSeriesDatumId o) {
      throw new UnsupportedOperationException("TODO");
    }
  }
}
