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

package org.codelibs.elasticsearch.search.sort;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.join.BitSetProducer;
import org.codelibs.elasticsearch.action.support.ToXContentToBytes;
import org.codelibs.elasticsearch.common.ParseField;
import org.codelibs.elasticsearch.common.ParsingException;
import org.codelibs.elasticsearch.common.io.stream.NamedWriteable;
import org.codelibs.elasticsearch.common.lucene.search.Queries;
import org.codelibs.elasticsearch.common.xcontent.XContentParser;
import org.codelibs.elasticsearch.index.fielddata.IndexFieldData.XFieldComparatorSource.Nested;
import org.codelibs.elasticsearch.index.query.QueryBuilder;
import org.codelibs.elasticsearch.index.query.QueryParseContext;
import org.codelibs.elasticsearch.index.query.QueryShardContext;
import org.codelibs.elasticsearch.index.query.QueryShardException;
import org.codelibs.elasticsearch.search.DocValueFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.unmodifiableMap;

/**
 *
 */
public abstract class SortBuilder<T extends SortBuilder<T>> extends ToXContentToBytes implements NamedWriteable {

    protected SortOrder order = SortOrder.ASC;

    // parse fields common to more than one SortBuilder
    public static final ParseField ORDER_FIELD = new ParseField("order");
    public static final ParseField NESTED_FILTER_FIELD = new ParseField("nested_filter");
    public static final ParseField NESTED_PATH_FIELD = new ParseField("nested_path");

    private static final Map<String, Parser<?>> PARSERS;
    static {
        Map<String, Parser<?>> parsers = new HashMap<>();
        parsers.put(ScriptSortBuilder.NAME, ScriptSortBuilder::fromXContent);
        parsers.put(GeoDistanceSortBuilder.NAME, GeoDistanceSortBuilder::fromXContent);
        parsers.put(GeoDistanceSortBuilder.ALTERNATIVE_NAME, GeoDistanceSortBuilder::fromXContent);
        parsers.put(ScoreSortBuilder.NAME, ScoreSortBuilder::fromXContent);
        // FieldSortBuilder gets involved if the user specifies a name that isn't one of these.
        PARSERS = unmodifiableMap(parsers);
    }

    /**
     * Create a @link {@link SortFieldAndFormat} from this builder.
     */
    protected abstract SortFieldAndFormat build(QueryShardContext context) throws IOException;

    /**
     * Set the order of sorting.
     */
    @SuppressWarnings("unchecked")
    public T order(SortOrder order) {
        Objects.requireNonNull(order, "sort order cannot be null.");
        this.order = order;
        return (T) this;
    }

    /**
     * Return the {@link SortOrder} used for this {@link SortBuilder}.
     */
    public SortOrder order() {
        return this.order;
    }

    public static List<SortBuilder<?>> fromXContent(QueryParseContext context) throws IOException {
        List<SortBuilder<?>> sortFields = new ArrayList<>(2);
        XContentParser parser = context.parser();
        XContentParser.Token token = parser.currentToken();
        if (token == XContentParser.Token.START_ARRAY) {
            while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                if (token == XContentParser.Token.START_OBJECT) {
                    parseCompoundSortField(context, sortFields);
                } else if (token == XContentParser.Token.VALUE_STRING) {
                    String fieldName = parser.text();
                    sortFields.add(fieldOrScoreSort(fieldName));
                } else {
                    throw new IllegalArgumentException("malformed sort format, "
                            + "within the sort array, an object, or an actual string are allowed");
                }
            }
        } else if (token == XContentParser.Token.VALUE_STRING) {
            String fieldName = parser.text();
            sortFields.add(fieldOrScoreSort(fieldName));
        } else if (token == XContentParser.Token.START_OBJECT) {
            parseCompoundSortField(context, sortFields);
        } else {
            throw new IllegalArgumentException("malformed sort format, either start with array, object, or an actual string");
        }
        return sortFields;
    }

    private static SortBuilder<?> fieldOrScoreSort(String fieldName) {
        if (fieldName.equals(ScoreSortBuilder.NAME)) {
            return new ScoreSortBuilder();
        } else {
            return new FieldSortBuilder(fieldName);
        }
    }

    private static void parseCompoundSortField(QueryParseContext context, List<SortBuilder<?>> sortFields)
            throws IOException {
        XContentParser.Token token;
        XContentParser parser = context.parser();
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                String fieldName = parser.currentName();
                token = parser.nextToken();
                if (token == XContentParser.Token.VALUE_STRING) {
                    SortOrder order = SortOrder.fromString(parser.text());
                    sortFields.add(fieldOrScoreSort(fieldName).order(order));
                } else {
                    if (PARSERS.containsKey(fieldName)) {
                        sortFields.add(PARSERS.get(fieldName).fromXContent(context, fieldName));
                    } else {
                        sortFields.add(FieldSortBuilder.fromXContent(context, fieldName));
                    }
                }
            }
        }
    }

    public static Optional<SortAndFormats> buildSort(List<SortBuilder<?>> sortBuilders, QueryShardContext context) throws IOException {
        List<SortField> sortFields = new ArrayList<>(sortBuilders.size());
        List<DocValueFormat> sortFormats = new ArrayList<>(sortBuilders.size());
        for (SortBuilder<?> builder : sortBuilders) {
            SortFieldAndFormat sf = builder.build(context);
            sortFields.add(sf.field);
            sortFormats.add(sf.format);
        }
        if (!sortFields.isEmpty()) {
            // optimize if we just sort on score non reversed, we don't really
            // need sorting
            boolean sort;
            if (sortFields.size() > 1) {
                sort = true;
            } else {
                SortField sortField = sortFields.get(0);
                if (sortField.getType() == SortField.Type.SCORE && !sortField.getReverse()) {
                    sort = false;
                } else {
                    sort = true;
                }
            }
            if (sort) {
                return Optional.of(new SortAndFormats(
                        new Sort(sortFields.toArray(new SortField[sortFields.size()])),
                        sortFormats.toArray(new DocValueFormat[sortFormats.size()])));
            }
        }
        return Optional.empty();
    }

    protected static Nested resolveNested(QueryShardContext context, String nestedPath, QueryBuilder nestedFilter) throws IOException {
        throw new UnsupportedOperationException("querybuilders does not support this operation.");
    }

    protected static QueryBuilder parseNestedFilter(XContentParser parser, QueryParseContext context) {
        try {
            QueryBuilder builder = context.parseInnerQueryBuilder().orElseThrow(() -> new ParsingException(parser.getTokenLocation(),
                    "Expected " + NESTED_FILTER_FIELD.getPreferredName() + " element."));
            return builder;
        } catch (Exception e) {
            throw new ParsingException(parser.getTokenLocation(), "Expected " + NESTED_FILTER_FIELD.getPreferredName() + " element.", e);
        }
    }

    @FunctionalInterface
    private interface Parser<T extends SortBuilder<?>> {
        T fromXContent(QueryParseContext context, String elementName) throws IOException;
    }
}
