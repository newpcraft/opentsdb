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

import java.io.IOException;
import java.util.Map;

import com.google.common.reflect.TypeToken;

import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.exceptions.QueryDownstreamException;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.interpolation.QueryInterpolator;
import net.opentsdb.query.interpolation.QueryInterpolatorConfig;
import net.opentsdb.query.interpolation.QueryInterpolatorFactory;

/**
 * An iterator handling {@link NumericType} data values.
 * 
 * @since 3.0
 */
public class ExpressionNumericIterator extends BaseExpressionNumericIterator<NumericType> {

  /** Interpolators. May be null for literals.*/
  protected QueryInterpolator<NumericType> left_interpolator;
  protected QueryInterpolator<NumericType> right_interpolator;
  
  /** The data point set and returned by the iterator. */
  protected final MutableNumericValue dp;
  
  /**
   * Package private ctor.
   * @param node The non-null node this belongs to.
   * @param result The result set this belongs to.
   * @param sources The non-null map of results to fetch data from.
   */
  @SuppressWarnings("unchecked")
  ExpressionNumericIterator(final QueryNode node, 
                            final QueryResult result,
                            final Map<String, TimeSeries> sources) {
    super(node, result, sources);
    dp = new MutableNumericValue();
    
    if (sources.get(ExpressionTimeSeries.LEFT_KEY) == null) {
      left_interpolator = null;
    } else {
      QueryInterpolatorConfig interpolator_config = 
          ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
              NumericType.TYPE, 
              (String) ((ExpressionParseNode) node.config()).getLeft());
      if (interpolator_config == null) {
        interpolator_config = 
            ((BinaryExpressionNode) node).expressionConfig()
              .interpolatorConfig(NumericType.TYPE);
      }
      
      final QueryInterpolatorFactory factory = 
          node.pipelineContext().tsdb().getRegistry().getPlugin(
              QueryInterpolatorFactory.class, 
              interpolator_config.getType());
      if (factory == null) {
        throw new IllegalArgumentException("No interpolator factory found for: " + 
            (interpolator_config.getType() == null ? "Default" : 
              interpolator_config.getType()));
      }
      
      left_interpolator = (QueryInterpolator<NumericType>) factory.newInterpolator(
          NumericType.TYPE, sources.get(ExpressionTimeSeries.LEFT_KEY), 
          interpolator_config);
      has_next = left_interpolator.hasNext();
      if (has_next) {
        next_ts.update(left_interpolator.nextReal());
      }
    }
    
    if (sources.get(ExpressionTimeSeries.RIGHT_KEY) == null) {
      right_interpolator = null;
    } else {
      QueryInterpolatorConfig interpolator_config = 
          ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
              NumericType.TYPE, 
              (String) ((ExpressionParseNode) node.config()).getRight());
      if (interpolator_config == null) {
        interpolator_config = ((BinaryExpressionNode) node).expressionConfig()
            .interpolatorConfig(NumericType.TYPE);
      }
      
      final QueryInterpolatorFactory factory = 
          node.pipelineContext().tsdb().getRegistry().getPlugin(
              QueryInterpolatorFactory.class, 
              interpolator_config.getType());
      if (factory == null) {
        throw new IllegalArgumentException("No interpolator factory found for: " + 
            interpolator_config.getType() == null ? "Default" : 
              interpolator_config.getType());
      }
      
      right_interpolator = (QueryInterpolator<NumericType>) factory.newInterpolator(
          NumericType.TYPE, sources.get(ExpressionTimeSeries.RIGHT_KEY), 
          interpolator_config);
      if (!has_next) {
        has_next = right_interpolator.hasNext();
        if (right_interpolator.hasNext()) {
          next_ts.update(right_interpolator.nextReal());
        }
      } else {
        if (right_interpolator.nextReal().compare(Op.LT, next_ts)) {
          next_ts.update(right_interpolator.nextReal());
        }
      }
    }
    
    // final sanity check
    if (left_interpolator == null && right_interpolator == null) {
      throw new IllegalStateException("Must have at least one time "
          + "series in an expression.");
    }
  }

  @Override
  public TimeSeriesValue<? extends TimeSeriesDataType> next() {
    has_next = false;
    next_next_ts.setMax();
    
    final NumericType left;
    final NumericType right;
    
    if (left_interpolator != null && right_interpolator != null) {
      left = left_interpolator.next(next_ts).value();
      right = right_interpolator.next(next_ts).value();
      
      if (left_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(left_interpolator.nextReal());
      }
      if (right_interpolator.hasNext()) {
        has_next = true;
        if (right_interpolator.nextReal().compare(Op.LT, next_next_ts)) {
          next_next_ts.update(right_interpolator.nextReal());
        }
      }
    } else if (left_interpolator == null) {
      left = left_literal;
      right = right_interpolator.next(next_ts).value();
      
      if (right_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(right_interpolator.nextReal());
      }
    } else {
      left = left_interpolator.next(next_ts).value();

      ExpressionParseNode config = node.config();
      // check if left and right have same operand
      if(config.getLeftId() != null &&
         config.getRightId() != null &&
         config.getLeftId().equals(config.getRightId())) {
        right = left;
      } else {
        right = right_literal;
      }
      
      if (left_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(left_interpolator.nextReal());
      }
    }
    
    final NumericType result;
    switch (((ExpressionParseNode) node.config()).getOperator()) {
    // logical
    case OR:
    case AND:
      result = logical(left, right);
      break;
    // relational
    case EQ:
    case NE:
    case LE:
    case GE:
    case LT:
    case GT:
      result = relation(left, right);
      break;
    // arithmetic
    case ADD:
    case SUBTRACT:
      result = additive(left, right);
      break;
    case DIVIDE:
      result = divide(left, right);
      break;
    case MULTIPLY:
      result = multiply(left, right);
      break;
    case MOD:
      result = mod(left, right);
      break;
    default:
      throw new QueryDownstreamException("Expression iterator was "
          + "told to handle the unexpected operator: " 
          + ((ExpressionParseNode) node.config()).getOperator());
    }
    
    if (result == null) {
      dp.resetNull(next_ts);
    } else {
      dp.reset(next_ts, result);
    }
    next_ts.update(next_next_ts);
    return dp;
  }
  
  @Override
  public TimeStamp timestamp() {
    return dp.timestamp();
  }

  @Override
  public NumericType value() {
    return dp;
  }

  @Override
  public TypeToken<NumericType> type() {
    return NumericType.TYPE;
  }
  
  @Override
  public void close() {
    if (left_interpolator != null) {
      try {
        left_interpolator.close();
      } catch (IOException e) {
        // don't bother logging.
        e.printStackTrace();
      }
      left_interpolator = null;
    }
    
    if (right_interpolator != null) {
      try {
        right_interpolator.close();
      } catch (IOException e) {
        // don't bother logging.
        e.printStackTrace();
      }
      right_interpolator = null;
    }
  }
  
}
