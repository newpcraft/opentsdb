// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map.Entry;

import net.opentsdb.data.TypedTimeSeriesIterator;
import org.junit.Before;
import org.junit.Test;

import net.opentsdb.configuration.Configuration;
import net.opentsdb.configuration.UnitTestConfiguration;
import net.opentsdb.core.DefaultRegistry;
import net.opentsdb.core.DefaultTSDB;
import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesDatum;
import net.opentsdb.data.TimeSeriesDatumStringId;
import net.opentsdb.data.BaseTimeSeriesDatumStringId;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.storage.MockDataStore.MockRow;
import net.opentsdb.storage.MockDataStore.MockSpan;

public class TestMockDataStore {

  private DefaultTSDB tsdb;
  private Configuration config;
  private DefaultRegistry registry;
  private MockDataStore mds;
  
  @Before
  public void before() throws Exception {
    tsdb = mock(DefaultTSDB.class);
    config = UnitTestConfiguration.getConfiguration();
    registry = mock(DefaultRegistry.class);
    when(tsdb.getConfig()).thenReturn(config);
    when(tsdb.getRegistry()).thenReturn(registry);
    
    config.register("MockDataStore.timestamp", 1483228800000L, false, "UT");
    config.register("MockDataStore.threadpool.enable", true, false, "UT");
    config.register("MockDataStore.sysout.enable", true, false, "UT");
    
    mds = new MockDataStore(tsdb, "Mock");
  }
  
  @Test
  public void initialize() throws Exception {
    assertEquals(4 * 4 * 4, mds.getDatabase().size());
    for (final Entry<TimeSeriesDatumStringId, MockSpan> series : 
          mds.getDatabase().entrySet()) {
      assertEquals(24, series.getValue().rows().size());
      
      long ts = 1483228800000L;
      for (MockRow row : series.getValue().rows()) {
        assertEquals(ts, row.baseTimestamp());

        TypedTimeSeriesIterator<? extends TimeSeriesDataType> it = row.iterator(NumericType.TYPE).get();
        int count = 0;
        while (it.hasNext()) {
          assertEquals(ts + (count * 60000), it.next().timestamp().msEpoch());
          count++;
        }
        ts += MockDataStore.ROW_WIDTH;
        assertEquals(60, count);
      }
      assertEquals(1483315200000L, ts);
    }
  }
  
  @Test
  public void write() throws Exception {
    assertEquals(4 * 4 * 4, mds.getDatabase().size());
    
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric("unit.test")
        .addTags("dc", "lga")
        .addTags("host", "db01")
        .build();
    MutableNumericValue dp = new MutableNumericValue();
    TimeStamp ts = new MillisecondTimeStamp(1483228800000L);
    dp.reset(ts, 42.5);
    mds.write(null, new TimeSeriesDatum() {
      
        @Override
        public TimeSeriesDatumStringId id() {
          return id;
        }
  
        @Override
        public TimeSeriesValue<? extends TimeSeriesDataType> value() {
          return dp;
        }
        
      }, null);
    assertEquals((4 * 4 * 4) + 1, mds.getDatabase().size());
    
    ts.updateMsEpoch(1483228800000L + 60000L);
    dp.reset(ts, 24.5);
    mds.write(null, new TimeSeriesDatum() {
        
        @Override
        public TimeSeriesDatumStringId id() {
          return id;
        }
  
        @Override
        public TimeSeriesValue<? extends TimeSeriesDataType> value() {
          return dp;
        }
        
      }, null);
    
    // no out-of-order timestamps per series for now. at least within a "row".
    ts.updateMsEpoch(1483228800000L + 30000L);
    dp.reset(ts, -1);
    try {
      mds.write(null, new TimeSeriesDatum() {
          
          @Override
          public TimeSeriesDatumStringId id() {
            return id;
          }
  
          @Override
          public TimeSeriesValue<? extends TimeSeriesDataType> value() {
            return dp;
          }
          
        }, null);
      //fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
}
