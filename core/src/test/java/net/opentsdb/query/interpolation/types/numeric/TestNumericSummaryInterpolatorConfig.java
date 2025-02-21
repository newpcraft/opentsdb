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
package net.opentsdb.query.interpolation.types.numeric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import net.opentsdb.data.types.numeric.BaseNumericFillPolicy;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.data.types.numeric.aggregators.SumFactory;
import net.opentsdb.query.QueryFillPolicy;
import net.opentsdb.query.QueryFillPolicy.FillWithRealPolicy;
import net.opentsdb.query.interpolation.types.numeric.NumericInterpolatorConfig;
import net.opentsdb.query.pojo.FillPolicy;
import net.opentsdb.utils.JSON;

public class TestNumericSummaryInterpolatorConfig {
  
  @Test
  public void build() throws Exception {
    NumericSummaryInterpolatorConfig config = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
      .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
      .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
      .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
          .put(0, FillPolicy.ZERO)
          .build())
      .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
          .put(1, FillWithRealPolicy.PREFER_NEXT)
          .build())
      .setExpectedSummaries(Lists.newArrayList(0, 1))
      .setSync(true)
      .setComponentAggregator(new SumFactory().newAggregator(false))
      .setType("myId")
      .setDataType(NumericSummaryType.TYPE.toString())
      .build();
    assertEquals(FillPolicy.NOT_A_NUMBER, config.getDefaultFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.getDefaultRealFillPolicy());
    assertEquals(1, config.summary_fill_policy_overrides.size());
    assertEquals(FillPolicy.ZERO, config.summary_fill_policy_overrides.get(0));
    assertEquals(1, config.summary_real_fill_overrides.size());
    assertEquals(FillWithRealPolicy.PREFER_NEXT, config.summary_real_fill_overrides.get(1));
    assertEquals(2, config.getExpectedSummaries().size());
    assertTrue(config.getExpectedSummaries().contains(0));
    assertTrue(config.getExpectedSummaries().contains(1));
    assertTrue(config.sync());
    assertSame(new SumFactory().newAggregator(false), config.componentAggregator());
    assertEquals("myId", config.getType());
    assertEquals(NumericSummaryType.TYPE, config.type());
    
    // adders where proper
    config = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .addFillPolicyOverride(0, FillPolicy.ZERO)
        .addRealFillPolicyOverride(1, FillWithRealPolicy.PREFER_NEXT)
        .addExpectedSummary(0)
        .addExpectedSummary(1)
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertEquals(FillPolicy.NOT_A_NUMBER, config.getDefaultFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.getDefaultRealFillPolicy());
    assertEquals(1, config.summary_fill_policy_overrides.size());
    assertEquals(FillPolicy.ZERO, config.summary_fill_policy_overrides.get(0));
    assertEquals(1, config.summary_real_fill_overrides.size());
    assertEquals(FillWithRealPolicy.PREFER_NEXT, config.summary_real_fill_overrides.get(1));
    assertEquals(2, config.getExpectedSummaries().size());
    assertTrue(config.getExpectedSummaries().contains(0));
    assertTrue(config.getExpectedSummaries().contains(1));
    assertTrue(config.sync());
    assertSame(new SumFactory().newAggregator(false), config.componentAggregator());
    assertNull(config.getType());
    assertEquals(NumericSummaryType.TYPE, config.type());
    
    // just the bare minimums.
    config = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .addExpectedSummary(0)
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertEquals(FillPolicy.NOT_A_NUMBER, config.getDefaultFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.getDefaultRealFillPolicy());
    assertNull(config.summary_fill_policy_overrides);
    assertNull(config.summary_real_fill_overrides);
    assertEquals(1, config.getExpectedSummaries().size());
    assertTrue(config.getExpectedSummaries().contains(0));
    assertFalse(config.sync());
    assertNull(config.componentAggregator());
    
    try {
      config = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
          //.setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
          .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
          .addExpectedSummary(0)
          .setDataType(NumericSummaryType.TYPE.toString())
          .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      config = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
          .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
          //.setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
          .addExpectedSummary(0)
          .setDataType(NumericSummaryType.TYPE.toString())
          .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      config = NumericSummaryInterpolatorConfig.newBuilder()
          .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
          .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
          .addExpectedSummary(0)
          .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }

  @Test
  public void fillPolicyOverrides() throws Exception {
    NumericSummaryInterpolatorConfig config = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
    .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
    .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
    .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
        .put(0, FillPolicy.ZERO)
        .build())
    .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
        .put(1, FillWithRealPolicy.PREFER_NEXT)
        .build())
    .setExpectedSummaries(Lists.newArrayList(0, 1, 2))
    .setSync(true)
    .setComponentAggregator(new SumFactory().newAggregator(false))
    .setDataType(NumericSummaryType.TYPE.toString())
    .build();
    
    assertEquals(FillPolicy.ZERO, config.fillPolicy(0));
    assertEquals(FillPolicy.NOT_A_NUMBER, config.fillPolicy(1));
    assertEquals(FillPolicy.NOT_A_NUMBER, config.fillPolicy(2));
    assertEquals(FillPolicy.NOT_A_NUMBER, config.fillPolicy(3)); // <-- NA
    
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.realFillPolicy(0));
    assertEquals(FillWithRealPolicy.PREFER_NEXT, config.realFillPolicy(1));
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.realFillPolicy(2));
    assertEquals(FillWithRealPolicy.NEXT_ONLY, config.realFillPolicy(3));
  }
  
  @Test
  public void queryFill() throws Exception {
    NumericSummaryInterpolatorConfig config = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
    .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
    .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
    .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
        .put(0, FillPolicy.ZERO)
        .build())
    .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
        .put(1, FillWithRealPolicy.PREFER_NEXT)
        .build())
    .setExpectedSummaries(Lists.newArrayList(0, 1, 2))
    .setSync(true)
    .setComponentAggregator(new SumFactory().newAggregator(false))
    .setDataType(NumericSummaryType.TYPE.toString())
    .build();
    
    QueryFillPolicy<NumericType> fill = config.queryFill(0);
    assertTrue(fill instanceof BaseNumericFillPolicy);
    NumericInterpolatorConfig nic = (NumericInterpolatorConfig) (
        (BaseNumericFillPolicy) fill).config();
    assertEquals(FillPolicy.ZERO, nic.getFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, nic.getRealFillPolicy());
    
    fill = config.queryFill(1);
    assertTrue(fill instanceof BaseNumericFillPolicy);
    nic = (NumericInterpolatorConfig) ((BaseNumericFillPolicy) fill).config();
    assertEquals(FillPolicy.NOT_A_NUMBER, nic.getFillPolicy());
    assertEquals(FillWithRealPolicy.PREFER_NEXT, nic.getRealFillPolicy());
    
    fill = config.queryFill(2);
    assertTrue(fill instanceof BaseNumericFillPolicy);
    nic = (NumericInterpolatorConfig) ((BaseNumericFillPolicy) fill).config();
    assertEquals(FillPolicy.NOT_A_NUMBER, nic.getFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, nic.getRealFillPolicy());
    
    // not expected
    fill = config.queryFill(3);
    assertTrue(fill instanceof BaseNumericFillPolicy);
    nic = (NumericInterpolatorConfig) ((BaseNumericFillPolicy) fill).config();
    assertEquals(FillPolicy.NOT_A_NUMBER, nic.getFillPolicy());
    assertEquals(FillWithRealPolicy.NEXT_ONLY, nic.getRealFillPolicy());
  }

  @Test
  public void hashCodeEqualsCompareTo() throws Exception {
    final NumericSummaryInterpolatorConfig c1 = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
      .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
      .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
      .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
          .put(0, FillPolicy.ZERO)
          .build())
      .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
          .put(1, FillWithRealPolicy.PREFER_NEXT)
          .build())
      .setExpectedSummaries(Lists.newArrayList(0, 1))
      .setSync(true)
      .setComponentAggregator(new SumFactory().newAggregator(false))
      .setType("myId")
      .setDataType(NumericSummaryType.TYPE.toString())
      .build();
    
    NumericSummaryInterpolatorConfig c2 = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
      .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
      .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
      .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
          .put(0, FillPolicy.ZERO)
          .build())
      .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
          .put(1, FillWithRealPolicy.PREFER_NEXT)
          .build())
      .setExpectedSummaries(Lists.newArrayList(0, 1))
      .setSync(true)
      .setComponentAggregator(new SumFactory().newAggregator(false))
      .setType("myId")
      .setDataType(NumericSummaryType.TYPE.toString())
      .build();
    assertEquals(c1.hashCode(), c2.hashCode());
    assertEquals(c1, c2);
    assertEquals(0, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
      .setDefaultFillPolicy(FillPolicy.MAX) // <-- DIFF
      .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
      .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
          .put(0, FillPolicy.ZERO)
          .build())
      .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
          .put(1, FillWithRealPolicy.PREFER_NEXT)
          .build())
      .setExpectedSummaries(Lists.newArrayList(0, 1))
      .setSync(true)
      .setComponentAggregator(new SumFactory().newAggregator(false))
      .setType("myId")
      .setDataType(NumericSummaryType.TYPE.toString())
      .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(-1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.PREFER_NEXT) // <-- DIFF
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(-1, c1.compareTo(c2));
      
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.MAX) // <-- DIFF
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(-1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        //.setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
        //    .put(0, FillPolicy.ZERO)
        //    .build()) 
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.NONE) // <-- DIFF
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        //.setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
        //    .put(1, FillWithRealPolicy.PREFER_NEXT)
        //    .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(1, 0)) // Diff order but ok!
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertEquals(c1.hashCode(), c2.hashCode());
    assertEquals(c1, c2);
    assertEquals(0, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 2)) // <-- DIFF
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(-1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0)) // <-- DIFF
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        //.setSync(true) 
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("myId")
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(1, c1.compareTo(c2));
    
    c2 = (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
        .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
        .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
            .put(0, FillPolicy.ZERO)
            .build())
        .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
            .put(1, FillWithRealPolicy.PREFER_NEXT)
            .build())
        .setExpectedSummaries(Lists.newArrayList(0, 1))
        .setSync(true)
        .setComponentAggregator(new SumFactory().newAggregator(false))
        .setType("other") // <--DIFF
        .setDataType(NumericSummaryType.TYPE.toString())
        .build();
    assertNotEquals(c1.hashCode(), c2.hashCode());
    assertNotEquals(c1, c2);
    assertEquals(-1, c1.compareTo(c2));
  }

  @Test
  public void serialize() throws Exception {
    NumericSummaryInterpolatorConfig config = 
        (NumericSummaryInterpolatorConfig) NumericSummaryInterpolatorConfig.newBuilder()
      .setDefaultFillPolicy(FillPolicy.NOT_A_NUMBER)
      .setDefaultRealFillPolicy(FillWithRealPolicy.NEXT_ONLY)
      .setFillPolicyOverrides(ImmutableMap.<Integer, FillPolicy>builder()
          .put(0, FillPolicy.ZERO)
          .build())
      .setRealFillPolicyOverrides(ImmutableMap.<Integer, FillWithRealPolicy>builder()
          .put(1, FillWithRealPolicy.PREFER_NEXT)
          .build())
      .setExpectedSummaries(Lists.newArrayList(0, 1))
      .setSync(true)
      .setComponentAggregator(new SumFactory().newAggregator(false))
      .setType("myId")
      .setDataType(NumericSummaryType.TYPE.toString())
      .build();
    
    final String json = JSON.serializeToString(config);
    assertTrue(json.contains("\"defaultFillPolicy\":\"nan\""));
    assertTrue(json.contains("\"defaultRealFillPolicy\":\"NEXT_ONLY\""));
    assertTrue(json.contains("\"expectedSummaries\":[0,1]"));
    assertTrue(json.contains("\"fillPolicyOverrides\":{\"0\":\"zero\"}"));
    assertTrue(json.contains("\"realFillPolicyOverrides\":{\"1\":\"PREFER_NEXT\"}"));
    assertTrue(json.contains("\"type\":\"myId\""));
    assertTrue(json.contains("\"dataType\":\"net.opentsdb.data.types.numeric.NumericSummaryType\""));
  }
}
