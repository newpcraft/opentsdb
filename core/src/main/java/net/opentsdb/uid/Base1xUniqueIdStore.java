// This file is part of OpenTSDB.
// Copyright (C) 2010-2021  The OpenTSDB Authors.
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
package net.opentsdb.uid;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;

import net.opentsdb.storage.StorageException;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.auth.AuthState;
import net.opentsdb.data.TimeSeriesDatumId;
import net.opentsdb.stats.Span;
import net.opentsdb.uid.IdOrError;
import net.opentsdb.uid.RandomUniqueId;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueIdAssignmentAuthorizer;
import net.opentsdb.uid.UniqueIdStore;
import net.opentsdb.uid.UniqueIdType;
import net.opentsdb.utils.Bytes;

/**
 * Represents a table of Unique IDs, manages the lookup and creation of IDs.
 * <p>
 * Don't attempt to use {@code equals()} or {@code hashCode()} on
 * this class.
 *
 * @since 1.0
 */
public abstract class Base1xUniqueIdStore implements UniqueIdStore {
  private static final Logger LOG = LoggerFactory.getLogger(Base1xUniqueIdStore.class);

  public static final byte[] EMPTY_ARRAY = new byte[0];

  /** The start row to scan on empty search strings.  `!' = first printable ASCII char.
   * NOTE: Use only for suggest which is ASCII only. */
  protected static final byte[] START_ROW = new byte[] { '!' };

  /** The end row to scan on empty search strings.  `~' = last printable ASCII char.
   * NOTE: Use only for suggest which is ASCII only. */
  protected static final byte[] END_ROW = new byte[] { '~' };


  /** A map of the various config UID types. */
  public static final Map<UniqueIdType, String> CONFIG_PREFIX =
      Maps.newHashMapWithExpectedSize(UniqueIdType.values().length);
  static {
    for (final UniqueIdType type : UniqueIdType.values()) {
      CONFIG_PREFIX.put(type, "uid." + type.toString().toLowerCase() + ".");
    }
  }

  /** Various configuration keys. */
  public static final String CHARACTER_SET_KEY = "character_set";
  public static final String CHARACTER_SET_DEFAULT = "ISO-8859-1";
  public static final String ASSIGN_AND_RETRY_KEY = "assign_and_retry";
  public static final String RANDOM_ASSIGNMENT_KEY = "assign_random";
  public static final String LOG_ASSIGNMENT_KEY = "log_assignments";
  public static final String RANDOM_ATTEMPTS_KEY = "attempts.max_random";
  public static final String ATTEMPTS_KEY = "attempts.max";
  
  /** Static byte arrays for the various type qualifiers. */
  public static final byte[] METRICS_QUAL = 
      "metrics".getBytes(Const.ASCII_CHARSET);
  public static final byte[] TAG_NAME_QUAL = 
      "tagk".getBytes(Const.ASCII_CHARSET);
  public static final byte[] TAG_VALUE_QUAL = 
      "tagv".getBytes(Const.ASCII_CHARSET);

  /** The single column family used by this class. */
  public static final byte[] ID_FAMILY = "id".getBytes(Const.ASCII_CHARSET);
  /** The single column family used by this class. */
  public static final byte[] NAME_FAMILY = "name".getBytes(Const.ASCII_CHARSET);
  /** Row key of the special row used to track the max ID already assigned. */
  public static final byte[] MAXID_ROW = { 0 };
  /** How many time do we try to assign an ID before giving up. */
  public static final short DEFAULT_ATTEMPTS_ASSIGN_ID = 3;
//  /** How many time do we try to apply an edit before giving up. */
//  public static final short MAX_ATTEMPTS_PUT = 6;
  /** How many time do we try to assign a random ID before giving up. */
  public static final short DEFAULT_ATTEMPTS_ASSIGN_RANDOM_ID = 10;
//  /** Initial delay in ms for exponential backoff to retry failed RPCs. */
//  private static final short INITIAL_EXP_BACKOFF_DELAY = 800;
  /** Maximum number of results to return in suggest(). */
  public static final short MAX_SUGGESTIONS = 25;

  public final Schema schema;
  public final TSDB tsdb;
  public final String id;
  
  /** The authorizer pulled from the registry. */
  protected final UniqueIdAssignmentAuthorizer authorizer;
  
  /** Character sets for each type. */
  protected final Charset metric_character_set;
  protected final Charset tagk_character_set;
  protected final Charset tagv_character_set;
  
  /** A map of type to pending assignments. Protected for testing. */
  protected final Map<UniqueIdType, Map<String, Deferred<IdOrError>>> pending_assignments;
  
  /** Whether or not to immediately return a RETRY state and run the 
   * assignment process in the background. */
  protected final boolean assign_and_retry;
  
  /** Max attempts. */
  protected final short max_attempts_assign;
  protected final short max_attempts_assign_random;
  
  /** Whethe ror not to randomize UID assignments for these types. */
  protected final boolean randomize_metric_ids;
  protected final boolean randomize_tagk_ids;
  protected final boolean randomize_tagv_ids;

  public Base1xUniqueIdStore(final Schema schema, final TSDB tsdb, final String id) {
    this.schema = schema;
    this.tsdb = tsdb;
    this.id = id;
    registerConfigs(tsdb);
    
    authorizer = tsdb.getRegistry()
        .getDefaultPlugin(UniqueIdAssignmentAuthorizer.class);
    
    metric_character_set = Charset.forName(tsdb.getConfig()
        .getString(configKey(
            CONFIG_PREFIX.get(UniqueIdType.METRIC) + CHARACTER_SET_KEY)));
    tagk_character_set = Charset.forName(tsdb.getConfig()
        .getString(configKey(
            CONFIG_PREFIX.get(UniqueIdType.TAGK) + CHARACTER_SET_KEY)));
    tagv_character_set = Charset.forName(tsdb.getConfig()
        .getString(configKey(
            CONFIG_PREFIX.get(UniqueIdType.TAGV) + CHARACTER_SET_KEY)));
    
    randomize_metric_ids = tsdb.getConfig()
        .getBoolean(configKey(
            CONFIG_PREFIX.get(UniqueIdType.METRIC) + RANDOM_ASSIGNMENT_KEY));
    randomize_tagk_ids = tsdb.getConfig()
        .getBoolean(configKey(
            CONFIG_PREFIX.get(UniqueIdType.TAGK) + RANDOM_ASSIGNMENT_KEY));
    randomize_tagv_ids = tsdb.getConfig()
        .getBoolean(configKey(
            CONFIG_PREFIX.get(UniqueIdType.TAGV) + RANDOM_ASSIGNMENT_KEY));
    
    assign_and_retry = tsdb.getConfig()
        .getBoolean(configKey(ASSIGN_AND_RETRY_KEY));
    max_attempts_assign = (short) tsdb.getConfig()
        .getInt(configKey(ATTEMPTS_KEY));
    max_attempts_assign_random = (short) tsdb.getConfig()
        .getInt(configKey(RANDOM_ATTEMPTS_KEY));
    
    // It's important to create maps here.
    pending_assignments = Maps.newHashMapWithExpectedSize(
        UniqueIdType.values().length);
    for (final UniqueIdType type : UniqueIdType.values()) {
      pending_assignments.put(type, Maps.newConcurrentMap());
    }
    
    LOG.info("Initialized UniqueId store.");
  }
  
//  /** Returns the number of random UID collisions */
//  public int randomIdCollisions() {
//    return random_id_collisions;
//  }
//  
//  /** Returns the number of UID assignments rejected by the filter */
//  public int rejectedAssignments() {
//    return rejected_assignments;
//  }
//  public short width() {
//    return id_width;
//  }
//
//  /** @param tsdb Whether or not to track new UIDMeta objects */
//  public void setTSDB(final TSDB tsdb) {
//    this.tsdb = tsdb;
//  }
//  
//  /** The largest possible ID given the number of bytes the IDs are 
//   * represented on.
//   * @deprecated Use {@link Internal.getMaxUnsignedValueOnBytes}
//   */
//  public long maxPossibleId() {
//    return Internal.getMaxUnsignedValueOnBytes(id_width);
//  }
  
  @Override
  public Deferred<String> getName(final UniqueIdType type, 
                                  final byte[] id,
                                  final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (Bytes.isNullOrEmpty(id)) {
      throw new IllegalArgumentException("ID cannot be null or empty.");
    }
    
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".getName")
          .withTag("dataStore", this.id)
          .withTag("type", type.toString())
          .withTag("id", net.opentsdb.uid.UniqueId.uidToString(id))
          .start();
    } else {
      child = null;
    }
    
    class ErrorCB implements Callback<String, Exception> {
      @Override
      public String call(final Exception ex) throws Exception {
        if (child != null) {
          child.setErrorTags()
            .log("Exception", ex)
            .finish();
        }
        throw new StorageException("Failed to fetch name.", ex);
      }
    }
    
    class NameFromHBaseCB implements Callback<String, byte[]> {
      @Override
      public String call(final byte[] name) {
        if (child != null) {
          child.setSuccessTags()
            .finish();
        }
        return name == null ? null : new String(name, characterSet(type));
      }
    }
    
    try {
      return get(type, id, NAME_FAMILY)
          .addCallbacks(new NameFromHBaseCB(), new ErrorCB());
    } catch (Exception e) {
      if (child != null) {
        child.setErrorTags()
          .log("Exception", e)
          .finish();
      }
      return Deferred.fromError(new StorageException(
          "Unexpected exception from storage", e));
    }
  }
  

  
  @Override
  public Deferred<byte[]> getId(final UniqueIdType type, 
                                final String name,
                                final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (Strings.isNullOrEmpty(name)) {
      throw new IllegalArgumentException("Name cannot be null or empty.");
    }
    
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getName() + ".getId")
          .withTag("dataStore", id)
          .withTag("type", type.toString())
          .withTag("name", name)
          .start();
    } else {
      child = null;
    }
    
    class ErrorCB implements Callback<byte[], Exception> {
      @Override
      public byte[] call(final Exception ex) throws Exception {
        if (child != null) {
          child.setErrorTags()
            .log("Exception", ex)
            .finish();
        }
        throw new StorageException("Failed to fetch ID.", ex);
      }
    }
    
    class SpanCB implements Callback<byte[], byte[]> {
      @Override
      public byte[] call(final byte[] uid) throws Exception {
        child.setSuccessTags()
          .finish();
        return uid;
      }
    }

    try {
      if (child != null) {
        return get(type, name.getBytes(characterSet(type)), ID_FAMILY)
            .addCallbacks(new SpanCB(), new ErrorCB());
      }
      return get(type, name.getBytes(characterSet(type)), ID_FAMILY)
          .addErrback(new ErrorCB());
    } catch (Exception e) {
      if (child != null) {
        child.setErrorTags()
          .log("Exception", e)
          .finish();
      }
      return Deferred.fromError(new StorageException(
          "Unexpected exception from storage", e));
    }
  }

  /**
   * Implements the process to allocate a new UID.
   * This callback is re-used multiple times in a four step process:
   *   1. Allocate a new UID via atomic increment.
   *   2. Create the reverse mapping (ID to name).
   *   3. Create the forward mapping (name to ID).
   *   4. Return the new UID to the caller.
   */
  final class UniqueIdAllocator implements Callback<Object, Object>, TimerTask {
    private final UniqueIdType type;
    private final String name;  // What we're trying to allocate an ID for.
    private final Deferred<IdOrError> assignment; // deferred to call back
    private final boolean randomize_id; // Whether or not to randomize.
    private final byte[] qualifier;
    private final int id_width;
    private final short attempts;
    private short attempt;     // Give up when zero.
    private boolean run_timer = false;
    
    private Exception ex = null;  // Last exception caught.
    // TODO(manolama) - right now if we retry the assignment it will create a 
    // callback chain MAX_ATTEMPTS_* long and call the ErrBack that many times.
    // This can be cleaned up a fair amount but it may require changing the 
    // public behavior a bit. For now, the flag will prevent multiple attempts
    // to execute the callback.
    private boolean called = false; // whether we called the deferred or not

    private long id = -1;  // The ID we'll grab with an atomic increment.
    private byte row[];    // The same ID, as a byte array.

    private static final byte ALLOCATE_UID = 0;
    private static final byte CREATE_REVERSE_MAPPING = 1;
    private static final byte CREATE_FORWARD_MAPPING = 2;
    private static final byte DONE = 3;
    private byte state = ALLOCATE_UID;  // Current state of the process.

    UniqueIdAllocator(final UniqueIdType type, 
                      final String name, 
                      final Deferred<IdOrError> assignment) {
      this.type = type;
      this.name = name;
      this.assignment = assignment;
      
      switch (type) {
      case METRIC:
        randomize_id = randomize_metric_ids;
        qualifier = METRICS_QUAL;
        id_width = schema.metricWidth();
        attempts = attempt = randomize_metric_ids ?
            max_attempts_assign_random : max_attempts_assign;
        break;
      case TAGK:
        randomize_id = randomize_tagk_ids;
        qualifier = TAG_NAME_QUAL;
        id_width = schema.tagkWidth();
        attempts = attempt = randomize_tagk_ids ?
            max_attempts_assign_random : max_attempts_assign;
        break;
      case TAGV:
        randomize_id = randomize_tagv_ids;
        qualifier = TAG_VALUE_QUAL;
        id_width = schema.tagvWidth();
        attempts = attempt = randomize_tagv_ids ?
            max_attempts_assign_random : max_attempts_assign;
        break;
      default:
        purgeWaiting();
        throw new IllegalArgumentException("Unhandled type: " + type);
      }
    }

    Deferred<IdOrError> tryAllocate() {
      attempt--;
      state = ALLOCATE_UID;
      call(null);
      return assignment;
    }

    @SuppressWarnings("unchecked")
    public Object call(final Object arg) {
      if (attempt == 0) {
        final String err;
        switch (type) {
        case METRIC:
        case TAGK:
        case TAGV:
          err = "Failed to assign an ID for kind='" + type
          + "' name='" + name + "' after " + attempts + " attempts.";
          break;
        default:
          purgeWaiting();
          throw new IllegalArgumentException("Unhandled type: " + type);
        }
        
        if (ex != null) {
          LOG.error(err, ex);
        } else {
          LOG.error(err);
        }
        purgeWaiting();
        assignment.callback(IdOrError.wrapRetry(err));
        return null;
      }

      if (arg instanceof Exception) {
        final String msg = ("Failed attempt #" + (randomize_id
                         ? (max_attempts_assign_random - attempt) 
                         : (max_attempts_assign - attempt))
                         + " to assign an UID for " + type + ':' + name
                         + " at step #" + state);
        if (arg instanceof StorageException) {
          LOG.error(msg, (Exception) arg);
          ex = (StorageException) arg;
          attempt--;
          state = ALLOCATE_UID;  // Retry from the beginning.
        } else {
          purgeWaiting();
          LOG.error("WTF?  Unexpected exception!  " + msg, (Exception) arg);
          return arg;  // Unexpected exception, let it bubble up.
        }
      }

      if (state == ALLOCATE_UID && attempt < attempts - 1 && run_timer) {
        retryWithBackoff();
        return null;
      } else {
        run_timer = true;
      }
      
      class ErrBack implements Callback<Object, Exception> {
        public Object call(final Exception e) throws Exception {
          if (!called) {
            LOG.warn("Failed pending assignment for: " + name, e);
            purgeWaiting();
            assignment.callback(e);
            called = true;
          }
          return assignment;
        }
      }
      
      @SuppressWarnings("rawtypes")
      final Deferred d;
      switch (state) {
        case ALLOCATE_UID:
          d = allocateUid();
          break;
        case CREATE_REVERSE_MAPPING:
          d = createReverseMapping(arg);
          break;
        case CREATE_FORWARD_MAPPING:
          d = createForwardMapping(arg);
          break;
        case DONE:
          return done(arg);
        default:
          purgeWaiting();
          throw new AssertionError("Should never be here!");
      }
      return d.addBoth(this).addErrback(new ErrBack());
    }

    /** Generates either a random or a serial ID. If random, we need to
     * make sure that there isn't a UID collision.
     */
    private Deferred<Long> allocateUid() {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Creating " + (randomize_id ? "a random " : "an ") + 
            "ID for kind='" + type + "' name='" + name + '\'');
      }

      state = CREATE_REVERSE_MAPPING;
      if (randomize_id) {
        return Deferred.fromResult(RandomUniqueId.getRandomUID(id_width));
      } else {
        // TODO - fix in AsyncHBase
        try {
          return increment(MAXID_ROW, ID_FAMILY, qualifier);
        } catch (ArrayIndexOutOfBoundsException e) {
          purgeWaiting();
          throw new StorageException("Atomic increment "
              + "counter may be corrupt as it doesn't appear to be 8 "
              + "bytes long", e);
        }
      }
    }

    /**
     * Create the reverse mapping.
     * We do this before the forward one so that if we die before creating
     * the forward mapping we don't run the risk of "publishing" a
     * partially assigned ID.  The reverse mapping on its own is harmless
     * but the forward mapping without reverse mapping is bad as it would
     * point to an ID that cannot be resolved.
     */
    private Deferred<Boolean> createReverseMapping(final Object arg) {
      if (!(arg instanceof Long)) {
        purgeWaiting();
        throw new IllegalStateException("Expected a Long but got " + arg);
      }
      id = (Long) arg;
      if (id <= 0) {
        purgeWaiting();
        throw new IllegalStateException("Got a negative ID from storage: " + id);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Got ID=" + id
                 + " for kind='" + type + "' name='" + name + "'");
      }
      row = Bytes.fromLong(id);
      // row.length should actually be 8.
      if (row.length < id_width) {
        purgeWaiting();
        throw new IllegalStateException("OMG, row.length = " + row.length
                                        + " which is less than " + id_width
                                        + " for id=" + id
                                        + " row=" + Arrays.toString(row));
      }
      // Verify that we're going to drop bytes that are 0.
      for (int i = 0; i < row.length - id_width; i++) {
        if (row[i] != 0) {
          final String message = "All Unique IDs for " + type
            + " on " + id_width + " bytes are already assigned!";
          LOG.error("OMG " + message);
          purgeWaiting();
          throw new IllegalStateException(message);
        }
      }
      // Shrink the ID on the requested number of bytes.
      row = Arrays.copyOfRange(row, row.length - id_width, row.length);

      state = CREATE_FORWARD_MAPPING;
      // We are CAS'ing the KV into existence -- the second argument is how
      // we tell HBase we want to atomically create the KV, so that if there
      // is already a KV in this cell, we'll fail.  Technically we could do
      // just a `put' here, as we have a freshly allocated UID, so there is
      // not reason why a KV should already exist for this UID, but just to
      // err on the safe side and catch really weird corruption cases, we do
      // a CAS instead to create the KV.
      return cas(row, NAME_FAMILY, qualifier, name.getBytes(characterSet(type)),
              EMPTY_ARRAY);
    }

    private Deferred<?> createForwardMapping(final Object arg) {
      if (!(arg instanceof Boolean)) {
        purgeWaiting();
        throw new IllegalStateException("Expected a Boolean but got " + arg);
      }
      if (!((Boolean) arg)) {  // Previous CAS failed. 
        if (randomize_id) {
          // This random Id is already used by another row
          LOG.warn("Detected random id collision and retrying kind='" + 
              type + "' name='" + name + "'");
          //random_id_collisions++; // TODO
        } else {
          // something is really messed up then
          LOG.error("WTF!  Failed to CAS reverse mapping: " + name
              + " -- run an fsck against the UID table!");
        }
        attempt--;
        state = ALLOCATE_UID;
        //retryWithBackoff();
        return Deferred.fromResult(false);
      }

      state = DONE;
      return cas(name.getBytes(characterSet(type)),
              ID_FAMILY,
              qualifier,
              row,
              EMPTY_ARRAY);
    }


    private Deferred<IdOrError> done(final Object arg) {
      if (!(arg instanceof Boolean)) {
        throw new IllegalStateException("Expected a Boolean but got " + arg);
      }
      if (!((Boolean) arg)) {  // Previous CAS failed.  We lost a race.
        LOG.warn("Race condition: tried to assign ID " + id + " to "
                 + type + ":" + name + ", but CAS failed which indicates this "
                 + "UID must have"
                 + " been allocated concurrently by another TSD or thread. "
                 + "So ID " + id + " was leaked.");
        // If two TSDs attempted to allocate a UID for the same name at the
        // same time, they would both have allocated a UID, and created a
        // reverse mapping, and upon getting here, only one of them would
        // manage to CAS this KV into existence.  The one that loses the
        // race will retry and discover the UID assigned by the winner TSD,
        // and a UID will have been wasted in the process.  No big deal.
        if (randomize_id) {
          // This random Id is already used by another row
          LOG.warn("Detected random id collision between two tsdb "
              + "servers kind='" + type + "' name='" + name + "'");
          //random_id_collisions++;
        }
        
        class GetIdCB implements Callback<Object, byte[]> {
          public Object call(final byte[] id) throws Exception {
            purgeWaiting();
            assignment.callback(IdOrError.wrapId(id));
            return null;
          }
        }
        getId(type, name, null /* TODO */).addCallback(new GetIdCB());
        return assignment;
      }
      
      // TODO - UID meta
//      if (tsdb != null && tsdb.getConfig().enable_realtime_uid()) {
//        final UIDMeta meta = new UIDMeta(type, row, name);
//        meta.storeNew(tsdb);
//        LOG.info("Wrote UIDMeta for: " + name);
//        tsdb.indexUIDMeta(meta);
//      }
      purgeWaiting();
      assignment.callback(IdOrError.wrapId(row));
      return assignment;
    }

    private void purgeWaiting() {
      final Map<String, Deferred<IdOrError>> ids_pending = 
          pending_assignments.get(type);
      synchronized(ids_pending) {
        if (ids_pending.remove(name) != null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Completed pending assignment for: " + name);
          }
        }
      }
    }

    private void retryWithBackoff() {
      if (state != ALLOCATE_UID) {
        throw new IllegalStateException("We can't retry with a backoff "
            + "if the state isn't ALLOCATE_UID: " + state);
      }
      // backoff
      final int diff = (attempts - attempt);
      final int delay = diff < 4
          ? 200 * (diff)     // 200, 400, 600, 800
              : 1000 + (1 << diff);  // 1016, 1032, 1064, 1128, 1256, 1512, ..
      run_timer = false;
      tsdb.getMaintenanceTimer().newTimeout(this, 
          delay, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void run(final Timeout ignored) throws Exception {
      try {
        call(null);
      } catch (Exception e) {
        LOG.error("Unexpected exception caught in timmer handler", e);
        purgeWaiting();
        assignment.callback(e);
      }
    }
  }
  
  @Override
  public Deferred<IdOrError> getOrCreateId(final AuthState auth,
                                           final UniqueIdType type, 
                                           final String name,
                                           final TimeSeriesDatumId id,
                                           final Span span) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (Strings.isNullOrEmpty(name)) {
      throw new IllegalArgumentException("Name cannot be null or empty.");
    }
    if (id == null) {
      throw new IllegalArgumentException("The ID cannot be null.");
    }
    
    final Span child;
    if (span != null && span.isDebug()) {
      child = span.newChild(getClass().getSimpleName() + ".getOrCreateId")
          .withTag("dataStore", this.id)
          .withTag("type", type.toString())
          .withTag("name", name)
          .withTag("id", id.toString())
          .start();
    } else {
      child = null;
    }
    
    /** Triggers the assignment if allowed through the filter */
    class AssignmentAllowedCB implements  Callback<Deferred<IdOrError>, String> {
      @Override
      public Deferred<IdOrError> call(final String error) throws Exception {
        if (!Strings.isNullOrEmpty(error)) {
          return Deferred.fromResult(IdOrError.wrapRejected(error));
        }
        
        Deferred<IdOrError> assignment = null;
        final Map<String, Deferred<IdOrError>> typed_assignments = 
            pending_assignments.get(type);
        synchronized (typed_assignments) {
          assignment = typed_assignments.get(name);
          if (assignment == null) {
            // to prevent UID leaks that can be caused when multiple time
            // series for the same metric or tags arrive, we need to write a 
            // deferred to the pending map as quickly as possible. Then we can 
            // start the assignment process after we've stashed the deferred 
            // and released the lock
            assignment = new Deferred<IdOrError>();
            typed_assignments.put(name, assignment);
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.info("Already waiting for UID assignment: '" + name + "'");
            }
            if (assign_and_retry) {
              // aww, still pending.
              return Deferred.fromResult(IdOrError.ASSIGNMENT_RETRY);
            } else {
              return assignment;
            }
          }
        }
        
        // start the assignment dance after stashing the deferred
        if (tsdb.getConfig().hasProperty(
                configKey(LOG_ASSIGNMENT_KEY)) &&
          LOG.isInfoEnabled()) {
          LOG.info("Assigning UID for '" + name + "' of type '" + type +
                  "' for series '" + id + "'");
        }
        
        // start the assignment dance after stashing the deferred
        if (assign_and_retry) {
          new UniqueIdAllocator(type, name, assignment).tryAllocate();
          return Deferred.fromResult(IdOrError.ASSIGNMENT_RETRY);
        }
        return new UniqueIdAllocator(type, name, assignment).tryAllocate();
      }
      @Override
      public String toString() {
        return "AssignmentAllowedCB";
      }
    }
    
    /** Triggers the assignment path if the response was null. */
    class IdCB implements Callback<Deferred<IdOrError>, byte[]> {
      public Deferred<IdOrError> call(final byte[] uid) {
        if (uid != null) {
          return Deferred.fromResult(IdOrError.wrapId(uid));
        }

        if (authorizer != null && authorizer.fillterUIDAssignments()) {
          return authorizer.allowUIDAssignment(auth, type, name, id)
              .addCallbackDeferring(new AssignmentAllowedCB());
        } else {
          return Deferred.fromResult((String) null)
              .addCallbackDeferring(new AssignmentAllowedCB());
        }
      }
    }

    // Kick off the lookup, and if we don't find it there either, start
    // the process to allocate a UID.
    return getId(type, name, child).addCallbackDeferring(new IdCB());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Deferred<List<IdOrError>> getOrCreateIds(final AuthState auth,
                                                  final UniqueIdType type, 
                                                  final List<String> names,
                                                  final TimeSeriesDatumId id,
                                                  final Span span) {
    final List<IdOrError> results = 
        Lists.newArrayListWithExpectedSize(names.size());
    for (int i = 0; i < names.size(); i++) {
      results.add(null);
    }
    
    final List<Deferred<Object>> deferreds =
        Lists.newArrayListWithExpectedSize(names.size());
    
    class ResponseCB implements Callback<Object, Object> {
      final int idx;
      
      ResponseCB(final int idx) {
        this.idx = idx;
      }
      
      @Override
      public Object call(final Object response) throws Exception {
        
        if (response instanceof IdOrError) {
          results.set(idx, (IdOrError) response);
        } else {
          if (response instanceof Exception) {
            results.set(idx, IdOrError.wrapError(
                ((Exception) response).getMessage(), (Exception) response));
          } else {
            results.set(idx, IdOrError.wrapError(
                "Unknown response type: " + response));
          }
        }
        return null;
      }
    }
    
    for (int i = 0; i < names.size(); i++) {
      try {
        @SuppressWarnings("rawtypes")
        final Deferred d = getOrCreateId(auth, type, names.get(i), id, span);
        deferreds.add(d.addBoth(new ResponseCB(i)));
      } catch (Exception e) {
        results.set(i, IdOrError.wrapError(e.getMessage()));
      }
    }
    
    class GroupCB implements Callback<List<IdOrError>, ArrayList<Object>> {
      @Override
      public List<IdOrError> call(ArrayList<Object> arg) throws Exception {
        return results;
      }
    }
    
    return Deferred.groupInOrder(deferreds).addCallback(new GroupCB());
  }

//  TODO - this is for deletions. Will need it for full bwc with 2.x
//    final byte[] row = getId(oldname);
//    final String row_string = fromBytes(row);
//    {
//      byte[] id = null;
//      try {
//        id = getId(newname);
//      } catch (NoSuchUniqueName e) {
//        // OK, we don't want the new name to be assigned.
//      }
//      if (id != null) {
//        throw new IllegalArgumentException("When trying rename(\"" + oldname
//          + "\", \"" + newname + "\") on " + this + ": new name already"
//          + " assigned ID=" + Arrays.toString(id));
//      }
//    }
//
//    if (renaming_id_names.contains(row_string)
//        || renaming_id_names.contains(newname)) {
//      throw new IllegalArgumentException("Ongoing rename on the same ID(\""
//        + Arrays.toString(row) + "\") or an identical new name(\"" + newname
//        + "\")");
//    }
//    renaming_id_names.add(row_string);
//    renaming_id_names.add(newname);
//
//    final byte[] newnameb = toBytes(newname);
//
//    // Update the reverse mapping first, so that if we die before updating
//    // the forward mapping we don't run the risk of "publishing" a
//    // partially assigned ID.  The reverse mapping on its own is harmless
//    // but the forward mapping without reverse mapping is bad.
//    try {
//      final PutRequest reverse_mapping = new PutRequest(
//        table, row, NAME_FAMILY, kind, newnameb);
//      hbasePutWithRetry(reverse_mapping, MAX_ATTEMPTS_PUT,
//                        INITIAL_EXP_BACKOFF_DELAY);
//    } catch (HBaseException e) {
//      LOG.error("When trying rename(\"" + oldname
//        + "\", \"" + newname + "\") on " + this + ": Failed to update reverse"
//        + " mapping for ID=" + Arrays.toString(row), e);
//      renaming_id_names.remove(row_string);
//      renaming_id_names.remove(newname);
//      throw e;
//    }
//
//    // Now create the new forward mapping.
//    try {
//      final PutRequest forward_mapping = new PutRequest(
//        table, newnameb, ID_FAMILY, kind, row);
//      hbasePutWithRetry(forward_mapping, MAX_ATTEMPTS_PUT,
//                        INITIAL_EXP_BACKOFF_DELAY);
//    } catch (HBaseException e) {
//      LOG.error("When trying rename(\"" + oldname
//        + "\", \"" + newname + "\") on " + this + ": Failed to create the"
//        + " new forward mapping with ID=" + Arrays.toString(row), e);
//      renaming_id_names.remove(row_string);
//      renaming_id_names.remove(newname);
//      throw e;
//    }
//
//    // Update cache.
//    addIdToCache(newname, row);            // add     new name -> ID
//    id_cache.put(fromBytes(row), newname);  // update  ID -> new name
//    name_cache.remove(oldname);             // remove  old name -> ID
//
//    // Delete the old forward mapping.
//    try {
//      final DeleteRequest old_forward_mapping = new DeleteRequest(
//        table, toBytes(oldname), ID_FAMILY, kind);
//      client.delete(old_forward_mapping).joinUninterruptibly();
//    } catch (HBaseException e) {
//      LOG.error("When trying rename(\"" + oldname
//        + "\", \"" + newname + "\") on " + this + ": Failed to remove the"
//        + " old forward mapping for ID=" + Arrays.toString(row), e);
//      throw e;
//    } catch (Exception e) {
//      final String msg = "Unexpected exception when trying rename(\"" + oldname
//        + "\", \"" + newname + "\") on " + this + ": Failed to remove the"
//        + " old forward mapping for ID=" + Arrays.toString(row);
//      LOG.error("WTF?  " + msg, e);
//      throw new RuntimeException(msg, e);
//    } finally {
//      renaming_id_names.remove(row_string);
//      renaming_id_names.remove(newname);
//    }
//    // Success!
//  }
//
//    if (tsdb == null) {
//      throw new IllegalStateException("The TSDB is null for this UID object.");
//    }
//    final byte[] uid = new byte[id_width];
//    final ArrayList<Deferred<Object>> deferreds = 
//        new ArrayList<Deferred<Object>>(2);
//    
//    /** Catches errors and still cleans out the cache */
//    class ErrCB implements Callback<Object, Exception> {
//      @Override
//      public Object call(final Exception ex) throws Exception {
//        name_cache.remove(name);
//        id_cache.remove(fromBytes(uid));
//        LOG.error("Failed to delete " + fromBytes(kind) + " UID " + name 
//            + " but still cleared the cache", ex);
//        return ex;
//      }
//    }
//    
//    /** Used to wait on the group of delete requests */
//    class GroupCB implements Callback<Deferred<Object>, ArrayList<Object>> {
//      @Override
//      public Deferred<Object> call(final ArrayList<Object> response) 
//          throws Exception {
//        name_cache.remove(name);
//        id_cache.remove(fromBytes(uid));
//        LOG.info("Successfully deleted " + fromBytes(kind) + " UID " + name);
//        return Deferred.fromResult(null);
//      }
//    }
//    
//    /** Called after fetching the UID from storage */
//    class LookupCB implements Callback<Deferred<Object>, byte[]> {
//      @Override
//      public Deferred<Object> call(final byte[] stored_uid) throws Exception {
//        if (stored_uid == null) {
//          return Deferred.fromError(new NoSuchUniqueName(kind(), name));
//        }
//        System.arraycopy(stored_uid, 0, uid, 0, id_width);
//        final DeleteRequest forward = 
//            new DeleteRequest(table, toBytes(name), ID_FAMILY, kind);
//        deferreds.add(tsdb.getClient().delete(forward));
//        
//        final DeleteRequest reverse = 
//            new DeleteRequest(table, uid, NAME_FAMILY, kind);
//        deferreds.add(tsdb.getClient().delete(reverse));
//        
//        final DeleteRequest meta = new DeleteRequest(table, uid, NAME_FAMILY, 
//            toBytes((type.toString().toLowerCase() + "_meta")));
//        deferreds.add(tsdb.getClient().delete(meta));
//        return Deferred.group(deferreds).addCallbackDeferring(new GroupCB());
//      }
//    }
//    
//    final byte[] cached_uid = name_cache.get(name);
//    if (cached_uid == null) {
//      return getIdFromHBase(name).addCallbackDeferring(new LookupCB())
//          .addErrback(new ErrCB());
//    }
//    System.arraycopy(cached_uid, 0, uid, 0, id_width);
//    final DeleteRequest forward = 
//        new DeleteRequest(table, toBytes(name), ID_FAMILY, kind);
//    deferreds.add(tsdb.getClient().delete(forward));
//    
//    final DeleteRequest reverse = 
//        new DeleteRequest(table, uid, NAME_FAMILY, kind);
//    deferreds.add(tsdb.getClient().delete(reverse));
//    
//    final DeleteRequest meta = new DeleteRequest(table, uid, NAME_FAMILY, 
//        toBytes((type.toString().toLowerCase() + "_meta")));
//    deferreds.add(tsdb.getClient().delete(meta));
//    return Deferred.group(deferreds).addCallbackDeferring(new GroupCB())
//        .addErrback(new ErrCB());
//  }

  /**
   * Returns the cell of the specified row key, using family:kind.
   * @param type The non-null type.
   * @param key The non-null key.
   * @param family The non-null column family.
   * @return A deferred to wait on resolving to the ID or null.
   */
  protected abstract Deferred<byte[]> get(final UniqueIdType type,
                                          final byte[] key,
                                          final byte[] family);

  /**
   * Storage needs to implement this atomic increment. For HBase and Bigtable
   * it was straight foward and other stores should have similar calls.
   * @param key The non-null key.
   * @param family The non-null column family.
   * @param qualifier The non-null qualifier.
   * @return A deferred to wait on resolving to the incremented value.
   */
  protected abstract Deferred<Long> increment(final byte[] key,
                                              final byte[] family,
                                              final byte[] qualifier);

  /**
   * Storage has to implement the compare and swap call.
   * @param key The non-null key.
   * @param family The non-null column family.
   * @param qualifier The non-null qualifier.
   * @param value The non-null value we want to set.
   * @param expected The non-null expected value.
   * @return A deferred resolving to true if the call succeeded and the new value
   * was set or false if the value was not set.
   */
  protected abstract Deferred<Boolean> cas(final byte[] key,
                                           final byte[] family,
                                           byte[] qualifier,
                                           byte[] value,
                                           byte[] expected);
//
//  /**
//   * Attempts to run the PutRequest given in argument, retrying if needed.
//   *
//   * Puts are synchronized.
//   *
//   * @param put The PutRequest to execute.
//   * @param attempts The maximum number of attempts.
//   * @param wait The initial amount of time in ms to sleep for after a
//   * failure.  This amount is doubled after each failed attempt.
//   * @throws HBaseException if all the attempts have failed.  This exception
//   * will be the exception of the last attempt.
//   */
//  private void hbasePutWithRetry(final PutRequest put, short attempts, short wait)
//    throws HBaseException {
//    put.setBufferable(false);  // TODO(tsuna): Remove once this code is async.
//    while (attempts-- > 0) {
//      try {
//        client.put(put).joinUninterruptibly();
//        return;
//      } catch (HBaseException e) {
//        if (attempts > 0) {
//          LOG.error("Put failed, attempts left=" + attempts
//                    + " (retrying in " + wait + " ms), put=" + put, e);
//          try {
//            Thread.sleep(wait);
//          } catch (InterruptedException ie) {
//            throw new RuntimeException("interrupted", ie);
//          }
//          wait *= 2;
//        } else {
//          throw e;
//        }
//      } catch (Exception e) {
//        LOG.error("WTF?  Unexpected exception type, put=" + put, e);
//      }
//    }
//    throw new IllegalStateException("This code should never be reached!");
//  }
//
//  private static byte[] toBytes(final String s) {
//    return s.getBytes(CHARSET);
//  }
//
//  private static String fromBytes(final byte[] b) {
//    return new String(b, CHARSET);
//  }
//
//  /** Returns a human readable string representation of the object. */
//  public String toString() {
//    return "UniqueId(" + fromBytes(table) + ", " + kind() + ", " + id_width + ")";
//  }
//
//  /**
//   * Extracts the TSUID from a storage row key that includes the timestamp.
//   * @param row_key The row key to process
//   * @param metric_width The width of the metric
//   * @param timestamp_width The width of the timestamp
//   * @return The TSUID as a byte array
//   * @throws IllegalArgumentException if the row key is missing tags or it is
//   * corrupt such as a salted key when salting is disabled or vice versa.
//   */
//  public static byte[] getTSUIDFromKey(final byte[] row_key, 
//      final short metric_width, final short timestamp_width) {
//    int idx = 0;
//    // validation
//    final int tag_pair_width = TSDB.tagk_width() + TSDB.tagv_width();
//    final int tags_length = row_key.length - 
//        (Const.SALT_WIDTH() + metric_width + timestamp_width);
//    if (tags_length < tag_pair_width || (tags_length % tag_pair_width) != 0) {
//      throw new IllegalArgumentException(
//          "Row key is missing tags or it is corrupted " + Arrays.toString(row_key));
//    }
//    final byte[] tsuid = new byte[
//                 row_key.length - timestamp_width - Const.SALT_WIDTH()];
//    for (int i = Const.SALT_WIDTH(); i < row_key.length; i++) {
//      if (i < Const.SALT_WIDTH() + metric_width || 
//          i >= (Const.SALT_WIDTH() + metric_width + timestamp_width)) {
//        tsuid[idx] = row_key[i];
//        idx++;
//      }
//    }
//    return tsuid;
//  }
//  
//  /**
//   * Extracts a list of tagks and tagvs as individual values in a list
//   * @param tsuid The tsuid to parse
//   * @return A list of tagk/tagv UIDs alternating with tagk, tagv, tagk, tagv
//   * @throws IllegalArgumentException if the TSUID is malformed
//   * @since 2.1
//   */
//  public static List<byte[]> getTagsFromTSUID(final String tsuid) {
//    if (tsuid == null || tsuid.isEmpty()) {
//      throw new IllegalArgumentException("Missing TSUID");
//    }
//    if (tsuid.length() <= TSDB.metrics_width() * 2) {
//      throw new IllegalArgumentException(
//          "TSUID is too short, may be missing tags");
//    }
//     
//    final List<byte[]> tags = new ArrayList<byte[]>();
//    final int pair_width = (TSDB.tagk_width() * 2) + (TSDB.tagv_width() * 2);
//    
//    // start after the metric then iterate over each tagk/tagv pair
//    for (int i = TSDB.metrics_width() * 2; i < tsuid.length(); i+= pair_width) {
//      if (i + pair_width > tsuid.length()){
//        throw new IllegalArgumentException(
//            "The TSUID appears to be malformed, improper tag width");
//      }
//      String tag = tsuid.substring(i, i + (TSDB.tagk_width() * 2));
//      tags.add(UniqueId.stringToUid(tag));
//      tag = tsuid.substring(i + (TSDB.tagk_width() * 2), i + pair_width);
//      tags.add(UniqueId.stringToUid(tag));
//    }
//    return tags;
//  }
//   
//  /**
//   * Extracts a list of tagk/tagv pairs from a tsuid
//   * @param tsuid The tsuid to parse
//   * @return A list of tagk/tagv UID pairs
//   * @throws IllegalArgumentException if the TSUID is malformed
//   * @since 2.0
//   */
//  public static List<byte[]> getTagPairsFromTSUID(final String tsuid) {
//     if (tsuid == null || tsuid.isEmpty()) {
//       throw new IllegalArgumentException("Missing TSUID");
//     }
//     if (tsuid.length() <= TSDB.metrics_width() * 2) {
//       throw new IllegalArgumentException(
//           "TSUID is too short, may be missing tags");
//     }
//      
//     final List<byte[]> tags = new ArrayList<byte[]>();
//     final int pair_width = (TSDB.tagk_width() * 2) + (TSDB.tagv_width() * 2);
//     
//     // start after the metric then iterate over each tagk/tagv pair
//     for (int i = TSDB.metrics_width() * 2; i < tsuid.length(); i+= pair_width) {
//       if (i + pair_width > tsuid.length()){
//         throw new IllegalArgumentException(
//             "The TSUID appears to be malformed, improper tag width");
//       }
//       String tag = tsuid.substring(i, i + pair_width);
//       tags.add(UniqueId.stringToUid(tag));
//     }
//     return tags;
//   }
//  
//  /**
//   * Extracts a list of tagk/tagv pairs from a tsuid
//   * @param tsuid The tsuid to parse
//   * @return A list of tagk/tagv UID pairs
//   * @throws IllegalArgumentException if the TSUID is malformed
//   * @since 2.0
//   */
//  public static List<byte[]> getTagPairsFromTSUID(final byte[] tsuid) {
//    if (tsuid == null) {
//      throw new IllegalArgumentException("Missing TSUID");
//    }
//    if (tsuid.length <= TSDB.metrics_width()) {
//      throw new IllegalArgumentException(
//          "TSUID is too short, may be missing tags");
//    }
//     
//    final List<byte[]> tags = new ArrayList<byte[]>();
//    final int pair_width = TSDB.tagk_width() + TSDB.tagv_width();
//    
//    // start after the metric then iterate over each tagk/tagv pair
//    for (int i = TSDB.metrics_width(); i < tsuid.length; i+= pair_width) {
//      if (i + pair_width > tsuid.length){
//        throw new IllegalArgumentException(
//            "The TSUID appears to be malformed, improper tag width");
//      }
//      tags.add(Arrays.copyOfRange(tsuid, i, i + pair_width));
//    }
//    return tags;
//  }
//  
//  /**
//   * Returns a map of max UIDs from storage for the given list of UID types 
//   * @param tsdb The TSDB to which we belong
//   * @param kinds A list of qualifiers to fetch
//   * @return A map with the "kind" as the key and the maximum assigned UID as
//   * the value
//   * @since 2.0
//   */
//  public static Deferred<Map<String, Long>> getUsedUIDs(final TSDB tsdb,
//      final byte[][] kinds) {
//    
//    /**
//     * Returns a map with 0 if the max ID row hasn't been initialized yet, 
//     * otherwise the map has actual data
//     */
//    final class GetCB implements Callback<Map<String, Long>, 
//      ArrayList<KeyValue>> {
//
//      @Override
//      public Map<String, Long> call(final ArrayList<KeyValue> row)
//          throws Exception {
//        
//        final Map<String, Long> results = new HashMap<String, Long>(3);
//        if (row == null || row.isEmpty()) {
//          // it could be the case that this is the first time the TSD has run
//          // and the user hasn't put any metrics in, so log and return 0s
//          LOG.info("Could not find the UID assignment row");
//          for (final byte[] kind : kinds) {
//            results.put(new String(kind, CHARSET), 0L);
//          }
//          return results;
//        }
//        
//        for (final KeyValue column : row) {
//          results.put(new String(column.qualifier(), CHARSET), 
//              Bytes.getLong(column.value()));
//        }
//        
//        // if the user is starting with a fresh UID table, we need to account
//        // for missing columns
//        for (final byte[] kind : kinds) {
//          if (results.get(new String(kind, CHARSET)) == null) {
//            results.put(new String(kind, CHARSET), 0L);
//          }
//        }
//        return results;
//      }
//      
//    }
//    
//    final GetRequest get = new GetRequest(tsdb.uidTable(), MAXID_ROW);
//    get.family(ID_FAMILY);
//    get.qualifiers(kinds);
//    return tsdb.getClient().get(get).addCallback(new GetCB());
//  }
//
//  /**
//   * Pre-load UID caches, scanning up to "tsd.core.preload_uid_cache.max_entries"
//   * rows from the UID table.
//   * @param tsdb The TSDB to use 
//   * @param uid_cache_map A map of {@link UniqueId} objects keyed on the kind.
//   * @throws HBaseException Passes any HBaseException from HBase scanner.
//   * @throws RuntimeException Wraps any non HBaseException from HBase scanner.
//   * @2.1
//   */
//  public static void preloadUidCache(final TSDB tsdb,
//      final ByteMap<UniqueId> uid_cache_map) throws HBaseException {
//    int max_results = tsdb.getConfig().getInt(
//        "tsd.core.preload_uid_cache.max_entries");
//    LOG.info("Preloading uid cache with max_results=" + max_results);
//    if (max_results <= 0) {
//      return;
//    }
//    Scanner scanner = null;
//    try {
//      int num_rows = 0;
//      scanner = getSuggestScanner(tsdb.getClient(), tsdb.uidTable(), "", null, 
//          max_results);
//      for (ArrayList<ArrayList<KeyValue>> rows = scanner.nextRows().join();
//          rows != null;
//          rows = scanner.nextRows().join()) {
//        for (final ArrayList<KeyValue> row : rows) {
//          for (KeyValue kv: row) {
//            final String name = fromBytes(kv.key());
//            final byte[] kind = kv.qualifier();
//            final byte[] id = kv.value();
//            LOG.debug("id='{}', name='{}', kind='{}'", Arrays.toString(id),
//                name, fromBytes(kind));
//            UniqueId uid_cache = uid_cache_map.get(kind);
//            if (uid_cache != null) {
//              uid_cache.cacheMapping(name, id);
//            }
//          }
//          num_rows += row.size();
//          row.clear();  // free()
//          if (num_rows >= max_results) {
//            break;
//          }
//        }
//      }
//      for (UniqueId unique_id_table : uid_cache_map.values()) {
//        LOG.info("After preloading, uid cache '{}' has {} ids and {} names.",
//                 unique_id_table.kind(),
//                 unique_id_table.id_cache.size(),
//                 unique_id_table.name_cache.size());
//      }
//    } catch (Exception e) {
//      if (e instanceof HBaseException) {
//        throw (HBaseException)e;
//      } else if (e instanceof RuntimeException) {
//        throw (RuntimeException)e;
//      } else {
//        throw new RuntimeException("Error while preloading IDs", e);
//      }
//    } finally {
//      if (scanner != null) {
//        scanner.close();
//      }
//    }
//  }

  @Override
  public Charset characterSet(final UniqueIdType type) {
    switch (type) {
    case METRIC:
      return metric_character_set;
    case TAGK:
      return tagk_character_set;
    case TAGV:
      return tagv_character_set;
    default:
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }

  @VisibleForTesting
  public Map<UniqueIdType, Map<String, Deferred<IdOrError>>> pending() {
    return Collections.unmodifiableMap(pending_assignments);
  }

  protected void registerConfigs(final TSDB tsdb) {
    for (final Entry<UniqueIdType, String> entry : CONFIG_PREFIX.entrySet()) {
      if (!tsdb.getConfig().hasProperty(
          configKey(entry.getValue() + CHARACTER_SET_KEY))) {
        tsdb.getConfig().register(
            configKey(entry.getValue() + CHARACTER_SET_KEY), 
            CHARACTER_SET_DEFAULT, 
            false, 
            "The character set used for encoding/decoding UID "
            + "strings for " + entry.getKey().toString().toLowerCase() 
            + " entries.");
      }
      
      if (!tsdb.getConfig().hasProperty(
          configKey(entry.getValue() + RANDOM_ASSIGNMENT_KEY))) {
        tsdb.getConfig().register(
            configKey(entry.getValue() + RANDOM_ASSIGNMENT_KEY), 
            false, 
            false, 
            "Whether or not to randomly assign UIDs for " 
            + entry.getKey().toString().toLowerCase() + " entries "
                + "instead of incrementing a counter in storage.");
      }
    }
    
    if (!tsdb.getConfig().hasProperty(
        configKey(ASSIGN_AND_RETRY_KEY))) {
      tsdb.getConfig().register(
          configKey(ASSIGN_AND_RETRY_KEY), 
          false, 
          false, 
          "Whether or not to return with a RETRY write state immediately "
          + "when a UID needs to be assigned and being the assignment "
          + "process in the background. The next time the same string "
          + "is looked up it should be assigned.");
    }

    if (!tsdb.getConfig().hasProperty(
            configKey(LOG_ASSIGNMENT_KEY))) {
      tsdb.getConfig().register(
              configKey(LOG_ASSIGNMENT_KEY),
              false,
              true,
              "Whether or not to log UID assignments at the INFO level.");
    }
    
    if (!tsdb.getConfig().hasProperty(
        configKey(RANDOM_ATTEMPTS_KEY))) {
      tsdb.getConfig().register(
          configKey(RANDOM_ATTEMPTS_KEY), 
          DEFAULT_ATTEMPTS_ASSIGN_RANDOM_ID, 
          false, 
          "The maximum number of attempts to make before giving up on "
          + "a UID assignment and return a RETRY error when in the "
          + "random ID mode. (This is usually higher than the normal "
          + "mode as random IDs can collide more often.)");
    }
    
    if (!tsdb.getConfig().hasProperty(
        configKey(ATTEMPTS_KEY))) {
      tsdb.getConfig().register(
          configKey(ATTEMPTS_KEY), 
          DEFAULT_ATTEMPTS_ASSIGN_ID, 
          false, 
          "The maximum number of attempts to make before giving up on "
          + "a UID assignment and return a RETRY error.");
    }
  }

  public String configKey(final String suffix) {
    return "tsd.storage." + (Strings.isNullOrEmpty(id) ? "" : id + ".")
            + suffix;
  }
}
