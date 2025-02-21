// This file is part of OpenTSDB.
// Copyright (C) 2018-2020  The OpenTSDB Authors.
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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.opentsdb.rollup.RollupInterval;
import org.hbase.async.KeyValue;
import org.hbase.async.Scanner;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.DeferredGroupException;

import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import net.opentsdb.common.Const;
import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.types.numeric.NumericByteArraySummaryType;
import net.opentsdb.data.types.numeric.NumericLongArrayType;
import net.opentsdb.exceptions.QueryExecutionException;
import net.opentsdb.pools.CloseablePooledObject;
import net.opentsdb.pools.PooledObject;
import net.opentsdb.query.QueryMode;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.query.filter.FilterUtils;
import net.opentsdb.query.filter.QueryFilter;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.stats.QueryStats;
import net.opentsdb.stats.Span;
import net.opentsdb.stats.StatsCollector.StatsTimer;
import net.opentsdb.storage.HBaseExecutor.State;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.storage.schemas.tsdb1x.TSUID;
import net.opentsdb.storage.schemas.tsdb1x.Tsdb1xPartialTimeSeries;
import net.opentsdb.storage.schemas.tsdb1x.Tsdb1xPartialTimeSeriesSet;
import net.opentsdb.uid.NoSuchUniqueId;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.Exceptions;

/**
 * A single scanner for a single metric within a single salt bucket 
 * (optionally). 
 * <p>
 * While the most efficient scanner is one with a fully configured
 * start and stop key and no {@link Tsdb1xScanners#scannerFilter()}, if 
 * filters are present, then it will resolve the UIDs of the rows into 
 * the string IDs, then filter them and cache the results in sets.
 * <p>
 * If {@link Tsdb1xScanners#sequenceEnd()} is reached or 
 * {@link Tsdb1xScanners#isFull()} is returned, then the scanner can stop
 * mid-result and buffer some data till {@link #fetchNext(Tsdb1xQueryResult, Span)}
 * is called again.
 * <p>
 * When resolving filters, it's possible to ignore UIDs that fail to
 * resolve to a name by setting the {@link #skip_nsui} flag.
 * 
 * TODO - handle reverse scanning for push queries.
 * 
 * @since 3.0
 */
public class Tsdb1xScanner implements CloseablePooledObject {
  private static final Logger LOG = LoggerFactory.getLogger(Tsdb1xScanner.class);
  
  private static final Deferred<ArrayList<KeyValue>> SKIP_DEFERRED = 
      Deferred.fromResult(null);
  
  private static final String SCAN_METRIC = "hbase.scanner.next.latency";
  
  /** Reference to the Object pool for this instance. */
  protected PooledObject pooled_object;
  
  /** The scanner owner to report to. */
  private volatile Tsdb1xScanners owner;
  
  /** The actual HBase scanner to execute. */
  private Scanner scanner;
  
  /** The 0 based index amongst salt buckets. */
  private int idx;
  
  /** An optional rollup interval. */
  private RollupInterval rollup_interval;
  
  /** The current state of this scanner. */
  private State state;
  
  /** When filtering, used to hold the TSUIDs being resolved. */
  protected TLongObjectMap<ResolvingId> keys_to_ids;
  
  /** The set of TSUID hashes that we have resolved and have failed our
   * filter set. */
  protected TLongSet skips;
  
  /** The set of TSUID hashes that we have resolved and matched our filter
   * set. */
  protected TLongSet keepers;
  
  /** A buffer for storing data when we either reach a segment end or 
   * have filled up the result set. Calls to 
   * {@link #fetchNext(Tsdb1xQueryResult, Span)} will process this list
   * before moving on to the scanner. */
  protected List<ArrayList<KeyValue>> row_buffer;
  
  /** A singleton base timestamp for this scanner. */
  protected TimeStamp base_ts;
  
  /** A singleton previous timestamp for this scanner to determine when we hit
   * the next hour or interval. */
  protected TimeStamp last_ts;
  
  /** Map used for holding the previous push series. */
  protected Map<TypeToken<? extends TimeSeriesDataType>, 
    Tsdb1xPartialTimeSeries> last_pts;
  
  /** A timer to measure how long we waited for a response from HBase, including
   *  the request sent time. */
  protected StatsTimer scan_wait_timer;
    
  /**
   * Ctor.
   */
  public Tsdb1xScanner() {
    keys_to_ids = new TLongObjectHashMap<ResolvingId>();
    skips = new TLongHashSet();
    keepers = new TLongHashSet();
    base_ts = new SecondTimeStamp(0);
    last_ts = new SecondTimeStamp(-1);
  }
  
  /**
   * Reset the pooled object.
   * @param owner A non-null owner with configuration and reporting.
   * @param scanner A non-null HBase scanner to work with.
   * @param idx A zero based index when multiple salt scanners are in
   * use.
   * @throws IllegalArgumentException if the owner or scanner was null.
   */
  public void reset(final Tsdb1xScanners owner, 
                    final Scanner scanner, 
                    final int idx,
                    final RollupInterval rollup_interval) {
    if (owner == null) {
      throw new IllegalArgumentException("Owner cannot be null.");
    }
    if (scanner == null) {
      throw new IllegalArgumentException("Scanner cannot be null.");
    }
    this.owner = owner;
    this.scanner = scanner;
    this.idx = idx;
    this.rollup_interval = rollup_interval;
    state = State.CONTINUE;
    base_ts.updateEpoch(-1);
    last_ts.updateEpoch(-1);
    if (owner.node().push() && last_pts == null) {
      last_pts = Maps.newHashMap();
    }
  }
  
  /**
   * Called by the {@link Tsdb1xScanners} to initiate the next fetch of
   * data from the buffer and/or scanner.
   * 
   * @param result A non-null result set to decode the columns we find.
   * @param span An optional tracing span.
   */
  public void fetchNext(final Tsdb1xQueryResult result, final Span span) {
    if (owner.hasException() ||
        owner.node().pipelineContext().queryContext().isClosed()) {
      scanner.close();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Closing scanner due to upstream result exception or "
            + "closed context.");
      }
      state = State.COMPLETE;
      owner.scannerDone();
      return;
    }
    
    if (!owner.node().push() && result.isFull()) {
      if (owner.node().pipelineContext().queryContext().mode() == 
          QueryMode.SINGLE) {
        state = State.EXCEPTION;
        owner.exception(new QueryExecutionException(
            result.resultIsFullErrorMessage(),
            HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode()));
        return;
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Pausing scanner as upstream is full.");
      }
      owner.scannerDone();
      return;
    }
    
    if (row_buffer != null) {
      if (owner.filterDuringScan()) {
        processBufferWithFilter(result, span);
      } else {
        processBuffer(result, span);
      }
    } else {
      // try for some data from HBase
      final Span child;
      if (span != null && span.isDebug()) {
        child = span.newChild(getClass().getName() + ".fetchNext_" + idx)
            .start();
      } else {
        child = span;
      }
      
      scan_wait_timer = owner.node().pipelineContext().tsdb().getStatsCollector()
          .startTimer(SCAN_METRIC, ChronoUnit.MILLIS);
      scanner.nextRows()
        .addCallback(new ScannerCB(result, child))
        .addErrback(new ErrorCB(child));
    }
  }
  
  @Override
  public Object object() {
    return this;
  }
  
  @Override
  public void setPooledObject(final PooledObject pooled_object) {
    this.pooled_object = pooled_object;
  }
  
  @Override
  public void release() {
    if (pooled_object != null) {
      pooled_object.release();
    }
  }
  
  /**
   * Called by {@link #fetchNext(Tsdb1xQueryResult, Span)} to process a 
   * non-null buffer without a scanner filter. Will continue scanning if
   * we haven't hit a segment end.
   * 
   * @param result The non-null result set to decode the columns we find.
   * @param span An optional tracing span.
   */
  private void processBuffer(final Tsdb1xQueryResult result, final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".processBuffer_" + idx)
          .start();
    } else {
      child = span;
    }
    
    try {
      // copy so we can delete and create a new one if necessary
      final List<ArrayList<KeyValue>> row_buffer = this.row_buffer;
      this.row_buffer = null;
      final Iterator<ArrayList<KeyValue>> it = row_buffer.iterator();
      while (it.hasNext()) {
        final ArrayList<KeyValue> row = it.next();
        owner.node().schema().baseTimestamp(row.get(0).key(), base_ts);
        
        if ((!owner.node().push() && result.isFull()) || 
            owner.node().sequenceEnd() != null && 
            base_ts.compare(
                (scanner.isReversed() ? Op.LT : Op.GT), 
                  owner.node().sequenceEnd())) {
          // end of sequence encountered in the buffer. Push on up
          if (LOG.isDebugEnabled()) {
            LOG.debug("Hit next sequence end while in the scanner cache.");
          }
          this.row_buffer = row_buffer;
          if (child != null) {
            child.setSuccessTags()
                 .finish();
          }
          owner.scannerDone();
          return;
        }
        
        it.remove();
        if (owner.node().push()) {
          processPushRow(row);
        } else {
          result.decode(row, rollup_interval);
        }
      }
      
    } catch (Exception e) {
      if (child != null) {
        child.setErrorTags()
             .log("Exception", e)
             .finish();
      }
      owner.exception(e);
      state = State.EXCEPTION;
      scanner.close();
      return;
    }
    
    if (child != null) {
      child.setSuccessTags()
           .finish();
    }
    // all good, keep going with the scanner now.
    scan_wait_timer = owner.node().pipelineContext().tsdb().getStatsCollector()
        .startTimer(SCAN_METRIC, ChronoUnit.MILLIS);
    scanner.nextRows()
      .addCallback(new ScannerCB(result, span))
      .addErrback(new ErrorCB(span));
  }
  
  /**
   * Called by {@link #fetchNext(Tsdb1xQueryResult, Span)} to process a 
   * non-null buffer with a scanner filter. Will continue scanning if
   * we haven't hit a segment end.
   * 
   * @param result The non-null result set to decode the columns we find.
   * @param span An optional tracing span.
   */
  void processBufferWithFilter(final Tsdb1xQueryResult result, final Span span) {
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + 
            ".processBufferWithFilter_" + idx)
          .start();
    } else {
      child = span;
    }
    
    try {
      // copy so we can delete and create a new one if necessary
      final List<ArrayList<KeyValue>> row_buffer = this.row_buffer;
      this.row_buffer = null;
      
      final List<Deferred<ArrayList<KeyValue>>> deferreds = 
          Lists.newArrayListWithCapacity(row_buffer.size());
      final Iterator<ArrayList<KeyValue>> it = row_buffer.iterator();
      
      /** Executed after all of the resolutions are complete. */
      class GroupResolutionCB implements Callback<Object, ArrayList<ArrayList<KeyValue>>> {
        final boolean keep_going;
        
        GroupResolutionCB(final boolean keep_going) {
          this.keep_going = keep_going;
        }
        
        @Override
        public Object call(final ArrayList<ArrayList<KeyValue>> rows) throws Exception {
          for (final ArrayList<KeyValue> row : rows) {
            if (row != null) {
              if (owner.node().push()) {
                processPushRow(row);
              } else {
                result.decode(row, rollup_interval);
              }
            }
          }
          
          if (child != null) {
            child.setSuccessTags()
                 .finish();
          }
          
          keys_to_ids.clear();
          if (owner.hasException()) {
            owner.scannerDone();
            scanner.clearFilter();
            state = State.COMPLETE;
          } else if ((owner.node().push() || !result.isFull()) && keep_going) {
            scan_wait_timer = owner.node().pipelineContext().tsdb().getStatsCollector()
                .startTimer(SCAN_METRIC, ChronoUnit.MILLIS);
            return scanner.nextRows()
                .addCallback(new ScannerCB(result, span))
                .addErrback(new ErrorCB(span));
          } else {
            // told not to keep going.
            owner.scannerDone();
          }
          return null;
        }
      }
      
      boolean keep_going = true;
      synchronized (this) {
        while (it.hasNext()) {
          final ArrayList<KeyValue> row = it.next();
          
          owner.node().schema().baseTimestamp(row.get(0).key(), base_ts);
          if (owner.node().sequenceEnd() != null && 
              base_ts.compare(
                  (scanner.isReversed() ? Op.LT : Op.GT), 
                      owner.node().sequenceEnd())) {
            // end of sequence encountered in the buffer. Push on up
            if (LOG.isDebugEnabled()) {
              LOG.debug("Hit next sequence end while in the scanner cache.");
            }
            this.row_buffer = row_buffer;
            keep_going = false;
            break;
          } else if (!owner.node().push() && result.isFull()) {
            if (owner.node().pipelineContext().queryContext().mode() == 
                  QueryMode.SINGLE) {
              throw new QueryExecutionException(
                  result.resultIsFullErrorMessage(),
                  HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode());
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug("Owner is full while in the scanner cache.");
            }
            this.row_buffer = row_buffer;
            keep_going = false;
            break;
          }
          
          it.remove();
          deferreds.add(resolveAndFilter(row, result, child));
        }
      }
      
      Deferred.groupInOrder(deferreds)
        .addCallback(new GroupResolutionCB(keep_going))
        .addErrback(new ErrorCB(child));
    } catch (Exception e) {
      if (child != null) {
        child.setErrorTags()
             .log("Exception", e)
             .finish();
      }
      owner.exception(e);
      state = State.EXCEPTION;
      scanner.close();
      return;
    }
  }

  /**
   * A callback attached to the scanner's {@link Scanner#nextRows()} call
   * that processes the rows returned.
   */
  final class ScannerCB implements Callback<Object, ArrayList<ArrayList<KeyValue>>> {
    /** The results. */
    private final Tsdb1xQueryResult result;
    
    /** A tracing span. */
    private final Span span;
    
    /** A counter for the total number of rows scanned in this pass/segment. */
    private long rows_scanned = 0;
    
    /**
     * Default ctor.
     * @param result The non-null result.
     * @param span An optional tracing span.
     */
    ScannerCB(final Tsdb1xQueryResult result, final Span span) {
      this.result = result;
      if (span != null && span.isDebug()) {
        this.span = span.newChild(getClass().getName() + "_" + idx)
            .start();
      } else {
        this.span = span;
      }
    }

    @Override
    public Object call(final ArrayList<ArrayList<KeyValue>> rows) throws Exception {
      if (scan_wait_timer != null) {
        scan_wait_timer.stop();
        scan_wait_timer = null;
      }
      
      if (rows == null) {
        complete(null, 0);
        return null;
      }
      
      if (owner.hasException() || 
          owner.node().pipelineContext().queryContext().isClosed()) {
        // bail out!
        complete(null, rows.size());
        return null;
      }
      
      final Span child;
      if (span != null) {
        child = span.newChild(getClass().getName() + "call_" + idx)
            .start();
      } else {
        child = null;
      }
      
      try {
        rows_scanned += rows.size();
        final QueryStats stats = owner.node().pipelineContext().queryContext().stats();
        if (stats != null) {
          long size = 0;
          for (int r = 0; r < rows.size(); r++) {
            final ArrayList<KeyValue> row = rows.get(r);
            for (int k = 0; k < row.size(); k++) {
              size += 8; // timestamp
              final KeyValue kv = row.get(k);
              size += kv.key().length;
              size += kv.family().length;
              size += kv.qualifier().length;
              size += kv.value() != null ? kv.value().length : 0;
            }
          }
          stats.incrementRawDataSize(size);
        }
        
        if (owner.filterDuringScan()) {
          final List<Deferred<ArrayList<KeyValue>>> deferreds = 
              Lists.newArrayListWithCapacity(rows.size());
          boolean keep_going = true;
          for (int i = 0; i < rows.size(); i++) {
            final ArrayList<KeyValue> row = rows.get(i);
            if (row.isEmpty()) {
              // should never happen
              if (LOG.isDebugEnabled()) {
                LOG.debug("Received an empty row from result set: " + rows);
              }
              continue;
            }
            
            owner.node().schema().baseTimestamp(row.get(0).key(), base_ts);
            if (owner.node().sequenceEnd() != null && 
                base_ts.compare(
                    (scanner.isReversed() ? Op.LT : Op.GT), 
                        owner.node().sequenceEnd())) {
              // end of sequence encountered in the buffer. Push on up
              if (LOG.isDebugEnabled()) {
                LOG.debug("Hit next sequence end in the scanner. "
                    + "Buffering results and returning.");
              }
              buffer(i, rows, false);
              keep_going = false;
              break;
            } else if (!owner.node().push() && result.isFull()) {
              if (owner.node().pipelineContext().queryContext().mode() == 
                  QueryMode.SINGLE) {
                throw new QueryExecutionException(
                    result.resultIsFullErrorMessage(),
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode());
              }
              if (LOG.isDebugEnabled()) {
                LOG.debug("Owner is full while in the scanner cache.");
              }
              buffer(i, rows, false);
              keep_going = false;
              break;
            }
            
            deferreds.add(resolveAndFilter(row, result, child));
          }
          
          return Deferred.groupInOrder(deferreds)
              .addCallback(new GroupResolutionCB(keep_going, child))
              .addErrback(new ErrorCB(child));
        } else {
          // load all
          for (int i = 0; i < rows.size(); i++) {
            final ArrayList<KeyValue> row = rows.get(i);
            TimeStamp t = new SecondTimeStamp(0);
            owner.node().schema().baseTimestamp(row.get(0).key(), t);
            if (row.isEmpty()) {
              // should never happen
              if (LOG.isDebugEnabled()) {
                LOG.debug("Received an empty row from result set: " + rows);
              }
              continue;
            }
            
            owner.node().schema().baseTimestamp(row.get(0).key(), base_ts);
            if ((owner.node().sequenceEnd() != null && 
                base_ts.compare(
                    (scanner.isReversed() ? Op.LT : Op.GT), 
                        owner.node().sequenceEnd()))) {
              
              // end of sequence encountered in the buffer. Push on up
              if (LOG.isDebugEnabled()) {
                LOG.debug("Hit next sequence end in the scanner. "
                    + "Buffering results and returning.");
              }
              buffer(i, rows, true);
              return null;
            } else if (!owner.node().push() && result.isFull()) {
              if (owner.node().pipelineContext().queryContext().mode() == 
                  QueryMode.SINGLE) {
                throw new QueryExecutionException(
                    result.resultIsFullErrorMessage(),
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode());
              }
              if (LOG.isDebugEnabled()) {
                LOG.debug("Owner is full. Buffering results and returning.");
              }
              buffer(i, rows, true);
              return null;
            }
            
            if (owner.node().push()) {
              processPushRow(row);
            } else {
              result.decode(row, rollup_interval);
            }
          }
        }
        
        if (owner.node().pipelineContext().queryContext().isClosed()) {
          // fall through.
        } else if (owner.node().push() || !result.isFull()) {
          // keep going!
          if (child != null) {
            child.setSuccessTags()
                 .setTag("rows", rows.size())
                 .setTag("buffered", row_buffer == null ? 0 : row_buffer.size())
                 .finish();
          }
          scan_wait_timer = owner.node().pipelineContext().tsdb().getStatsCollector()
              .startTimer(SCAN_METRIC, ChronoUnit.MILLIS);
          return scanner.nextRows().addCallback(this)
              .addErrback(new ErrorCB(span));
        } else if (owner.node().pipelineContext().queryContext().mode() == 
              QueryMode.SINGLE) {
          throw new QueryExecutionException(
              result.resultIsFullErrorMessage(),
              HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode());
        }
        
        if (owner.hasException() || 
            owner.node().pipelineContext().queryContext().isClosed()) {
          complete(child, rows.size());
        } else {
          // is full or query timed out.
          owner.scannerDone();
        }
      } catch (Throwable t) {
        LOG.error("Unexpected exception", t);
        complete(t, child, rows.size());
      }
      
      return null;
    }
    
    /**
     * Marks the scanner as complete, closing it and reporting to the owner
     * @param child An optional tracing span.
     * @param rows The number of rows found in this result set.
     */
    void complete(final Span child, final int rows) {
      complete(null, child, rows);
    }
    
    /**
     * Marks the scanner as complete, closing it and reporting to the owner
     * @param t An exception, may be null. If not null, calls 
     * {@link Tsdb1xScanners#exception(Throwable)}
     * @param child An optional tracing span.
     * @param rows The number of rows found in this result set.
     */
    void complete(Throwable t, final Span child, final int rows) {
      if (owner.node().push() && t == null && !owner.hasException()) {
        try {
          // this will let the flush complete the last set.
          base_ts.updateEpoch(0L);
          final boolean complete = owner.scannerIndex() + 1 == owner.scannersSize();
          if (last_pts.isEmpty()) {
            // never had any data so for the parent, mark everything as complete 
            // for this salt
            for (final Tsdb1xPartialTimeSeriesSet set : 
              owner.currentSets().valueCollection()) {
              set.setCompleteAndEmpty(complete);
            }
          } else {
            flushPartials();
            if (last_ts.compare(Op.NE, owner.currentTimestamps().getValue())) {
              final Duration duration = owner.currentDuration();
              // We need to fill the end of the period
              last_ts.add(duration);
              while (last_ts.compare(Op.LT, owner.currentTimestamps().getValue())) {
                owner.getSet(last_ts).setCompleteAndEmpty(complete);
                last_ts.add(duration);
              }
            }
          }
        } catch (Throwable t1) {
          LOG.error("Failed to complete push query", t1);
          t = t1;
        }
      }
      
      if (t != null) {
        if (child != null) {
          child.setErrorTags(t)
               .finish();
        }
        if (span != null) {
          span.setErrorTags(t)
              .finish();
        }
        state = State.EXCEPTION;
        owner.exception(t);
      } else {
        if (child != null) {
          child.setSuccessTags()
               .setTag("rows", rows)
               .setTag("buffered", row_buffer == null ? 0 : row_buffer.size())
               .finish();
        }
        if (span != null) {
          span.setSuccessTags()
              .setTag("totalRows", rows_scanned)
              .setTag("buffered", row_buffer == null ? 0 : row_buffer.size())
              .finish();
        }
        state = State.COMPLETE;
      }
      if (scanner != null) {
        scanner.close(); // TODO - attach a callback for logging in case
      }
      // something goes pear shaped.
      owner.scannerDone();
      clear();
    }
    
    /** Called when the filter resolution is complete. */
    class GroupResolutionCB implements Callback<Object, ArrayList<ArrayList<KeyValue>>> {
      final boolean keep_going;
      final Span child;
      
      GroupResolutionCB(final boolean keep_going, final Span span) {
        this.keep_going = keep_going;
        this.child = span;
      }
      
      @Override
      public Object call(final ArrayList<ArrayList<KeyValue>> rows) throws Exception {
        for (final ArrayList<KeyValue> row : rows) {
          if (row != null) {
            if (owner.node().push()) {
              processPushRow(row);
            } else {
              result.decode(row, rollup_interval);
            }
          }
        }
        
        keys_to_ids.clear();
        if (owner.hasException()) {
          complete(child, 0);
        } else if ((owner.node().push() || !result.isFull()) && keep_going) {
          scan_wait_timer = owner.node().pipelineContext().tsdb().getStatsCollector()
              .startTimer(SCAN_METRIC, ChronoUnit.MILLIS);
          return scanner.nextRows()
              .addCallback(ScannerCB.this)
              .addErrback(new ErrorCB(span));
        } else if ((!owner.node().push() && result.isFull()) && 
            owner.node().pipelineContext().queryContext().mode() == 
              QueryMode.SINGLE) {
          complete(new QueryExecutionException(
              result.resultIsFullErrorMessage(),
              HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.getCode()), child, 0);
        } else {
          // told not to keep going.
          owner.scannerDone();
        }
        return null;
      }
    }
  }
  
  /**
   * Writes the remaining rows to the buffer.
   * @param i A zero based offset in the rows array to buffer.
   * @param rows The non-null rows list.
   * @param mark_scanner_done Whether or not to call {@link Tsdb1xScanners#scannerDone()}.
   */
  private void buffer(int i, 
                      final ArrayList<ArrayList<KeyValue>> rows, 
                      final boolean mark_scanner_done) {
    if (row_buffer == null) {
      row_buffer = Lists.newArrayListWithCapacity(rows.size() - i);
    }
    for (; i < rows.size(); i++) {
      row_buffer.add(rows.get(i));
      TimeStamp t = new MillisecondTimeStamp(0);
      owner.node().schema().baseTimestamp(rows.get(i).get(0).key(), t);
    }
    if (mark_scanner_done) {
      owner.scannerDone();
    }
  }
  
  /** @return The state of this scanner. */
  State state() {
    return state;
  }
  
  @Override
  public void close() {
    try {
      if (scanner != null) {
        scanner.close();
      }
    } catch (Exception e) {
      LOG.error("Failed to close scanner", e);
    }
    clear();
    owner = null;
    scanner = null;
    rollup_interval = null;
    release();
  }
  
  /** Clears the filter map and sets when the scanner is done so GC can
   * clean up quicker. */
  private void clear() {
    if (keys_to_ids != null) {
      synchronized (keys_to_ids) {
        keys_to_ids.clear();
      }
    }
    if (skips != null) {
      synchronized (skips) {
        skips.clear();
      }
    }
    if (keepers != null) {
      synchronized (keepers) {
        keepers.clear();
      }
    }
    if (row_buffer != null) {
      row_buffer.clear();
    }
    if (last_pts != null) {
      last_pts.clear();
    }
  }

  /**
   * WARNING: Only call this single threaded!
   * @param row the row to process.
   */
  private void processPushRow(final ArrayList<KeyValue> row) {
    try {
      owner.node().schema().baseTimestamp(row.get(0).key(), base_ts);
      if (base_ts.compare(Op.NE, last_ts)) {
        final Duration duration = owner.currentDuration();
        final boolean complete = owner.scannerIndex() + 1 == 
            owner.scannersSize();
        if (last_ts.epoch() == -1) {
          // we found the first value. So if we don't match the first 
          // set then we need to fill
          if (base_ts.compare(Op.NE, owner.currentTimestamps().getKey())) {
            TimeStamp ts = owner.currentTimestamps().getKey().getCopy();
            while (ts.compare(Op.LT, base_ts)) {
              owner.getSet(ts).setCompleteAndEmpty(complete);
              ts.add(duration);
            }
          }
        } else {
          TimeStamp ts = last_ts.getCopy();
          ts.add(duration);
          if (ts.compare(Op.NE, base_ts)) {
            // FILL
            flushPartials();
            while (ts.compare(Op.LT, base_ts)) {
              owner.getSet(ts).setCompleteAndEmpty(complete);
              ts.add(duration);
            }
          }
        }
        
        // flush em!
        flushPartials();
      }
      last_ts.update(base_ts);
      
      final long hash = owner.node().schema().getTSUIDHash(row.get(0).key());
      // TODO - find a better spot. We may not pull any data from this row so we
      // shouldn't bother putting it in the ids.
      if (!owner.node().pipelineContext().hasId(hash, Const.TS_BYTE_ID)) {
        owner.node().pipelineContext().addId(hash, 
            new TSUID(owner.node().schema().getTSUID(row.get(0).key()), 
                      owner.node().schema()));
      }
      
      Tsdb1xPartialTimeSeries pts = rollup_interval != null ? 
          last_pts.get(NumericByteArraySummaryType.TYPE)
          : last_pts.get(NumericLongArrayType.TYPE);
      for (final KeyValue column : row) {
        if (rollup_interval == null && (column.qualifier().length & 1) == 0) {
          // it's a NumericDataType
          if (!owner.node().fetchDataType((byte) 0)) {
            // filter doesn't want #'s
            // TODO - dropped counters
            continue;
          }
          
          if (pts == null) {
            pts = owner.node().schema().newSeries(
                NumericLongArrayType.TYPE, 
                base_ts, 
                hash, 
                owner.getSet(base_ts), 
                rollup_interval);
            last_pts.put(NumericLongArrayType.TYPE, pts);
          } else if (pts.value().type() != NumericLongArrayType.TYPE) {
            pts = last_pts.get(NumericLongArrayType.TYPE);
            if (pts == null) {
              pts = owner.node().schema().newSeries(
                  NumericLongArrayType.TYPE, 
                  base_ts, 
                  hash, 
                  owner.getSet(base_ts), 
                  rollup_interval);
              last_pts.put(NumericLongArrayType.TYPE, pts);
            }
          }
          
          if (!pts.sameHash(hash)) {
            flushPartials();
            pts = owner.node().schema().newSeries(
                NumericLongArrayType.TYPE, 
                base_ts, 
                hash, 
                owner.getSet(base_ts), 
                rollup_interval);
            last_pts.put(NumericLongArrayType.TYPE, pts);
          }
          
          pts.addColumn((byte) 0,
                        column.qualifier(), 
                        column.value());
        } else if (rollup_interval == null) {
          final byte prefix = column.qualifier()[0];
          
          if (prefix == Schema.APPENDS_PREFIX) {
            if (!owner.node().fetchDataType((byte) 1)) {
              // filter doesn't want #'s
              continue;
            } else {
              if (pts == null) {
                pts = owner.node().schema().newSeries(
                    NumericLongArrayType.TYPE, 
                    base_ts, 
                    hash, 
                    owner.getSet(base_ts), 
                    rollup_interval);
                last_pts.put(NumericLongArrayType.TYPE, pts);
              } else if (pts.value().type() != NumericLongArrayType.TYPE) {
                pts = last_pts.get(NumericLongArrayType.TYPE);
                if (pts == null) {
                  pts = owner.node().schema().newSeries(
                      NumericLongArrayType.TYPE, 
                      base_ts, 
                      hash, 
                      owner.getSet(base_ts), 
                      rollup_interval);
                  last_pts.put(NumericLongArrayType.TYPE, pts);
                }
              }
  
              if (!pts.sameHash(hash)) {
                flushPartials();
                pts = owner.node().schema().newSeries(
                    NumericLongArrayType.TYPE, 
                    base_ts, 
                    hash, 
                    owner.getSet(base_ts), 
                    rollup_interval);
                last_pts.put(NumericLongArrayType.TYPE, pts);
              }
              
              pts.addColumn(Schema.APPENDS_PREFIX, 
                            column.qualifier(), 
                            column.value());
            }
          } else if (owner.node().fetchDataType(prefix)) {
            // TODO - find the right type
          } else {
            // TODO - log drop
          }
        } else {
          // Only numerics are rolled up right now. And we shouldn't have
          // a rollup query if the user doesn't want rolled-up data.
          pts = last_pts.get(NumericByteArraySummaryType.TYPE);
          if (pts == null) {
            pts = owner.node().schema().newSeries(
                NumericByteArraySummaryType.TYPE, 
                base_ts, hash, 
                owner.getSet(base_ts), 
                rollup_interval);
            last_pts.put(NumericByteArraySummaryType.TYPE, pts);
          } else if (pts.value().type() != NumericByteArraySummaryType.TYPE) {
            pts = last_pts.get(NumericByteArraySummaryType.TYPE);
            if (pts == null) {
              pts = owner.node().schema().newSeries(
                  NumericByteArraySummaryType.TYPE, 
                  base_ts, 
                  hash, 
                  owner.getSet(base_ts), 
                  rollup_interval);
              last_pts.put(NumericByteArraySummaryType.TYPE, pts);
            }
          }
  
          if (!pts.sameHash(hash)) {
            flushPartials();
            pts = owner.node().schema().newSeries(
                NumericByteArraySummaryType.TYPE, 
                base_ts, 
                hash, 
                owner.getSet(base_ts), 
                rollup_interval);
            last_pts.put(NumericByteArraySummaryType.TYPE, pts);
          }
          
          pts.addColumn(Schema.APPENDS_PREFIX, 
                        column.qualifier(), 
                        column.value());
        }
      }
    } catch (Throwable t) {
      LOG.error("Unexpected exception processing a row", t);
      throw t;
    }
  }
  
  /**
   * Runs through the cached PTSs and sends them to the set.
   */
  private void flushPartials() {
    try {
      final Iterator<Tsdb1xPartialTimeSeries> iterator = 
          last_pts.values().iterator();
      while (iterator.hasNext()) {
        Tsdb1xPartialTimeSeries series = iterator.next();
        if (!iterator.hasNext() && series.set().start().compare(Op.NE, base_ts)) {
          ((Tsdb1xPartialTimeSeriesSet) series.set()).increment(series, true);
        } else {
          ((Tsdb1xPartialTimeSeriesSet) series.set()).increment(series, false);
        }
        iterator.remove();
      }
    } catch (Throwable t) {
      LOG.error("Failed to flush partial PTSs", t);
      owner.exception(t);
    }
  }
  
  /** The error back used to catch all kinds of exceptions. Closes out 
   * everything after passing the exception to the owner. */
  final class ErrorCB implements Callback<Object, Exception> {
    final Span span;
    
    ErrorCB(final Span span) {
      this.span = span;
    }
    
    @Override
    public Object call(final Exception ex) throws Exception {
      LOG.error("Unexpected exception", 
          (ex instanceof DeferredGroupException ? 
              Exceptions.getCause((DeferredGroupException) ex) : ex));
      state = State.EXCEPTION;
      if (owner != null) {
        owner.exception((ex instanceof DeferredGroupException ? 
            Exceptions.getCause((DeferredGroupException) ex) : ex));
        owner.scannerDone();
      }
      if (scanner != null) {
        scanner.close();
      }
      clear();
      return null;
    }
  }

  @VisibleForTesting
  List<ArrayList<KeyValue>> buffer() {
    return row_buffer;
  }

  /**
   * Evaluates a row against the skips, keepers and may resolve it if
   * necessary when we have to go through filters that couldn't be sent
   * to HBase.
   * 
   * @param row A non-null row to process.
   * @param result A non-null result to store successful matches into.
   * @param span An optional tracing span.
   * @return A deferred to wait on before starting the next fetch.
   */
  final Deferred<ArrayList<KeyValue>> resolveAndFilter(
                                          final ArrayList<KeyValue> row, 
                                          final Tsdb1xQueryResult result, 
                                          final Span span) {
    final long hash = owner.node().schema().getTSUIDHash(row.get(0).key());
    boolean skip_or_keep;
    synchronized (skips) {
      skip_or_keep = skips.contains(hash);
    }
    if (skip_or_keep) {
      // discard
      // TODO - counters
      return SKIP_DEFERRED;
    }
    
    synchronized (keepers) {
      skip_or_keep = keepers.contains(hash);
    }
    if (skip_or_keep) {
      return Deferred.fromResult(row);
    }
    
    final Deferred<Boolean> d = new Deferred<Boolean>();
    ResolvingId id = null;
    synchronized (keys_to_ids) {
      id = keys_to_ids.get(hash);
    }
    
    if (id == null) {
      ResolvingId new_id = new ResolvingId(
          owner.node().schema().getTSUID(row.get(0).key()), hash);
      final ResolvingId extant;
      // check for a race to avoid resolving the same ID multiple times.
      synchronized (keys_to_ids) {
        extant = keys_to_ids.putIfAbsent(hash, new_id);
      }
      if (extant == null) {
        // start resolution of the tags to strings, then filter
        new_id.addCallback(d);
        new_id.decode(span);
        return d.addCallback(new ResolvedCB(row));
      } else {
        // add it
        extant.addCallback(d);
        return d.addCallback(new ResolvedCB(row));
      }
    } else {
      id.addCallback(d);
      return d.addCallback(new ResolvedCB(row));
    }
  }
  
  /** Simple class for rows waiting on resolution. */
  class ResolvedCB implements Callback<ArrayList<KeyValue>, Boolean> {
    private final ArrayList<KeyValue> row;
    
    ResolvedCB(final ArrayList<KeyValue> row) {
      this.row = row;
    }
    
    @Override
    public ArrayList<KeyValue> call(final Boolean matched) throws Exception {
      if (matched != null && matched) {
        return row;
      }
      return null;
    }
    
  }
  
  /**
   * An override of the {@link TSUID} class that holds a reference to the
   * resolution deferred so others rows with different timestamps but the
   * same TSUID can wait for a single result to be resolved.
   * <p>
   * <b>NOTE:</b> Do not call {@link TSUID#decode(boolean, Span)} directly!
   * Instead call {@link ResolvingId#decode(Span)}.
   * <p>
   * <b>NOTE:</b> If skip_nsui was set to true, this will return a false
   * for any rows that didn't resolve properly. If set to true, then this
   * will return a {@link NoSuchUniqueId} exception.
   */
  private class ResolvingId extends TSUID implements Callback<Void, TimeSeriesStringId> {
    /** The computed hash of the TSUID. */
    private final long hash;
    
    private boolean complete;
    private Object result;
    private List<Deferred<Boolean>> deferreds;
    
    /** A child tracing span. */
    private Span child;
    
    /**
     * Default ctor.
     * @param tsuid A non-null TSUID.
     * @param hash The computed hash of the TSUID.
     */
    public ResolvingId(final byte[] tsuid, final long hash) {
      super(tsuid, owner.node().schema());
      this.hash = hash;
      deferreds = Lists.newArrayList();
    }

    synchronized void addCallback(final Deferred<Boolean> cb) {
      if (complete) {
        try {
          cb.callback(result);
        } catch (Exception e) {
          LOG.error("Failed to execute callback on: " + cb, e);
          cb.callback(e);
        }
      } else {
        deferreds.add(cb);
      }
    }
    
    /**
     * Starts decoding the TSUID into a string and returns the deferred 
     * for other TSUIDs to wait on.
     * 
     * @param span An optional tracing span.
     * @return A deferred resolving to true if the TSUID passed all of
     * the scan filters, false if not. Or an exception if something went
     * pear shaped.
     */
    void decode(final Span span) {
      if (span != null && span.isDebug()) {
        child = span.newChild(getClass().getName() + "_" + idx)
            .start();
      } else {
        child = span;
      }
      decode(false, child)
          .addCallback(this)
          .addErrback(new ErrorCB(null));
    }
    
    @Override
    public Void call(final TimeSeriesStringId id) throws Exception {
      final Span grand_child;
      if (child != null && child.isDebug()) {
        grand_child = child.newChild(getClass().getName() + ".call_" + idx)
            .start();
      } else {
        grand_child = child;
      }
      
      QueryFilter filter = ((TimeSeriesDataSourceConfig) 
          owner.node().config()).getFilter();
      if (filter == null) {
        filter = owner.node().pipelineContext().query().getFilter(
            ((TimeSeriesDataSourceConfig) owner.node().config()).getFilterId());
      }
      if (filter == null) {
        final IllegalStateException ex = new IllegalStateException(
            "No filter found when resolving filter IDs?");
        if (child != null) {
          child.setErrorTags(ex)
               .finish();
        }
        complete(ex);
        return null;
      }
      
      if (FilterUtils.matchesTags(filter, id.tags(), null)) {
        synchronized (keepers) {
          keepers.add(hash);
        }
        if (grand_child != null) {
          grand_child.setSuccessTags()
                     .setTag("resolved", "true")
                     .setTag("matched", "true")
                     .finish();
        }
        if (child != null) {
          child.setSuccessTags()
               .setTag("resolved", "true")
               .setTag("matched", "true")
               .finish();
        }
        complete(true);
        return null;
      } else {
        synchronized (skips) {
          skips.add(hash);
        }
        if (grand_child != null) {
          grand_child.setSuccessTags()
                     .setTag("resolved", "true")
                     .setTag("matched", "false")
                     .finish();
        }
        if (child != null) {
          child.setSuccessTags()
               .setTag("resolved", "true")
               .setTag("matched", "false")
               .finish();
        }
        complete(false);
        return null;
      }
    }
    
    synchronized void complete(final Object result) {
      this.result = result;
      for (final Deferred<Boolean> cb : deferreds) {
        try {
          cb.callback(result);
        } catch (Exception e) {
          LOG.error("Unexpected exception processing filter callback", e);
          owner.exception(e);
        }
      }
      complete = true;
    }
    
    class ErrorCB implements Callback<Void, Exception> {
      final Span grand_child;
      
      ErrorCB(final Span grand_child) {
        this.grand_child = grand_child;
      }
      
      @Override
      public Void call(final Exception ex) throws Exception {
        if (ex instanceof NoSuchUniqueId && owner.node().skipNSUI()) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Row contained a bad UID: " + Bytes.pretty(tsuid) 
              + " " + ex.getMessage());
          }
          synchronized (skips) {
            skips.add(hash);
          }
          if (grand_child != null) {
            grand_child.setSuccessTags()
                       .setTag("resolved", "false")
                       .finish();
          }
          if (child != null) {
            child.setSuccessTags()
                 .setTag("resolved", "false")
                 .finish();
          }
          complete(false);
          return null;
        }
        if (grand_child != null) {
          grand_child.setErrorTags(ex)
                     .setTag("resolved", "false")
                     .finish();
        }
        if (child != null) {
          child.setErrorTags(ex)
               .setTag("resolved", "false")
               .finish();
        }
        complete(ex);
        return null;
      }
    }
  }
}
