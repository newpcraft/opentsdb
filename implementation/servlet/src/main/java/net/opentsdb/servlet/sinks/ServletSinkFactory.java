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
package net.opentsdb.servlet.sinks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.BaseTSDBPlugin;
import net.opentsdb.core.TSDB;
import net.opentsdb.query.QueryContext;
import net.opentsdb.query.QuerySink;
import net.opentsdb.query.QuerySinkConfig;
import net.opentsdb.query.QuerySinkFactory;

/**
 * A factory to generate the servlet sink.
 * 
 * @since 3.0
 */
public class ServletSinkFactory extends BaseTSDBPlugin 
    implements QuerySinkFactory {

  public static final String TYPE = "TSDBServletSink";
  public static final String TEE_ID = "tsdb.servletsink.tee.plugin";

  private ServletSinkTee tee_plugin;

  @Override
  public String type() {
    return TYPE;
  }
  
  @Override
  public Deferred<Object> initialize(final TSDB tsdb, final String id) {
    this.id = Strings.isNullOrEmpty(id) ? TYPE : id;

    if (!tsdb.getConfig().hasProperty(TEE_ID)) {
      tsdb.getConfig().register(TEE_ID, null, false,
              "An optional plugin to tee serialization results.");
    }

    final String tee_plugin_id = tsdb.getConfig().getString(TEE_ID);
    if (!Strings.isNullOrEmpty(tee_plugin_id)) {
      tee_plugin = tsdb.getRegistry().getPlugin(ServletSinkTee.class, tee_plugin_id);
      if (tee_plugin == null) {
        return Deferred.fromError(new IllegalArgumentException(
                "No servlet sink tee plugin found for " + tee_plugin_id));
      }
    }
    return Deferred.fromResult(null);
  }

  @Override
  public QuerySink newSink(final QueryContext context, 
                           final QuerySinkConfig config) {
    return new ServletSink(context, (ServletSinkConfig) config, tee_plugin);
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
      return mapper.treeToValue(node, ServletSinkConfig.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to parse JSON", e);
    }
  }

}
