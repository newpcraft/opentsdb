// This file is part of OpenTSDB.
// Copyright (C) 2016  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.data.iterators;

/**
 * Represents a single time series with a {@code TimeSeriesId} shared between
 * all time series. The implementation returns an iterator for viewing the data
 * in the series.
 * 
 * @param <T> The type of data represented by the time series (must return
 * a {@link TimeSeriesValue}
 */
public interface TimeSeries<T extends TimeSeriesValue<?>> {
  
  /**
   * Returns a streaming iterator to view the data for this time series.
   * 
   * WARNING: This method may be called by multiple sinks so each call must 
   * return an independent (new) iterator that doesn't affect any other iterators
   * working over the data.
   *  
   * @return A streaming iterator over the time series.
   */
  public StreamingIterator<T> iterator();
  
}
