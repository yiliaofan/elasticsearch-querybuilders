/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.codelibs.elasticsearch.search.aggregations.metrics.geocentroid;

import org.codelibs.elasticsearch.search.aggregations.Aggregator;
import org.codelibs.elasticsearch.search.aggregations.AggregatorFactories;
import org.codelibs.elasticsearch.search.aggregations.AggregatorFactory;
import org.codelibs.elasticsearch.search.aggregations.InternalAggregation.Type;
import org.codelibs.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.codelibs.elasticsearch.search.aggregations.support.ValuesSource;
import org.codelibs.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.codelibs.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.codelibs.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class GeoCentroidAggregatorFactory extends ValuesSourceAggregatorFactory<ValuesSource.GeoPoint, GeoCentroidAggregatorFactory> {

    public GeoCentroidAggregatorFactory(String name, Type type, ValuesSourceConfig<ValuesSource.GeoPoint> config,
            SearchContext context, AggregatorFactory<?> parent, AggregatorFactories.Builder subFactoriesBuilder,
            Map<String, Object> metaData) throws IOException {
        super(name, type, config, context, parent, subFactoriesBuilder, metaData);
    }

    @Override
    protected Aggregator createUnmapped(Aggregator parent,
            List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) throws IOException {
        return new GeoCentroidAggregator(name, context, parent, null, pipelineAggregators, metaData);
    }

    @Override
    protected Aggregator doCreateInternal(ValuesSource.GeoPoint valuesSource, Aggregator parent,
            boolean collectsFromSingleBucket, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData)
                    throws IOException {
        return new GeoCentroidAggregator(name, context, parent, valuesSource, pipelineAggregators, metaData);
    }
}
