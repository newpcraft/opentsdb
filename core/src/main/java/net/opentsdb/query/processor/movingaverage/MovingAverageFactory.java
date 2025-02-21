// This file is part of OpenTSDB.
// Copyright (C) 2019  The OpenTSDB Authors.
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
package net.opentsdb.query.processor.movingaverage;

import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.TSDB;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.types.numeric.NumericArrayType;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.query.QueryIteratorFactory;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.plan.QueryPlanner;
import net.opentsdb.query.processor.BaseQueryNodeFactory;

/**
 * A factory to generate moving average nodes.
 * 
 * @since 3.0
 */
public class MovingAverageFactory extends BaseQueryNodeFactory<MovingAverageConfig, MovingAverage> {

  public static final String TYPE = "MovingAverage";
  
  /**
   * Default plugin ctor.
   */
  public MovingAverageFactory() {
    super();
    registerIteratorFactory(NumericType.TYPE, 
        new NumericIteratorFactory());
    registerIteratorFactory(NumericSummaryType.TYPE, 
        new NumericSummaryIteratorFactory());
    registerIteratorFactory(NumericArrayType.TYPE, 
        new NumericArrayIteratorFactory());
  }
  
  @Override
  public String type() {
    return TYPE;
  }
  
  @Override
  public Deferred<Object> initialize(final TSDB tsdb, final String id) {
    this.id = Strings.isNullOrEmpty(id) ? TYPE : id;
    return Deferred.fromResult(null);
  }

  @Override
  public MovingAverage newNode(final QueryPipelineContext context) {
    return new MovingAverage(this, context, null);
  }

  @Override
  public MovingAverage newNode(final QueryPipelineContext context,
                           final MovingAverageConfig config) {
    return new MovingAverage(this, context, config);
  }
  
  @Override
  public MovingAverageConfig parseConfig(final ObjectMapper mapper,
                                     final TSDB tsdb,
                                     final JsonNode node) {
    try {
      return mapper.treeToValue(node, MovingAverageConfig.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to parse config", e);
    }
  }
  
  @Override
  public void setupGraph(final QueryPipelineContext context, 
                         final MovingAverageConfig config,
                         final QueryPlanner plan) {
    // TODO We may need some padding for queries here.
  }
  
  /**
   * The default numeric iterator factory.
   */
  protected class NumericIteratorFactory implements QueryIteratorFactory<MovingAverage, NumericType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericIterator(node, result, sources);
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericIterator(node, result, sources);
    }

    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericType.SINGLE_LIST;
    }
    
  }
  
  /**
   * The default numeric summary iterator factory.
   */
  protected class NumericSummaryIteratorFactory implements QueryIteratorFactory<MovingAverage, NumericSummaryType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericSummaryIterator(node, result, sources);
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericSummaryIterator(node, result, sources);
    }

    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericSummaryType.SINGLE_LIST;
    }
    
  }
  
  /**
   * The default numeric summary iterator factory.
   */
  protected class NumericArrayIteratorFactory implements QueryIteratorFactory<MovingAverage, NumericArrayType> {

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Collection<TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericArrayIterator(node, result, sources);
    }

    @Override
    public TypedTimeSeriesIterator newIterator(final MovingAverage node,
                                               final QueryResult result,
                                               final Map<String, TimeSeries> sources,
                                               final TypeToken<? extends TimeSeriesDataType> type) {
      return new MovingAverageNumericArrayIterator(node, result, sources);
    }

    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return NumericArrayType.SINGLE_LIST;
    }
    
  }
}
