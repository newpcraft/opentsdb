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
package net.opentsdb.query;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

import net.opentsdb.data.PartialTimeSeries;
import net.opentsdb.data.TimeSeriesDataSource;
import net.opentsdb.exceptions.QueryUpstreamException;
import net.opentsdb.utils.UnitTestException;

public class TestAbstractQueryNode {

  private QueryNodeFactory factory;
  private QueryPipelineContext context;
  private List<QueryNode> upstream;
  private List<QueryNode> downstream;
  private List<TimeSeriesDataSource> downstream_sources;
  
  @Before
  public void before() throws Exception {
    factory = mock(QueryNodeFactory.class);
    context = mock(QueryPipelineContext.class);
    
    upstream = Lists.newArrayList(
        mock(QueryNode.class),
        mock(QueryNode.class));
    downstream = Lists.newArrayList(
        mock(QueryNode.class),
        mock(QueryNode.class));
    downstream_sources = Lists.newArrayList(
        mock(TimeSeriesDataSource.class),
        mock(TimeSeriesDataSource.class));
    
    when(context.upstream(any(QueryNode.class))).thenReturn(upstream);
    when(context.downstream(any(QueryNode.class))).thenReturn(downstream);
    when(context.downstreamSources(any(QueryNode.class))).thenReturn(downstream_sources);
  }
  
  @Test
  public void ctor() throws Exception {
    TestAQ node = new TestAQ(factory, context);
    assertSame(factory, node.factory());
    assertSame(context, node.pipelineContext());
    
    try {
      new TestAQ(factory, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void initialize() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    assertNull(node.upstream);
    assertNull(node.downstream);
    assertNull(node.downstream_sources);
    
    node.initialize(null);
    assertSame(upstream, node.upstream);
    assertSame(downstream, node.downstream);
    assertSame(downstream_sources, node.downstream_sources);
  }
  
  @Test
  public void sendUpstream() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    
    try {
      node.sendUpstream((PartialTimeSeries) null);
      fail("Expected QueryUpstreamException");
    } catch (QueryUpstreamException e) { }
    
    final PartialTimeSeries series = mock(PartialTimeSeries.class);
    node.sendUpstream(series);
    verify(upstream.get(0), times(1)).onNext(series);
    verify(upstream.get(1), times(1)).onNext(series);
  }
  
  @Test
  public void sendUpstreamQueryResult() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    
    try {
      node.sendUpstream((QueryResult) null);
      fail("Expected QueryUpstreamException");
    } catch (QueryUpstreamException e) { }
    
    final QueryResult result = mock(QueryResult.class);
    node.sendUpstream(result);
    verify(upstream.get(0), times(1)).onNext(result);
    verify(upstream.get(1), times(1)).onNext(result);
  }
  
  @Test
  public void sendUpstreamExceptionSecond() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(1)).onNext(any(QueryResult.class));
    
    final QueryResult result = mock(QueryResult.class);
    try {
      node.sendUpstream(result);
      fail("Expected QueryUpstreamException");
    } catch (QueryUpstreamException e) { }
    verify(upstream.get(0), times(1)).onNext(result);
    verify(upstream.get(1), times(1)).onNext(result);
  }
  
  @Test
  public void sendUpstreamExceptionFirst() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(0)).onNext(any(QueryResult.class));
    
    final QueryResult result = mock(QueryResult.class);
    try {
      node.sendUpstream(result);
      fail("Expected QueryUpstreamException");
    } catch (QueryUpstreamException e) { }
    verify(upstream.get(0), times(1)).onNext(result);
    verify(upstream.get(1), never()).onNext(result);
  }
  
  @Test
  public void sendUpstreamThrowable() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    
    try {
      node.sendUpstream((Throwable) null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    final UnitTestException ex = new UnitTestException();
    node.sendUpstream(ex);
    verify(upstream.get(0), times(1)).onError(ex);
    verify(upstream.get(1), times(1)).onError(ex);
    
    node.onError(ex);
    verify(upstream.get(0), times(2)).onError(ex);
    verify(upstream.get(1), times(2)).onError(ex);
  }
  
  @Test
  public void sendUpstreamThrowableExceptionSecond() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(1)).onError(any(Throwable.class));
   
    final UnitTestException ex = new UnitTestException();
    node.sendUpstream(ex);
    verify(upstream.get(0), times(1)).onError(ex);
    verify(upstream.get(1), times(1)).onError(ex);
  }
  
  @Test
  public void sendUpstreamThrowableExceptionFirst() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(0)).onError(any(Throwable.class));
   
    final UnitTestException ex = new UnitTestException();
    node.sendUpstream(ex);
    verify(upstream.get(0), times(1)).onError(ex);
    verify(upstream.get(1), times(1)).onError(ex);
  }
    
  @Test
  public void completeUpstreamQueryResult() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    
    node.completeUpstream(42, 42);
    verify(upstream.get(0), times(1)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(1)).onComplete(node, 42, 42);
    
    node.onComplete(node, 42, 42);
    verify(upstream.get(0), times(2)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(2)).onComplete(node, 42, 42);
    
    node.onComplete(null, 42, 42);
    verify(upstream.get(0), times(3)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(3)).onComplete(node, 42, 42);
  }
  
  @Test
  public void completeUpstreamExceptionSecond() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(1)).onComplete(any(QueryNode.class), anyLong(), anyLong());
    
    node.completeUpstream(42, 42);
    verify(upstream.get(0), times(1)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(1)).onComplete(node, 42, 42);
    
    node.onComplete(node, 42, 42);
    verify(upstream.get(0), times(2)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(2)).onComplete(node, 42, 42);
  }
  
  @Test
  public void completeUpstreamExceptionFirst() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    doThrow(new UnitTestException())
      .when(upstream.get(0)).onComplete(any(QueryNode.class), anyLong(), anyLong());
    
    node.completeUpstream(42, 42);
    verify(upstream.get(0), times(1)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(1)).onComplete(node, 42, 42);
    
    node.onComplete(node, 42, 42);
    verify(upstream.get(0), times(2)).onComplete(node, 42, 42);
    verify(upstream.get(1), times(2)).onComplete(node, 42, 42);
  }
  
  @Test
  public void fetchDownstream() throws Exception {
    final TestAQ node = new TestAQ(factory, context);
    node.initialize(null);
    
    node.fetchDownstream(null);
    verify(downstream_sources.get(0), times(1)).fetchNext(null);
    verify(downstream_sources.get(1), times(1)).fetchNext(null);
    
    doThrow(new UnitTestException()).when(downstream_sources.get(1))
      .fetchNext(null);
    try {
      node.fetchDownstream(null);
      fail("Expected UnitTestException");
    } catch (UnitTestException e) { }
    verify(downstream_sources.get(0), times(2)).fetchNext(null);
    verify(downstream_sources.get(1), times(2)).fetchNext(null);
  }
  
  class TestAQ extends AbstractQueryNode {

    public TestAQ(final QueryNodeFactory factory, 
                  final QueryPipelineContext context) {
      super(factory, context);
    }

    @Override
    public QueryNodeConfig config() { return null; }
    
    @Override
    public void close() { }

    @Override
    public void onNext(QueryResult next) { }
    
  }
  
  class TestAQ2 extends AbstractQueryNode {

    public TestAQ2(final QueryNodeFactory factory, 
                  final QueryPipelineContext context) {
      super(factory, context);
    }

    @Override
    public QueryNodeConfig config() { return null; }
    
    @Override
    public void close() { }
    
    @Override
    public void onNext(QueryResult next) { }

  }
}
