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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.BaseTSDBPlugin;
import net.opentsdb.core.TSDB;

/**
 * A factory to generate an ExplicitTags filter node.
 * 
 * @since 3.0
 */
public class ExplicitTagsFilterFactory extends BaseTSDBPlugin 
    implements QueryFilterFactory {

  static final String TYPE = "ExplicitTags";
  
  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public QueryFilter parse(final TSDB tsdb, 
                           final ObjectMapper mapper, 
                           final JsonNode node) {
    if (node == null) {
      throw new IllegalArgumentException("Not root canot be null.");
    }
    
    final JsonNode filter = node.get("filter");
    if (filter == null) {
      throw new IllegalArgumentException("Filter cannot be null.");
    }
    final JsonNode type_node = filter.get("type");
    if (type_node == null) {
      throw new IllegalArgumentException("Filter must include a type.");
    }
    final String type = type_node.asText();
    if (Strings.isNullOrEmpty(type)) {
      throw new IllegalArgumentException("Filter type cannot be null "
          + "or empty.");
    }
    final QueryFilterFactory factory = tsdb.getRegistry()
        .getPlugin(QueryFilterFactory.class, type);
    if (factory == null) {
      throw new IllegalArgumentException("No filter factory found "
          + "for type: " + type);
    }
    
    return (QueryFilter) ExplicitTagsFilter.newBuilder()
        .setFilter(factory.parse(tsdb, mapper, filter))
        .build();
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
  public String version() {
    return "3.0.0";
  }

}
