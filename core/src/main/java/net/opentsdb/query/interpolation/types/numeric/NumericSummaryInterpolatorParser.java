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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.opentsdb.core.TSDB;
import net.opentsdb.query.interpolation.QueryInterpolatorConfig;
import net.opentsdb.query.interpolation.QueryInterpolatorConfigParser;

/**
 * Returns a Numeric Summary Interpolator config.
 * TODO - handle scalars
 * 
 * @since 3.0
 */
public class NumericSummaryInterpolatorParser implements QueryInterpolatorConfigParser {

  @Override
  public QueryInterpolatorConfig parse(final ObjectMapper mapper, 
                                       final TSDB tsdb,
                                       final JsonNode node) {
    try {
      return mapper.treeToValue(node, NumericSummaryInterpolatorConfig.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to parse the config", e);
    }
  }

}
