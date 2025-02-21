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
package net.opentsdb.core;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.stumbleupon.async.Deferred;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import net.opentsdb.configuration.Configuration;
import net.opentsdb.configuration.UnitTestConfiguration;
import net.opentsdb.query.QueryContext;
import net.opentsdb.stats.BlackholeStatsCollector;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.threadpools.TSDBThreadPoolExecutor;

/**
 * Class for unit testing.
 */
public class MockTSDB implements TSDB {
  public UnitTestConfiguration config;
  public Registry registry;
  public BlackholeStatsCollector stats;
  public FakeTaskTimer maint_timer;
  public FakeTaskTimer query_timer;
  public TSDBThreadPoolExecutor query_pool;
  public List<Runnable> runnables;
  public Map<Long, QueryContext> running_queries;
  private ExecutorService executor;
  
  public MockTSDB() {
    this(false);
  }
  
  public MockTSDB(final boolean run_immediately) {
    config = (UnitTestConfiguration) UnitTestConfiguration.getConfiguration();
    registry = mock(Registry.class);
    stats = new BlackholeStatsCollector();
    maint_timer = spy(new FakeTaskTimer());
    maint_timer.multi_task = true;
    query_timer = spy(new FakeTaskTimer());
    query_timer.multi_task = true;
    query_pool = mock(TSDBThreadPoolExecutor.class);
    runnables = Lists.newArrayList();
    BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
    executor = new ThreadPoolExecutor(8, 8, 1L, TimeUnit.SECONDS, workQueue);
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        runnables.add((Runnable) invocation.getArguments()[0]);
        if (run_immediately) {
          ((Runnable) invocation.getArguments()[0]).run();
        }
        return null;
      }
    }).when(query_pool).submit(any(Runnable.class), any(QueryContext.class));

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        if (run_immediately) {
          ((Runnable) invocation.getArguments()[0]).run();
        } else {
          runnables.add((Runnable) invocation.getArguments()[0]);
        }
        return null;
      }
    }).when(query_pool).submit(any(Runnable.class));
    running_queries = Maps.newConcurrentMap();
  }
  
  @Override
  public Configuration getConfig() {
    return config;
  }

  @Override
  public Registry getRegistry() {
    return registry;
  }

  @Override
  public StatsCollector getStatsCollector() {
    return stats;
  }

  @Override
  public Timer getMaintenanceTimer() {
    return maint_timer;
  }
  
  @Override
  public TSDBThreadPoolExecutor getQueryThreadPool() {
    return query_pool;
  }

  @Override
  public Timer getQueryTimer() {
    return query_timer;
  }

  @Override
  public Deferred<Object> initializeRegistry(boolean loadPlugins) {
    return Deferred.fromResult(null);
  }

  @Override
  public Deferred<Object> shutdown() {
    return Deferred.fromResult(null);
  }

  @Override
  public boolean registerRunningQuery(final long hash, 
                                      final QueryContext context) {
    return running_queries.putIfAbsent(hash, context) == null;
  }
  
  @Override
  public boolean completeRunningQuery(final long hash) {
    final QueryContext context = running_queries.remove(hash);
    if (context == null) {
      return false;
    }
    context.close();
    return true;
  }
  
  public static class FakeTaskTimer extends HashedWheelTimer {
    public boolean multi_task;
    public TimerTask newPausedTask = null;
    public TimerTask pausedTask = null;
    public Timeout timeout = null;

    @Override
    public synchronized Timeout newTimeout(final TimerTask task,
                                           final long delay,
                                           final TimeUnit unit) {
      if (pausedTask == null) {
        pausedTask = task;
      }  else if (newPausedTask == null) {
        newPausedTask = task;
      } else if (!multi_task) {
        throw new IllegalStateException("Cannot Pause Two Timer Tasks");
      }
      timeout = mock(Timeout.class);
      return timeout;
    }

    @Override
    public Set<Timeout> stop() {
      return null;
    }

    public boolean continuePausedTask() {
      if (pausedTask == null) {
        return false;
      }
      try {
        if (!multi_task && newPausedTask != null) {
          throw new IllegalStateException("Cannot be in this state");
        }
        pausedTask.run(null);  // Argument never used in this code base
        pausedTask = newPausedTask;
        newPausedTask = null;
        return true;
      } catch (Exception e) {
        throw new RuntimeException("Timer task failed: " + pausedTask, e);
      }
    }
  }

  @Override
  public ExecutorService quickWorkPool() {
    return executor;
  }

  
}
