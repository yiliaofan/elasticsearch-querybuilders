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
package org.codelibs.elasticsearch.search.aggregations.bucket.missing;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.util.Bits;
import org.codelibs.elasticsearch.search.aggregations.Aggregator;
import org.codelibs.elasticsearch.search.aggregations.AggregatorFactories;
import org.codelibs.elasticsearch.search.aggregations.InternalAggregation;
import org.codelibs.elasticsearch.search.aggregations.LeafBucketCollector;
import org.codelibs.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.codelibs.elasticsearch.search.aggregations.bucket.SingleBucketAggregator;
import org.codelibs.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.codelibs.elasticsearch.search.aggregations.support.ValuesSource;
import org.codelibs.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class MissingAggregator extends SingleBucketAggregator {

    private final ValuesSource valuesSource;

    public MissingAggregator(String name, AggregatorFactories factories, ValuesSource valuesSource,
            SearchContext aggregationContext, Aggregator parent, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) throws IOException {
        super(name, factories, aggregationContext, parent, pipelineAggregators, metaData);
        this.valuesSource = valuesSource;
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
            final LeafBucketCollector sub) throws IOException {

        final Bits docsWithValue;
        if (valuesSource != null) {
            docsWithValue = valuesSource.docsWithValue(ctx);
        } else {
            docsWithValue = new Bits.MatchNoBits(ctx.reader().maxDoc());
        }
        return new LeafBucketCollectorBase(sub, docsWithValue) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (docsWithValue != null && !docsWithValue.get(doc)) {
                    collectBucket(sub, doc, bucket);
                }
            }
        };
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) throws IOException {
        return new InternalMissing(name, bucketDocCount(owningBucketOrdinal), bucketAggregations(owningBucketOrdinal), pipelineAggregators(),
                metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalMissing(name, 0, buildEmptySubAggregations(), pipelineAggregators(), metaData());
    }

}


