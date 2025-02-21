// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
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
package net.opentsdb.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.opentsdb.rollup.RollupInterval;
import org.hbase.async.HBaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.DeferredGroupException;

import net.opentsdb.common.Const;
import net.opentsdb.data.PartialTimeSeries;
import net.opentsdb.data.TimeSeriesByteId;
import net.opentsdb.data.TimeSeriesDataSource;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.exceptions.IllegalDataException;
import net.opentsdb.exceptions.QueryDownstreamException;
import net.opentsdb.exceptions.QueryExecutionException;
import net.opentsdb.exceptions.QueryUpstreamException;
import net.opentsdb.meta.MetaDataStorageResult;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryNodeFactory;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.rollup.RollupUtils.RollupUsage;
import net.opentsdb.stats.QueryStats;
import net.opentsdb.stats.Span;
import net.opentsdb.storage.HBaseExecutor.State;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.storage.schemas.tsdb1x.Tsdb1xQueryNode;
import net.opentsdb.uid.NoSuchUniqueName;
import net.opentsdb.uid.UniqueIdType;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.Bytes.ByteMap;
import net.opentsdb.utils.Exceptions;

/**
 * A query node implementation for the V1 schema from OpenTSDB. If the 
 * schema was loaded with a meta-data store, the node will query meta
 * first. If the meta results were empty and fallback is enabled, or if
 * meta is not enabled, we'll perform scans.
 * 
 * @since 3.0
 */
public class Tsdb1xHBaseQueryNode implements Tsdb1xQueryNode, Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(
      Tsdb1xHBaseQueryNode.class);

  private static final Deferred<Void> INITIALIZED = 
      Deferred.fromResult(null);
  
  /** A reference to the parent of this node. */
  protected final Tsdb1xHBaseDataStore parent;
  
  /** The pipeline context. */
  protected final QueryPipelineContext context;
  
  /** The upstream query nodes. */
  protected Collection<QueryNode> upstream;
  
  /** The downstream query nodes. */
  protected Collection<QueryNode> downstream;
  
  /** The downstream source nodes. */
  protected Collection<TimeSeriesDataSource> downstream_sources;
  
  /** The query source config. */
  protected final TimeSeriesDataSourceConfig config;
  
  /** The sequence ID counter. */
  protected final AtomicLong sequence_id;
  
  /** Whether the node has been initialized. Initialization starts with
   * the call to {@link #fetchNext(Span)}. */
  protected final AtomicBoolean initialized;
  
  /** Whether or not the node is initializing. This is a block on calling
   * {@link #fetchNext(Span)} multiple times. */
  protected final AtomicBoolean initializing;
  
  /** The executor for this node. */
  protected HBaseExecutor executor;
  
  /** Whether or not to skip NoSuchUniqueName errors for tag keys on resolution. */
  protected final boolean skip_nsun_tagks;
  
  /** Whether or not to skip NoSuchUniqueName errors for tag values on resolution. */
  protected final boolean skip_nsun_tagvs;
  
  /** Whether or not to skip name-less IDs when received from HBase. */
  protected final boolean skip_nsui;
  
  /** Whether or not to delete the data found by this query. */
  protected final boolean delete;
  
  /** Whether or not to push data. */
  protected final boolean push;
  
  /** Rollup fallback mode. */
  protected final RollupUsage rollup_usage;
  
  /** When pushing, whether or not real data was sent. */
  protected final AtomicBoolean sent_data;

  /** Rollup intervals matching the query downsampler if applicable. */
  protected List<RollupInterval> rollup_intervals;
  
  /** When we start fetching data. */
  protected long fetch_start;
  
  /**
   * Default ctor.
   * @param parent The Tsdb1xHBaseDataStore that instantiated this node.
   * @param context A non-null query pipeline context.
   * @param config A non-null config.
   */
  public Tsdb1xHBaseQueryNode(final Tsdb1xHBaseDataStore parent, 
                         final QueryPipelineContext context,
                         final TimeSeriesDataSourceConfig config) {
    if (parent == null) {
      throw new IllegalArgumentException("Parent cannot be null.");
    }
    if (context == null) {
      throw new IllegalArgumentException("Context cannot be null.");
    }
    if (config == null) {
      throw new IllegalArgumentException("Configuration cannot be null.");
    }
    if (context.tsdb().getConfig() == null) {
      throw new IllegalArgumentException("Can't execute a query without "
          + "a configuration in the source config!");
    }
    this.parent = parent;
    this.context = context;
    this.config = config;
    
    sequence_id = new AtomicLong();
    initialized = new AtomicBoolean();
    initializing = new AtomicBoolean();
    sent_data = new AtomicBoolean();
    if (config.hasKey(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGK_KEY)) {
      skip_nsun_tagks = config.getBoolean(context.tsdb().getConfig(), 
          Tsdb1xHBaseDataStore.SKIP_NSUN_TAGK_KEY);
    } else {
      skip_nsun_tagks = parent
          .dynamicBoolean(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGK_KEY);
    }
    if (config.hasKey(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGV_KEY)) {
      skip_nsun_tagvs = config.getBoolean(context.tsdb().getConfig(), 
          Tsdb1xHBaseDataStore.SKIP_NSUN_TAGV_KEY);
    } else {
      skip_nsun_tagvs = parent
          .dynamicBoolean(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGV_KEY);
    }
    if (config.hasKey(Tsdb1xHBaseDataStore.SKIP_NSUI_KEY)) {
      skip_nsui = config.getBoolean(context.tsdb().getConfig(), 
          Tsdb1xHBaseDataStore.SKIP_NSUI_KEY);
    } else {
      skip_nsui = parent
          .dynamicBoolean(Tsdb1xHBaseDataStore.SKIP_NSUI_KEY);
    }
    if (config.hasKey(Tsdb1xHBaseDataStore.DELETE_KEY)) {
      delete = config.getBoolean(context.tsdb().getConfig(), 
          Tsdb1xHBaseDataStore.DELETE_KEY);
    } else {
      delete = parent
          .dynamicBoolean(Tsdb1xHBaseDataStore.DELETE_KEY);
    }
    if (config.hasKey(Tsdb1xHBaseDataStore.ROLLUP_USAGE_KEY)) {
      rollup_usage = RollupUsage.parse(config.getString(context.tsdb().getConfig(),
          Tsdb1xHBaseDataStore.ROLLUP_USAGE_KEY));
    } else {
      rollup_usage = RollupUsage.parse(parent
          .dynamicString(Tsdb1xHBaseDataStore.ROLLUP_USAGE_KEY));
    }
    push = parent.dynamicBoolean(Tsdb1xHBaseDataStore.ENABLE_PUSH_KEY);
  }

  @Override
  public QueryNodeFactory factory() {
    return null;
  }
  
  @Override
  public QueryNodeConfig config() {
    return config;
  }

  @Override
  public synchronized void close() {
    if (executor != null) {
      // releases it to the pool.
      executor.close();
      // Make sure to null it so don't accidentally close a ref to an active
      // executor from another node.
      executor = null;
    }
  }

  @Override
  public void fetchNext(final Span span) {
    // TODO - how do I determine if we have an outstanding request and 
    // should queue or block another fetch? hmmm.
    if (!initialized.get()) {
      if (initializing.compareAndSet(false, true)) {
        setup(span);
        return;
      } else {
        throw new IllegalStateException("Don't call me until I'm "
            + "finished setting up!");
      }
    }

    if (push) {
      executor.fetchNext(null, span);
    } else {
      executor.fetchNext(new Tsdb1xQueryResult(
            sequence_id.getAndIncrement(), 
            Tsdb1xHBaseQueryNode.this, 
            parent.schema()), 
      span);
    }
  }
  
  @Override
  public String[] setIntervals() {
    final String[] intervals = new String[rollup_intervals != null ? 
        rollup_intervals.size() + 1 : 1];
    if (rollup_intervals != null) {
      for (int i = 0; i < rollup_intervals.size(); i++) {
        intervals[i] = rollup_intervals.get(i).getRowSpan();
      }
    }
    intervals[intervals.length - 1] = "1h";
    return intervals;
  }
  
  @Override
  public void onComplete(final QueryNode downstream, 
                         final long final_sequence,
                         final long total_sequences) {
    context.tsdb().getQueryThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        completeUpstream(final_sequence, total_sequences);
      }
    }, this.context.queryContext());
  }

  @Override
  public void onNext(final QueryResult next) {
    final QueryStats stats = pipelineContext().queryContext().stats();
    if (stats != null) {
      stats.incrementRawTimeSeriesCount(next.timeSeries().size());
    }
    if (executor == null) {
      onError(new QueryExecutionException("Executor was null.", 500));
      return;
    }
    final State state = executor.state();
    
    context.tsdb().getQueryThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        sendUpstream(next);
        if (state == State.COMPLETE || 
            state == State.EXCEPTION) {
          completeUpstream(sequence_id.get(), sequence_id.get());
        }
      }
    }, context.queryContext());
  }
  
  @Override
  public void onNext(final PartialTimeSeries series) {
    if (series == null) {
      throw new QueryUpstreamException("Series cannot be null.");
    }
  
    for (final QueryNode node : upstream) {
      try {
        // no need for an executor here as the Tsdb1x PTS set will run the
        // dedupe in the query thread pool so this will be called from there
        // instead of the AsyncHBase threads.
        node.onNext(series);
      } catch (Exception e) {
        throw new QueryUpstreamException("Failed to send series "
            + "upstream to node: " + node, e);
      }
    }
  }

  @Override
  public void onError(final Throwable t) {
    context.tsdb().getQueryThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        if (t instanceof HBaseException) {
          sendUpstream(new QueryExecutionException(t.getMessage(), 502, t));
        } else {
          sendUpstream(t);
        }
      }
    }, context.queryContext());
  }

  @Override
  public TimeStamp sequenceEnd() {
    // TODO implement when the query has this information.
    return null;
  }

  @Override
  public Schema schema() {
    return parent.schema();
  }
  
  @Override
  public Deferred<Void> initialize(final Span span) {
    final Span child;
    if (span != null) {
      child = span.newChild(getClass() + ".initialize()").start();
    } else {
      child = null;
    }

    List<String> rollupIntervals = config.getRollupIntervals();
    if (parent.schema().rollupConfig() != null &&
        rollup_usage != RollupUsage.ROLLUP_RAW &&
        rollupIntervals != null &&
        !rollupIntervals.isEmpty()) {
      rollup_intervals = Lists.newArrayListWithExpectedSize(
          rollupIntervals.size());
      for (final String interval : rollupIntervals) {
        final RollupInterval ri = parent.schema().rollupConfig()
            .getRollupInterval(interval);
        if (ri != null) {
          rollup_intervals.add(ri);
        }
      }
    } else {
      rollup_intervals = null;
    }
    
    upstream = context.upstream(this);
    downstream = context.downstream(this);
    downstream_sources = context.downstreamSources(this);
    if (child != null) {
      child.setSuccessTags().finish();
    }
    return INITIALIZED;
  }
  
  @Override
  public QueryPipelineContext pipelineContext() {
    return context;
  }
  
  @Override
  public void setSentData() {
    sent_data.set(true);
  }
  
  @Override
  public boolean sentData() {
    return sent_data.get();
  }
  
  /**
   * Calls {@link #fetchNext(Span)} on all of the downstream nodes.
   * @param span An optional tracing span.
   */
  protected void fetchDownstream(final Span span) {
    for (final TimeSeriesDataSource source : downstream_sources) {
      source.fetchNext(span);
    }
  }
  
  /**
   * Sends the result to each of the upstream subscribers.
   * 
   * @param result A non-null result.
   * @throws QueryUpstreamException if the upstream 
   * {@link #onNext(QueryResult)} handler throws an exception. I hate
   * checked exceptions but each node needs to be able to handle this
   * ideally by cancelling the query.
   * @throws IllegalArgumentException if the result was null.
   */
  protected void sendUpstream(final QueryResult result) 
        throws QueryUpstreamException {
    if (result == null) {
      throw new IllegalArgumentException("Result cannot be null.");
    }
    for (final QueryNode node : upstream) {
      try {
        node.onNext(result);
      } catch (Exception e) {
        throw new QueryUpstreamException("Failed to send results "
            + "upstream to node: " + node, e);
      }
    }
  }
  
  /**
   * Sends the throwable upstream to each of the subscribing nodes. If 
   * one or more upstream consumers throw an exception, it's caught and
   * logged as a warning.
   * 
   * @param t A non-null throwable.
   * @throws IllegalArgumentException if the throwable was null.
   */
  protected void sendUpstream(final Throwable t) {
    if (t == null) {
      throw new IllegalArgumentException("Throwable cannot be null.");
    }
    
    for (final QueryNode node : upstream) {
      try {
        node.onError(t);
      } catch (Exception e) {
        LOG.warn("Failed to send exception upstream to node: " + node, e);
      }
    }
  }
  
  /**
   * Passes the sequence info upstream to all subscribers. If one or 
   * more upstream consumers throw an exception, it's caught and logged 
   * as a warning.
   * 
   * @param final_sequence The final sequence number to pass.
   * @param total_sequences The total sequence count to pass.
   */
  protected void completeUpstream(final long final_sequence,
                                  final long total_sequences) {
    for (final QueryNode node : upstream) {
      try {
        node.onComplete(this, final_sequence, total_sequences);
      } catch (Exception e) {
        LOG.warn("Failed to mark upstream node complete: " + node, e);
      }
    }
  }
  
  /** @return The parent for this node. */
  Tsdb1xHBaseDataStore parent() {
    return parent;
  }
  
  /** @return Whether or not to skip name-less UIDs found in storage. */
  boolean skipNSUI() {
    return skip_nsui;
  }
  
  /**
   * @param prefix A 1 to 254 prefix for a data type.
   * @return True if the type should be included, false if we're filtering
   * out that data type.
   */
  boolean fetchDataType(final byte prefix) {
    // TODO - implement
    return true;
  }
  
  /** @return Whether or not to delete the found data. */
  boolean deleteData() {
    return delete;
  }

  /** @return Whether or not to push data up the stack. */
  boolean push() {
    return push;
  }
  
  /** @return A list of applicable rollup intervals. May be null. */
  List<RollupInterval> rollupIntervals() {
    return rollup_intervals;
  }
  
  /** @return The rollup usage mode. */
  RollupUsage rollupUsage() {
    return rollup_usage;
  }
  
  /**
   * Initializes the query, either calling meta or setting up the scanner.
   * @param span An optional tracing span.
   */
  @VisibleForTesting
  void setup(final Span span) {
    if (parent.schema().metaSchema() != null) {
      parent.schema().metaSchema().runQuery(context, config, span)
          .addCallback(new MetaCB(span))
          .addErrback(new MetaErrorCB(span));
    } else {
      synchronized (this) {
        //executor = (Tsdb1xScanners) parent.tsdb().getRegistry().getObjectPool(
        //    Tsdb1xScannersPool.TYPE).claim().object();
        executor = new Tsdb1xScanners();
        ((Tsdb1xScanners) executor).reset(Tsdb1xHBaseQueryNode.this, config);
        if (initialized.compareAndSet(false, true)) {
          if (push) {
            executor.fetchNext(null, span);
          } else {
            executor.fetchNext(new Tsdb1xQueryResult(
                sequence_id.incrementAndGet(), 
                Tsdb1xHBaseQueryNode.this, 
                parent.schema()), 
            span);
          }
        } else {
          LOG.error("WTF? We lost an initialization race??");
        }
      }
    }
  }
  
  /**
   * A class to catch exceptions fetching data from meta.
   */
  class MetaErrorCB implements Callback<Object, Exception> {
    final Span span;
    
    MetaErrorCB(final Span span) {
      this.span = span;
    }
    
    @Override
    public Object call(final Exception ex) throws Exception {
      if (span != null) {
        span.setErrorTags(ex)
            .finish();
      }
      sendUpstream(ex);
      return null;
    }
    
  }
  
  /**
   * Handles the logic of what to do based on the results of a meta call
   * e.g. continue with meta if we have data, stop without data or fallback
   * to scans.
   */
  class MetaCB implements Callback<Object, MetaDataStorageResult> {
    final Span span;
    
    MetaCB(final Span span) {
      this.span = span;
    }
    
    @Override
    public Object call(final MetaDataStorageResult result) throws Exception {
      if (span != null) {
        span.setSuccessTags()
            .setTag("metaResponse", result.result().toString())
            .finish();
      }
      
      switch (result.result()) {
      case DATA:
        if (LOG.isDebugEnabled()) {
          LOG.debug("Received results from meta store, setting up "
              + "multi-gets.");
        }
        if (result.timeSeries() != null && !result.timeSeries().isEmpty()) {
          resolveMeta(result, span);
          return null;
        }
        LOG.error("Unexpected result from meta saying we had data but 0 documents.");
        // fall through.
      case NO_DATA:
        if (LOG.isDebugEnabled()) {
          LOG.debug("No data returned from meta store.");
        }
        initialized.compareAndSet(false, true);
        sendUpstream(new Tsdb1xQueryResult(0, Tsdb1xHBaseQueryNode.this, 
            parent.schema()));
        completeUpstream(0, 0);
        return null;
      case EXCEPTION:
        LOG.warn("Unrecoverable exception from meta store: ", 
            result.exception());
        initialized.compareAndSet(false, true);
        sendUpstream(result.exception());
        return null;
      case NO_DATA_FALLBACK:
        if (LOG.isDebugEnabled()) {
          LOG.debug("No data returned from meta store." 
              + " Falling back to scans.");
        }
        break; // fall through to scans
      case HIGH_CARDINALITY_FALLBACK:
        if (LOG.isDebugEnabled()) {
          LOG.debug("More than 5k records returned from meta store."
              + " Falling back to scans.");
        }
        break; // fall through to scans
      case EXCEPTION_FALLBACK:
        LOG.warn("Exception from meta store, falling back", 
            result.exception());
        break;
      default: // fall through to scans
        final QueryDownstreamException ex = new QueryDownstreamException(
            "Unhandled meta result type: " + result.result());
        LOG.error("WTF? Shouldn't happen.", ex);
        initialized.compareAndSet(false, true);
        sendUpstream(ex);
        return null;
      }
      
      synchronized (Tsdb1xHBaseQueryNode.this) {
        //executor = (Tsdb1xScanners) parent.tsdb().getRegistry().getObjectPool(
        //    Tsdb1xScannersPool.TYPE).claim().object();
        executor = new Tsdb1xScanners();
        ((Tsdb1xScanners) executor).reset(Tsdb1xHBaseQueryNode.this, config);
        if (initialized.compareAndSet(false, true)) {
          if (push) {
            executor.fetchNext(null, span);
          } else {
            executor.fetchNext(new Tsdb1xQueryResult(
                sequence_id.incrementAndGet(), 
                Tsdb1xHBaseQueryNode.this, 
                parent.schema()), 
            span);
          }
        } else {
          LOG.error("WTF? We lost an initialization race??");
        }
      }
      return null;
    }
    
  }
  
  /**
   * Processes the list of TSUIDs from the meta data store, resolving 
   * strings to UIDs.
   * @param result A non-null result with the 
   * {@link MetaDataStorageResult#timeSeries()} populated. 
   * @param span An optional tracing span.
   */
  @VisibleForTesting
  void resolveMeta(final MetaDataStorageResult result, final Span span) {
    final Span child;
    if (span != null) {
      child = span.newChild(getClass().getName() + ".resolveMeta").start();
    } else {
      child = span;
    }
    
    final int metric_width = parent.schema().metricWidth();
    final int tagk_width = parent.schema().tagkWidth();
    final int tagv_width = parent.schema().tagvWidth();
    
    if (result.idType() == Const.TS_BYTE_ID) {
      // easy! Just flatten the bytes.
      final List<byte[]> tsuids = Lists.newArrayListWithExpectedSize(
          result.timeSeries().size());
      final byte[] metric = ((TimeSeriesByteId) result.timeSeries()
          .iterator().next()).metric();
      for (final TimeSeriesId raw_id : result.timeSeries()) {
        final TimeSeriesByteId id = (TimeSeriesByteId) raw_id;
        if (Bytes.memcmp(metric, id.metric()) != 0) {
          throw new IllegalDataException("Meta returned two or more "
              + "metrics. The initial metric was " + Bytes.pretty(metric) 
              + " and another was " + Bytes.pretty(id.metric()));
        }
        final byte[] tsuid = new byte[metric_width + 
                                      (id.tags().size() * tagk_width) + 
                                      (id.tags().size() * tagv_width)
                                      ];
        System.arraycopy(id.metric(), 0, tsuid, 0, metric_width);
        int idx = metric_width;
        // no need to sort since the id specifies a ByteMap, already sorted!
        for (final Entry<byte[], byte[]> entry : id.tags().entrySet()) {
          System.arraycopy(entry.getKey(), 0, tsuid, idx, tagk_width);
          idx += tagk_width;
          System.arraycopy(entry.getValue(), 0, tsuid, idx, tagv_width);
          idx += tagv_width;
        }
        
        tsuids.add(tsuid);
      }
      
      synchronized (this) {
        if (initialized.compareAndSet(false, true)) {
          if (child != null) {
            child.setSuccessTags()
                 .finish();
          }
          //executor = (Tsdb1xMultiGet) parent.tsdb().getRegistry().getObjectPool(
          //    Tsdb1xMultiGetPool.TYPE).claim().object();
          executor = new Tsdb1xMultiGet();
          ((Tsdb1xMultiGet) executor).reset(
              Tsdb1xHBaseQueryNode.this, 
              config, 
              tsuids);
          // Don't run in the meta store's threadpool.
          parent.tsdb().getQueryThreadPool().submit(Tsdb1xHBaseQueryNode.this);
        } else {
          LOG.error("WTF? We lost an initialization race??");
        }
      }
    } else {
      final String metric = ((TimeSeriesStringId) 
          result.timeSeries().iterator().next()).metric();
      Set<String> dedupe_tagks = Sets.newHashSet();
      Set<String> dedupe_tagvs = Sets.newHashSet();
      // since it's quite possible that a result would share a number of 
      // common tag keys and values, we dedupe into maps then resolve those 
      // and compile the TSUIDs from them. 
      for (final TimeSeriesId raw_id : result.timeSeries()) {
        final TimeSeriesStringId id = (TimeSeriesStringId) raw_id;
        if (metric != null && !metric.equals(id.metric())) {
          throw new IllegalDataException("Meta returned two or more "
              + "metrics. The initial metric was " + metric 
              + " and another was " + id.metric());
        }
        
        for (final Entry<String, String> entry : id.tags().entrySet()) {
          dedupe_tagks.add(entry.getKey());
          dedupe_tagvs.add(entry.getValue());
        }
      }
      
      // now resolve
      final List<String> tagks = Lists.newArrayList(dedupe_tagks);
      final List<String> tagvs = Lists.newArrayList(dedupe_tagvs);
      final byte[] metric_uid = new byte[parent
                                         .schema().metricWidth()];
      final Map<String, byte[]> tagk_map = 
          Maps.newHashMapWithExpectedSize(tagks.size());
      final Map<String, byte[]> tagv_map = 
          Maps.newHashMapWithExpectedSize(tagvs.size());
      final List<byte[]> tsuids = Lists.newArrayListWithExpectedSize(
          result.timeSeries().size());
      
      /** Catches and passes errors upstream. */
      class ErrorCB implements Callback<Object, Exception> {
        @Override
        public Object call(final Exception ex) throws Exception {
          if (ex instanceof DeferredGroupException) {
            if (child != null) {
              child.setErrorTags(Exceptions.getCause((DeferredGroupException) ex))
                   .finish();
            }
            sendUpstream(Exceptions.getCause((DeferredGroupException) ex));
          } else {
            if (child != null) {
              child.setErrorTags(ex)
                     .finish();
            }
            sendUpstream(ex);
          }
          return null;
        }
      }
      
      /** Handles copying the resolved metric. */
      class MetricCB implements Callback<Object, byte[]> {
        @Override
        public Object call(final byte[] uid) throws Exception {
          if (uid == null) {
            boolean skip = false;
            if (config.hasKey(Tsdb1xHBaseDataStore.SKIP_NSUN_METRIC_KEY)) {
              skip = config.getBoolean(context.tsdb().getConfig(),
                      Tsdb1xHBaseDataStore.SKIP_NSUN_METRIC_KEY);
            } else {
              skip = parent
                      .dynamicBoolean(Tsdb1xHBaseDataStore.SKIP_NSUN_METRIC_KEY);
            }
            if (skip) {
              // return empty result
              return false;
            }

            final NoSuchUniqueName ex =
                    new NoSuchUniqueName(Schema.METRIC_TYPE, metric);
            if (child != null) {
              child.setErrorTags(ex)
                      .finish();
            }
            throw new QueryExecutionException(ex.getMessage(), 400, ex);
          }
          
          for (int i = 0; i < uid.length; i++) {
            metric_uid[i] = uid[i];
          }
          return true;
        }
      }
      
      /** Populates the tag to UID maps. */
      class TagCB implements Callback<Object, List<byte[]>> {
        final boolean is_tagvs;
        
        TagCB(final boolean is_tagvs) {
          this.is_tagvs = is_tagvs;
        }

        @Override
        public Object call(final List<byte[]> uids) throws Exception {
          if (is_tagvs) {
            for (int i = 0; i < uids.size(); i++) {
              if (uids.get(i) == null) {
                if (skip_nsun_tagvs) {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Dropping tag value without an ID: " 
                        + tagvs.get(i));
                  }
                  continue;
                }
                
                final NoSuchUniqueName ex = 
                    new NoSuchUniqueName(Schema.TAGV_TYPE, tagvs.get(i));
                if (child != null) {
                  child.setErrorTags(ex)
                       .finish();
                }
                throw new QueryExecutionException(ex.getMessage(), 400, ex);
              }
              
              tagv_map.put(tagvs.get(i), uids.get(i));
            }
          } else {
            for (int i = 0; i < uids.size(); i++) {
              if (uids.get(i) == null) {
                if (skip_nsun_tagks) {
                  if (LOG.isDebugEnabled()) {
                    LOG.debug("Dropping tag key without an ID: " 
                        + tagks.get(i));
                  }
                  continue;
                }
                
                final NoSuchUniqueName ex = 
                    new NoSuchUniqueName(Schema.TAGK_TYPE, tagks.get(i));
                if (child != null) {
                  child.setErrorTags(ex)
                       .finish();
                }
                throw new QueryExecutionException(ex.getMessage(), 400, ex);
              }
              
              tagk_map.put(tagks.get(i), uids.get(i));
            }
          }
          
          return null;
        }
      }

      /** The final callback that creates the TSUIDs. */
      class GroupCB implements Callback<Object, ArrayList<Object>> {
        @Override
        public Object call(final ArrayList<Object> ignored) throws Exception {
          // ok, fun is.. so we are returning a boolean from the metric CB and
          // if we're skipping we fail here.
          Object metric_resolved = ignored.get(0);
          if (!(Boolean) metric_resolved) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("No UID found for metric {], returning an empty result.", metric);
            }
            synchronized (this) {
              initialized.compareAndSet(false, true);
              sendUpstream(new Tsdb1xQueryResult(0, Tsdb1xHBaseQueryNode.this,
                      parent.schema()));
              completeUpstream(0, 0);
              return null;
            }
          }

          // TODO - maybe a better way but the TSUIDs have to be sorted
          // on the key values.
          final ByteMap<byte[]> sorter = new ByteMap<byte[]>();
          for (final TimeSeriesId raw_id : result.timeSeries()) {
            final TimeSeriesStringId id = (TimeSeriesStringId) raw_id;
            sorter.clear();
            
            boolean keep_goin = true;
            for (final Entry<String, String> entry : id.tags().entrySet()) {
              final byte[] tagk = tagk_map.get(entry.getKey());
              final byte[] tagv = tagv_map.get(entry.getValue());
              if (tagk == null || tagv == null) {
                keep_goin = false;
                break;
              }
              sorter.put(tagk, tagv);
            }
            
            if (!keep_goin) {
              // dropping due to a NSUN tagk or tagv
              continue;
            }
            
            final byte[] tsuid = new byte[metric_width + 
                                          (id.tags().size() * tagk_width) + 
                                          (id.tags().size() * tagv_width)
                                          ];
            System.arraycopy(metric_uid, 0, tsuid, 0, metric_width);
            int idx = metric_width;
            for (final Entry<byte[], byte[]> entry : sorter.entrySet()) {
              System.arraycopy(entry.getKey(), 0, tsuid, idx, tagk_width);
              idx += tagk_width;
              System.arraycopy(entry.getValue(), 0, tsuid, idx, tagv_width);
              idx += tagv_width;
            }
            
            tsuids.add(tsuid);
          }
          
          // TODO - what happens if we didn't resolve anything???
          if (tsuids.isEmpty()) {
            LOG.warn("No TSUIDs found after resolving metadata to UIDs.");
            synchronized (this) {
              initialized.compareAndSet(false, true);
              sendUpstream(new Tsdb1xQueryResult(0, Tsdb1xHBaseQueryNode.this, 
                  parent.schema()));
              completeUpstream(0, 0);
              return null;
            }
          }
          
          synchronized (this) {
            if (initialized.compareAndSet(false, true)) {
              if (child != null) {
                child.setSuccessTags()
                     .finish();
              }
              //executor = (Tsdb1xMultiGet) parent.tsdb().getRegistry().getObjectPool(
              //    Tsdb1xMultiGetPool.TYPE).claim().object();
              executor = new Tsdb1xMultiGet();
              ((Tsdb1xMultiGet) executor).reset(
                  Tsdb1xHBaseQueryNode.this, 
                  config, 
                  tsuids);
              if (push) {
                executor.fetchNext(null, span);
              } else {
                executor.fetchNext(new Tsdb1xQueryResult(
                    sequence_id.incrementAndGet(), 
                    Tsdb1xHBaseQueryNode.this, 
                    parent.schema()), 
                span);
              }
            } else {
              LOG.error("WTF? We lost an initialization race??");
            }
          }
          
          return null;
        }
      }
      
      final List<Deferred<Object>> deferreds = Lists.newArrayListWithCapacity(3);
      deferreds.add(parent.schema()
          .getId(UniqueIdType.METRIC, metric, span)
            .addCallback(new MetricCB()));
      deferreds.add(parent.schema()
          .getIds(UniqueIdType.TAGK, tagks, span)
            .addCallback(new TagCB(false)));
      deferreds.add(parent.schema()
          .getIds(UniqueIdType.TAGV, tagvs, span)
            .addCallback(new TagCB(true)));
      Deferred.groupInOrder(deferreds)
        .addCallback(new GroupCB())
        .addErrback(new ErrorCB());
    }
  }
  
  @Override
  public void run() {
    if (push) {
      executor.fetchNext(null, null);
    } else {
      executor.fetchNext(new Tsdb1xQueryResult(
          sequence_id.incrementAndGet(), 
          Tsdb1xHBaseQueryNode.this, 
          parent.schema()), 
      null);
    }
  }
}
