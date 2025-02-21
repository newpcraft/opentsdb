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
package net.opentsdb.query.interpolation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import net.opentsdb.core.BaseTSDBPlugin;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;

/**
 * The base factory for interpolators. It stores the interpolators by
 * data type in a map with values that are pairs of constructors, the
 * first being the {@link TimeSeries} source ctor and the second
 * being the {@link Iterator} source.
 * 
 * @since 3.0
 */
public abstract class BaseQueryIntperolatorFactory extends BaseTSDBPlugin 
    implements QueryInterpolatorFactory {
  private static final Logger LOG = LoggerFactory.getLogger(
      BaseQueryIntperolatorFactory.class);
  
  /** The map of types to <TimeSeries, Iterator> constructors. */
  protected Map<TypeToken<?>, Pair<Constructor<?>, Constructor<?>>> types
     = Maps.newHashMap();
  
  protected Map<TypeToken<?>, QueryInterpolatorConfigParser> parsers = 
      Maps.newHashMap();
  
  @Override
  public QueryInterpolator<? extends TimeSeriesDataType> newInterpolator(
      final TypeToken<? extends TimeSeriesDataType> type,
      final TimeSeries source, 
      final QueryInterpolatorConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null.");
    }
    final Pair<Constructor<?>, Constructor<?>> ctors = types.get(type);
    if (ctors == null) {
      return null;
    }
    try {
      return (QueryInterpolator<? extends TimeSeriesDataType>) 
          ctors.getKey().newInstance(source, config);
      // TODO - can I get the class out for exceptions?
    } catch (InstantiationException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getKey(), e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access constructor for " 
          + ctors.getKey(), e);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getKey(), e);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getKey(), e);
    }
  }
  
  @Override
  public QueryInterpolator<? extends TimeSeriesDataType> newInterpolator(
      final TypeToken<? extends TimeSeriesDataType> type,
      final TypedTimeSeriesIterator<? extends TimeSeriesDataType> iterator,
      final QueryInterpolatorConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null.");
    }
    final Pair<Constructor<?>, Constructor<?>> ctors = types.get(type);
    if (ctors == null) {
      return null;
    }
    try {
      return (QueryInterpolator<? extends TimeSeriesDataType>) 
          ctors.getValue().newInstance(iterator, config);
      // TODO - can I get the class out for exceptions?
    } catch (InstantiationException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getValue(), e);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access constructor for " 
          + ctors.getValue(), e);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getValue(), e);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Registered class failed to "
          + "instantiate for " + ctors.getValue(), e);
    }
  }
  
  @Override
  public void register(final TypeToken<? extends TimeSeriesDataType> type,
                       final Class<? extends QueryInterpolator<?>> clazz,
                       final QueryInterpolatorConfigParser parser) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (clazz == null) {
      throw new IllegalArgumentException("Class cannot be null.");
    }
    if (parser == null) {
      throw new IllegalArgumentException("Parser cannot be null.");
    }
    final Pair<Constructor<?>, Constructor<?>> ctors = types.get(type);
    if (ctors != null) {
      LOG.warn("Replacing an existing interpolator builder for type: " 
          + type + " in " + id() + " Class: " + ctors.getKey().getName());
    }
    
    try {
      final Constructor<?> ts_ctor = clazz.getDeclaredConstructor(
          TimeSeries.class, QueryInterpolatorConfig.class);
      final Constructor<?> iterator_ctor = clazz.getDeclaredConstructor(
          TypedTimeSeriesIterator.class, QueryInterpolatorConfig.class);
      types.put(type, new Pair(
          ts_ctor, iterator_ctor));
      LOG.info("Stored interpolator builder for type " + type + " in " 
          + id() + " Class: " + clazz);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Unable to find the required "
          + "constructors for the class " + clazz + " and data type: " 
          + type, e);
    } catch (SecurityException e) {
       throw new RuntimeException("Unable to extract constructors for "
           + "class " + clazz + " and data type: " + type, e); 
    }
    
    // if we made it this far, register the parser Replacement is ok.
    parsers.put(type, parser);
  }

  @Override
  public QueryInterpolatorConfig parseConfig(final ObjectMapper mapper, 
                                             final TSDB tsdb,
                                             final JsonNode node) {
    final JsonNode data_type_node = node.get("dataType");
    if (data_type_node == null) {
      throw new IllegalArgumentException("Missing dataType");
    }
    final String data_type = data_type_node.asText();
    if (Strings.isNullOrEmpty(data_type)) {
      throw new IllegalArgumentException("DataType cannot be null or empty.");
    }
    
    final TypeToken<?> type = tsdb.getRegistry().getType(data_type);
    if (type == null) {
      throw new IllegalArgumentException("No data type registered for " 
          + data_type);
    }
    
    final QueryInterpolatorConfigParser parser = parsers.get(type);
    if (parser == null) {
      throw new IllegalArgumentException("No interpolation parser registered for " 
          + data_type);
    }
    
    return parser.parse(mapper, tsdb, node);
  }

}
