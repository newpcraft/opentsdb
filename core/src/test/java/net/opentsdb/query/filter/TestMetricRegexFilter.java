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
package net.opentsdb.query.filter;

import static org.junit.Assert.*;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;

import net.opentsdb.query.pojo.Metric;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import net.opentsdb.core.TSDB;
import net.opentsdb.utils.JSON;

public class TestMetricRegexFilter {

  @Test
  public void parse() throws Exception {
    TSDB tsdb = mock(TSDB.class);
    MetricRegexFactory factory = new MetricRegexFactory();
    String json = "{\"type\":\"MetricRegex\",\"metric\":\"sys.*\"}";

    JsonNode node = JSON.getMapper().readTree(json);
    MetricRegexFilter filter = (MetricRegexFilter)
            factory.parse(tsdb, JSON.getMapper(), node);
    assertEquals("sys.*", filter.getMetric());

    try {
      factory.parse(tsdb, JSON.getMapper(), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }

  @Test
  public void builder() throws Exception {
    MetricRegexFilter filter = MetricRegexFilter.newBuilder()
            .setMetric("sys.*")
            .build();
    assertEquals("sys.*", filter.getMetric());

    // trim
    filter = MetricRegexFilter.newBuilder()
            .setMetric("  sys.* ")
            .build();
    assertEquals("sys.*", filter.getMetric());

    try {
      MetricRegexFilter.newBuilder()
              .setMetric(null)
              .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }

    try {
      MetricRegexFilter.newBuilder()
              .setMetric("")
              .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }

  }

  @Test
  public void serialize() throws Exception {
    MetricRegexFilter filter = MetricRegexFilter.newBuilder()
            .setMetric("sys.*")
            .build();

    final String json = JSON.serializeToString(filter);
    assertTrue(json.contains("\"type\":\"MetricRegex\""));
    assertTrue(json.contains("\"metric\":\"sys.*\""));
  }

  @Test
  public void initialize() throws Exception {
    MetricRegexFilter filter = MetricRegexFilter.newBuilder()
            .setMetric("sys.*")
            .build();
    assertNull(filter.initialize(null).join());
  }

  @Test
  public void equality() throws Exception {
    TSDB tsdb = mock(TSDB.class);
    MetricRegexFactory factory = new MetricRegexFactory();
    String json = "{\"type\":\"MetricRegex\",\"metric\":\"sys.*\"}";

    JsonNode node = JSON.getMapper().readTree(json);
    MetricRegexFilter filter = (MetricRegexFilter)
            factory.parse(tsdb, JSON.getMapper(), node);
    assertEquals("sys.*", filter.getMetric());

    MetricRegexFilter filter2 = (MetricRegexFilter)
            factory.parse(tsdb, JSON.getMapper(), node);

    String json2 = "{\"type\":\"MetricRegex\",\"metric\":\"sys.pct.*\"}";

    JsonNode node2 = JSON.getMapper().readTree(json2);
    MetricRegexFilter filter3 = (MetricRegexFilter)
            factory.parse(tsdb, JSON.getMapper(), node2);


    assertTrue(filter.equals(filter2));
    assertTrue(!filter.equals(filter3));
    assertEquals(filter.hashCode(), filter2.hashCode());
    assertNotEquals(filter.hashCode(), filter3.hashCode());


    filter = MetricRegexFilter.newBuilder()
            .setMetric("sys.*")
            .build();
    assertEquals("sys.*", filter.getMetric());

    // trim
    filter2 = MetricRegexFilter.newBuilder()
            .setMetric("  sys.* ")
            .build();

    filter3 = MetricRegexFilter.newBuilder()
            .setMetric("sys.pct.*")
            .build();

    assertTrue(filter.equals(filter2));
    assertTrue(!filter.equals(filter3));
    assertEquals(filter.hashCode(), filter2.hashCode());
    assertNotEquals(filter.hashCode(), filter3.hashCode());

  }


}
