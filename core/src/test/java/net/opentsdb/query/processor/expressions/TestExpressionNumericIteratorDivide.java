//This file is part of OpenTSDB.
//Copyright (C) 2018-2021  The OpenTSDB Authors.
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
package net.opentsdb.query.processor.expressions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import net.opentsdb.query.DefaultQueryResultId;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.types.numeric.NumericMillisecondShard;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.query.QueryFillPolicy.FillWithRealPolicy;
import net.opentsdb.query.interpolation.types.numeric.NumericInterpolatorConfig;
import net.opentsdb.query.pojo.FillPolicy;
import net.opentsdb.query.processor.expressions.ExpressionParseNode.ExpressionOp;
import net.opentsdb.query.processor.expressions.ExpressionParseNode.OperandType;
import net.opentsdb.query.processor.expressions.ExpressionParser.NumericLiteral;

public class TestExpressionNumericIteratorDivide extends BaseNumericTest {

  private TimeSeries left;
  private TimeSeries right;
  
  @Before
  public void beforeLocal() {
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
  }
  
  @Test
  public void longLong() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1);
    ((NumericMillisecondShard) left).add(3000, 4);
    ((NumericMillisecondShard) left).add(5000, 2);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 2); // integer!
    ((NumericMillisecondShard) right).add(5000, 8);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(.25, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(2, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(.25, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());

    // divide w/ same operand
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
            .setLeft("a")
            .setLeftType(OperandType.VARIABLE)
            .setLeftId(new DefaultQueryResultId("m1", "m1"))
            .setRight("a")
            .setRightType(OperandType.VARIABLE)
            .setRightId(new DefaultQueryResultId("m1", "m1"))
            .setExpressionOp(ExpressionOp.DIVIDE)
            .setExpressionConfig(config)
            .setId("expression")
            .build();
    when(node.config()).thenReturn(expression_config);

    iterator = new ExpressionNumericIterator(node, RESULT,
            (Map) ImmutableMap.builder()
                    .put(ExpressionTimeSeries.LEFT_KEY, left)
                    .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(1.0, value.value().doubleValue(), 0.001);

    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(1.0, value.value().doubleValue(), 0.001);

    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(1.0, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void longLongNegate() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1);
    ((NumericMillisecondShard) left).add(3000, 4);
    ((NumericMillisecondShard) left).add(5000, 2);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 2); // integer!
    ((NumericMillisecondShard) right).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setNegate(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(-.25, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(-2, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(-.25, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void longDouble() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1);
    ((NumericMillisecondShard) left).add(3000, 5);
    ((NumericMillisecondShard) left).add(5000, 2);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(.2222, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0.465116279069767, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.224719101123596, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }

  @Test
  public void longDoubleNegate() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1);
    ((NumericMillisecondShard) left).add(3000, 5);
    ((NumericMillisecondShard) left).add(5000, 2);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setNegate(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(-.2222, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(-0.465116279069767, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(-0.224719101123596, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void doubleDouble() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1.1);
    ((NumericMillisecondShard) left).add(3000, 5.33);
    ((NumericMillisecondShard) left).add(5000, 2.66);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0.244444444444444, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0.495813953488372, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.298876404494382, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaNNonInfectious() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1.1);
    //((NumericMillisecondShard) left).add(3000, 5.33);
    ((NumericMillisecondShard) left).add(5000, 2.66);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0.244444444444444, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.298876404494382, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaNInfectious() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1.1);
    //((NumericMillisecondShard) left).add(3000, 5.33);
    ((NumericMillisecondShard) left).add(5000, 2.66);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a / b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setInfectiousNan(true)
        .setId("e1")
        .build();
    when(node.expressionConfig()).thenReturn(cfg);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0.244444444444444, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.298876404494382, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNull() throws Exception {
    NumericInterpolatorConfig numeric_config = 
        (NumericInterpolatorConfig) NumericInterpolatorConfig.newBuilder()
      .setFillPolicy(FillPolicy.NONE)
      .setRealFillPolicy(FillWithRealPolicy.NONE)
      .setDataType(NumericType.TYPE.toString())
      .build();
    
    ExpressionConfig exp_config = (ExpressionConfig) ExpressionConfig.newBuilder()
      .setExpression("a + b + c")
      .setJoinConfig(JOIN_CONFIG)
      .addInterpolatorConfig(numeric_config)
      .setId("e1")
      .build();
    when(node.expressionConfig()).thenReturn(exp_config);
    
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 1.1);
    //((NumericMillisecondShard) left).add(3000, 5.33);
    ((NumericMillisecondShard) left).add(5000, 2.66);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4.5);
    ((NumericMillisecondShard) right).add(3000, 10.75);
    ((NumericMillisecondShard) right).add(5000, 8.9);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0.244444444444444, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.298876404494382, value.value().doubleValue(), 0.001);
    assertFalse(iterator.hasNext());
  }

  @Test
  public void numericLiteralLeft() throws Exception {
    NumericLiteral literal = mock(NumericLiteral.class);
    when(literal.isInteger()).thenReturn(true);
    when(literal.longValue()).thenReturn(42L);
    
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 10);
    ((NumericMillisecondShard) right).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(literal)
        .setLeftType(OperandType.LITERAL_NUMERIC)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(10.5, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(4.2, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(5.25, value.value().doubleValue(), 0.001);
  }
  
  @Test
  public void numericLiteralRight() throws Exception {
    NumericLiteral literal = mock(NumericLiteral.class);
    when(literal.isInteger()).thenReturn(true);
    when(literal.longValue()).thenReturn(42L);
    
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 4);
    ((NumericMillisecondShard) left).add(3000, 10);
    ((NumericMillisecondShard) left).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(literal)
        .setRightType(OperandType.LITERAL_NUMERIC)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0.095238095238095, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0.238095238095238, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0.19047619047619, value.value().doubleValue(), 0.001);
    
    // divide by zero integer
    when(literal.longValue()).thenReturn(0L);
    
    iterator = new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    // divide by zero float
    when(literal.isInteger()).thenReturn(false);
    when(literal.doubleValue()).thenReturn(0.0);
    
    iterator = new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
  }

  @Test
  public void booleanLeft() throws Exception {
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 10);
    ((NumericMillisecondShard) right).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(true)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(.25, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(.1, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(.125, value.value().doubleValue(), 0.001);
    
    // with false == 0
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(false)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
  }
  
  @Test
  public void booleanRight() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 4);
    ((NumericMillisecondShard) left).add(3000, 10);
    ((NumericMillisecondShard) left).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(true)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(4, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(10, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(8, value.value().doubleValue(), 0.001);
    
    // with false == 0
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(false)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
  }

  @Test
  public void nullLeftInfectious() throws Exception {
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 10);
    ((NumericMillisecondShard) right).add(5000, 8);
    
    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a / b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setInfectiousNan(true)
        .setId("e1")
        .build();
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    when(node.expressionConfig()).thenReturn(cfg);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
  }
  
  @Test
  public void nullLeft() throws Exception {
    right = new NumericMillisecondShard(RIGHT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) right).add(1000, 4);
    ((NumericMillisecondShard) right).add(3000, 10);
    ((NumericMillisecondShard) right).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
  }
  
  @Test
  public void nullRightInfectious() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 4);
    ((NumericMillisecondShard) left).add(3000, 10);
    ((NumericMillisecondShard) left).add(5000, 8);

    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a / b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setInfectiousNan(true)
        .setId("e1")
        .build();
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    when(node.expressionConfig()).thenReturn(cfg);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertTrue(Double.isNaN(value.value().doubleValue()));
  }
  
  @Test
  public void nullRight() throws Exception {
    left = new NumericMillisecondShard(LEFT_ID, 
        new MillisecondTimeStamp(1000), new MillisecondTimeStamp(7000));
    ((NumericMillisecondShard) left).add(1000, 4);
    ((NumericMillisecondShard) left).add(3000, 10);
    ((NumericMillisecondShard) left).add(5000, 8);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.DIVIDE)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericIterator iterator = 
        new ExpressionNumericIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericType> value = 
        (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(1000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(3000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
    
    value = (TimeSeriesValue<NumericType>) iterator.next();
    assertEquals(5000, value.timestamp().msEpoch());
    assertEquals(0, value.value().doubleValue(), 0.001);
  }
  
}
