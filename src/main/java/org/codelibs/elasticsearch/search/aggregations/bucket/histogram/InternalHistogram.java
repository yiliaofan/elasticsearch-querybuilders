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
package org.codelibs.elasticsearch.search.aggregations.bucket.histogram;

import org.apache.lucene.util.CollectionUtil;
import org.apache.lucene.util.PriorityQueue;
import org.codelibs.elasticsearch.common.io.stream.StreamInput;
import org.codelibs.elasticsearch.common.io.stream.StreamOutput;
import org.codelibs.elasticsearch.common.xcontent.XContentBuilder;
import org.codelibs.elasticsearch.search.DocValueFormat;
import org.codelibs.elasticsearch.search.aggregations.Aggregations;
import org.codelibs.elasticsearch.search.aggregations.InternalAggregation;
import org.codelibs.elasticsearch.search.aggregations.InternalAggregations;
import org.codelibs.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.codelibs.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.codelibs.elasticsearch.search.aggregations.pipeline.PipelineAggregator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Imelementation of {Histogram}.
 */
public final class InternalHistogram extends InternalMultiBucketAggregation<InternalHistogram, InternalHistogram.Bucket>
        implements Histogram, HistogramFactory {

    static final Type TYPE = new Type("histogram");

    public static class Bucket extends InternalMultiBucketAggregation.InternalBucket implements Histogram.Bucket {

        final double key;
        final long docCount;
        final InternalAggregations aggregations;
        private final transient boolean keyed;
        protected final transient DocValueFormat format;

        public Bucket(double key, long docCount, boolean keyed, DocValueFormat format,
                InternalAggregations aggregations) {
            this.format = format;
            this.keyed = keyed;
            this.key = key;
            this.docCount = docCount;
            this.aggregations = aggregations;
        }

        /**
         * Read from a stream.
         */
        public Bucket(StreamInput in, boolean keyed, DocValueFormat format) throws IOException {
            this.format = format;
            this.keyed = keyed;
            key = in.readDouble();
            docCount = in.readVLong();
            aggregations = InternalAggregations.readAggregations(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeDouble(key);
            out.writeVLong(docCount);
            aggregations.writeTo(out);
        }

        @Override
        public String getKeyAsString() {
            return format.format(key);
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public long getDocCount() {
            return docCount;
        }

        @Override
        public Aggregations getAggregations() {
            return aggregations;
        }

        Bucket reduce(List<Bucket> buckets, ReduceContext context) {
            List<InternalAggregations> aggregations = new ArrayList<>(buckets.size());
            long docCount = 0;
            for (Bucket bucket : buckets) {
                docCount += bucket.docCount;
                aggregations.add((InternalAggregations) bucket.getAggregations());
            }
            InternalAggregations aggs = InternalAggregations.reduce(aggregations, context);
            return new InternalHistogram.Bucket(key, docCount, keyed, format, aggs);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            String keyAsString = format.format(key);
            if (keyed) {
                builder.startObject(keyAsString);
            } else {
                builder.startObject();
            }
            if (format != DocValueFormat.RAW) {
                builder.field(CommonFields.KEY_AS_STRING, keyAsString);
            }
            builder.field(CommonFields.KEY, key);
            builder.field(CommonFields.DOC_COUNT, docCount);
            aggregations.toXContentInternal(builder, params);
            builder.endObject();
            return builder;
        }

        public DocValueFormat getFormatter() {
            return format;
        }

        public boolean getKeyed() {
            return keyed;
        }
    }

    static class EmptyBucketInfo {

        final double interval, offset, minBound, maxBound;
        final InternalAggregations subAggregations;

        EmptyBucketInfo(double interval, double offset, double minBound, double maxBound, InternalAggregations subAggregations) {
            this.interval = interval;
            this.offset = offset;
            this.minBound = minBound;
            this.maxBound = maxBound;
            this.subAggregations = subAggregations;
        }

        EmptyBucketInfo(StreamInput in) throws IOException {
            this(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), InternalAggregations.readAggregations(in));
        }

        public void writeTo(StreamOutput out) throws IOException {
            out.writeDouble(interval);
            out.writeDouble(offset);
            out.writeDouble(minBound);
            out.writeDouble(maxBound);
            subAggregations.writeTo(out);
        }

    }

    private final List<Bucket> buckets;
    private final InternalOrder order;
    private final DocValueFormat format;
    private final boolean keyed;
    private final long minDocCount;
    private final EmptyBucketInfo emptyBucketInfo;

    InternalHistogram(String name, List<Bucket> buckets, InternalOrder order, long minDocCount, EmptyBucketInfo emptyBucketInfo,
            DocValueFormat formatter, boolean keyed, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) {
        super(name, pipelineAggregators, metaData);
        this.buckets = buckets;
        this.order = order;
        assert (minDocCount == 0) == (emptyBucketInfo != null);
        this.minDocCount = minDocCount;
        this.emptyBucketInfo = emptyBucketInfo;
        this.format = formatter;
        this.keyed = keyed;
    }

    /**
     * Stream from a stream.
     */
    public InternalHistogram(StreamInput in) throws IOException {
        super(in);
        order = InternalOrder.Streams.readOrder(in);
        minDocCount = in.readVLong();
        if (minDocCount == 0) {
            emptyBucketInfo = new EmptyBucketInfo(in);
        } else {
            emptyBucketInfo = null;
        }
        format = in.readNamedWriteable(DocValueFormat.class);
        keyed = in.readBoolean();
        buckets = in.readList(stream -> new Bucket(stream, keyed, format));
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        InternalOrder.Streams.writeOrder(order, out);
        out.writeVLong(minDocCount);
        if (minDocCount == 0) {
            emptyBucketInfo.writeTo(out);
        }
        out.writeNamedWriteable(format);
        out.writeBoolean(keyed);
        out.writeList(buckets);
    }

    @Override
    public String getWriteableName() {
        return HistogramAggregationBuilder.NAME;
    }

    @Override
    public List<Histogram.Bucket> getBuckets() {
        return Collections.unmodifiableList(buckets);
    }

    @Override
    public InternalHistogram create(List<Bucket> buckets) {
        return new InternalHistogram(name, buckets, order, minDocCount, emptyBucketInfo, format, keyed, pipelineAggregators(), metaData);
    }

    @Override
    public Bucket createBucket(InternalAggregations aggregations, Bucket prototype) {
        return new Bucket(prototype.key, prototype.docCount, prototype.keyed, prototype.format, aggregations);
    }

    private static class IteratorAndCurrent {

        private final Iterator<Bucket> iterator;
        private Bucket current;

        IteratorAndCurrent(Iterator<Bucket> iterator) {
            this.iterator = iterator;
            current = iterator.next();
        }

    }

    private List<Bucket> reduceBuckets(List<InternalAggregation> aggregations, ReduceContext reduceContext) {

        final PriorityQueue<IteratorAndCurrent> pq = new PriorityQueue<IteratorAndCurrent>(aggregations.size()) {
            @Override
            protected boolean lessThan(IteratorAndCurrent a, IteratorAndCurrent b) {
                return a.current.key < b.current.key;
            }
        };
        for (InternalAggregation aggregation : aggregations) {
            InternalHistogram histogram = (InternalHistogram) aggregation;
            if (histogram.buckets.isEmpty() == false) {
                pq.add(new IteratorAndCurrent(histogram.buckets.iterator()));
            }
        }

        List<Bucket> reducedBuckets = new ArrayList<>();
        if (pq.size() > 0) {
            // list of buckets coming from different shards that have the same key
            List<Bucket> currentBuckets = new ArrayList<>();
            double key = pq.top().current.key;

            do {
                final IteratorAndCurrent top = pq.top();

                if (top.current.key != key) {
                    // the key changes, reduce what we already buffered and reset the buffer for current buckets
                    final Bucket reduced = currentBuckets.get(0).reduce(currentBuckets, reduceContext);
                    if (reduced.getDocCount() >= minDocCount) {
                        reducedBuckets.add(reduced);
                    }
                    currentBuckets.clear();
                    key = top.current.key;
                }

                currentBuckets.add(top.current);

                if (top.iterator.hasNext()) {
                    final Bucket next = top.iterator.next();
                    assert next.key > top.current.key : "shards must return data sorted by key";
                    top.current = next;
                    pq.updateTop();
                } else {
                    pq.pop();
                }
            } while (pq.size() > 0);

            if (currentBuckets.isEmpty() == false) {
                final Bucket reduced = currentBuckets.get(0).reduce(currentBuckets, reduceContext);
                if (reduced.getDocCount() >= minDocCount) {
                    reducedBuckets.add(reduced);
                }
            }
        }

        return reducedBuckets;
    }

    private double nextKey(double key) {
        return round(key + emptyBucketInfo.interval + emptyBucketInfo.interval / 2);
    }

    private double round(double key) {
        return Math.floor((key - emptyBucketInfo.offset) / emptyBucketInfo.interval) * emptyBucketInfo.interval + emptyBucketInfo.offset;
    }

    private void addEmptyBuckets(List<Bucket> list, ReduceContext reduceContext) {
        ListIterator<Bucket> iter = list.listIterator();

        // first adding all the empty buckets *before* the actual data (based on th extended_bounds.min the user requested)
        InternalAggregations reducedEmptySubAggs = InternalAggregations.reduce(
                Collections.singletonList(emptyBucketInfo.subAggregations),
                reduceContext);

        if (iter.hasNext() == false) {
            // fill with empty buckets
            for (double key = round(emptyBucketInfo.minBound); key <= emptyBucketInfo.maxBound; key = nextKey(key)) {
                iter.add(new Bucket(key, 0, keyed, format, reducedEmptySubAggs));
            }
        } else {
            Bucket first = list.get(iter.nextIndex());
            if (Double.isFinite(emptyBucketInfo.minBound)) {
                // fill with empty buckets until the first key
                for (double key = round(emptyBucketInfo.minBound); key < first.key; key = nextKey(key)) {
                    iter.add(new Bucket(key, 0, keyed, format, reducedEmptySubAggs));
                }
            }

            // now adding the empty buckets within the actual data,
            // e.g. if the data series is [1,2,3,7] there're 3 empty buckets that will be created for 4,5,6
            Bucket lastBucket = null;
            do {
                Bucket nextBucket = list.get(iter.nextIndex());
                if (lastBucket != null) {
                    double key = nextKey(lastBucket.key);
                    while (key < nextBucket.key) {
                        iter.add(new Bucket(key, 0, keyed, format, reducedEmptySubAggs));
                        key = nextKey(key);
                    }
                    assert key == nextBucket.key;
                }
                lastBucket = iter.next();
            } while (iter.hasNext());

            // finally, adding the empty buckets *after* the actual data (based on the extended_bounds.max requested by the user)
            for (double key = nextKey(lastBucket.key); key <= emptyBucketInfo.maxBound; key = nextKey(key)) {
                iter.add(new Bucket(key, 0, keyed, format, reducedEmptySubAggs));
            }
        }
    }

    @Override
    public InternalAggregation doReduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        List<Bucket> reducedBuckets = reduceBuckets(aggregations, reduceContext);

        // adding empty buckets if needed
        if (minDocCount == 0) {
            addEmptyBuckets(reducedBuckets, reduceContext);
        }

        if (order == InternalOrder.KEY_ASC) {
            // nothing to do, data are already sorted since shards return
            // sorted buckets and the merge-sort performed by reduceBuckets
            // maintains order
        } else if (order == InternalOrder.KEY_DESC) {
            // we just need to reverse here...
            List<Bucket> reverse = new ArrayList<>(reducedBuckets);
            Collections.reverse(reverse);
            reducedBuckets = reverse;
        } else {
            // sorted by sub-aggregation, need to fall back to a costly n*log(n) sort
            CollectionUtil.introSort(reducedBuckets, order.comparator());
        }

        return new InternalHistogram(getName(), reducedBuckets, order, minDocCount, emptyBucketInfo, format, keyed, pipelineAggregators(),
                getMetaData());
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        if (keyed) {
            builder.startObject(CommonFields.BUCKETS);
        } else {
            builder.startArray(CommonFields.BUCKETS);
        }
        for (Bucket bucket : buckets) {
            bucket.toXContent(builder, params);
        }
        if (keyed) {
            builder.endObject();
        } else {
            builder.endArray();
        }
        return builder;
    }

    // HistogramFactory method impls

    @Override
    public Number getKey(MultiBucketsAggregation.Bucket bucket) {
        return ((Bucket) bucket).key;
    }

    @Override
    public Number nextKey(Number key) {
        return nextKey(key.doubleValue());
    }

    @Override
    public InternalAggregation createAggregation(List<MultiBucketsAggregation.Bucket> buckets) {
        // convert buckets to the right type
        List<Bucket> buckets2 = new ArrayList<>(buckets.size());
        for (Object b : buckets) {
            buckets2.add((Bucket) b);
        }
        buckets2 = Collections.unmodifiableList(buckets2);
        return new InternalHistogram(name, buckets2, order, minDocCount, emptyBucketInfo, format,
                keyed, pipelineAggregators(), getMetaData());
    }

    @Override
    public Bucket createBucket(Number key, long docCount, InternalAggregations aggregations) {
        return new Bucket(key.doubleValue(), docCount, keyed, format, aggregations);
    }

}
