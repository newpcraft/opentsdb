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
package net.opentsdb.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.opentsdb.core.BaseTSDBPlugin;
import net.opentsdb.core.TSDB;
import net.opentsdb.grpc.QueryGRPCSink.GRPCSinkConfig;
import net.opentsdb.query.QueryContext;
import net.opentsdb.query.QuerySink;
import net.opentsdb.query.QuerySinkConfig;
import net.opentsdb.query.QuerySinkFactory;

/**
 * Factory for spitting out GRPC sinks.
 * 
 * @since 3.0
 */
public class QueryGRPCSinkFactory extends BaseTSDBPlugin 
    implements QuerySinkFactory {

  public static final String TYPE = "TSDBGRPCSink";
  
  @Override
  public QuerySink newSink(final QueryContext context, 
                           final QuerySinkConfig config) {
    return new QueryGRPCSink(context, config);
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public String version() {
    return "3.0.0";
  }
  
  @Override
  public QuerySinkConfig parseConfig(final ObjectMapper mapper, 
                                     final TSDB tsdb,
                                     final JsonNode node) {
    try {
      return mapper.treeToValue(node, GRPCSinkConfig.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to parse JSON", e);
    }
  }

}
