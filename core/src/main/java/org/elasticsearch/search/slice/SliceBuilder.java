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

package org.elasticsearch.search.slice;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.action.support.ToXContentToBytes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.UidFieldMapper;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Objects;

/**
 *  A slice builder allowing to split a scroll in multiple partitions.
 *  If the provided field is the "_uid" it uses a {@link org.elasticsearch.search.slice.TermsSliceQuery}
 *  to do the slicing. The slicing is done at the shard level first and then each shard is split into multiple slices.
 *  For instance if the number of shards is equal to 2 and the user requested 4 slices
 *  then the slices 0 and 2 are assigned to the first shard and the slices 1 and 3 are assigned to the second shard.
 *  This way the total number of bitsets that we need to build on each shard is bounded by the number of slices
 *  (instead of {@code numShards*numSlices}).
 *  Otherwise the provided field must be a numeric and doc_values must be enabled. In that case a
 *  {@link org.elasticsearch.search.slice.DocValuesSliceQuery} is used to filter the results.
 */
public class SliceBuilder extends ToXContentToBytes implements Writeable {
    public static final ParseField FIELD_FIELD = new ParseField("field");
    public static final ParseField ID_FIELD = new ParseField("id");
    public static final ParseField MAX_FIELD = new ParseField("max");
    private static final ObjectParser<SliceBuilder, Void> PARSER =
        new ObjectParser<>("slice", SliceBuilder::new);

    static {
        PARSER.declareString(SliceBuilder::setField, FIELD_FIELD);
        PARSER.declareInt(SliceBuilder::setId, ID_FIELD);
        PARSER.declareInt(SliceBuilder::setMax, MAX_FIELD);
    }

    /** Name of field to slice against (_uid by default) */
    private String field = UidFieldMapper.NAME;
    /** The id of the slice */
    private int id = -1;
    /** Max number of slices */
    private int max = -1;

    private SliceBuilder() {}

    public SliceBuilder(int id, int max) {
        this(UidFieldMapper.NAME, id, max);
    }

    /**
     *
     * @param field The name of the field
     * @param id The id of the slice
     * @param max The maximum number of slices
     */
    public SliceBuilder(String field, int id, int max) {
        setField(field);
        setId(id);
        setMax(max);
    }

    public SliceBuilder(StreamInput in) throws IOException {
        this.field = in.readString();
        this.id = in.readVInt();
        this.max = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(field);
        out.writeVInt(id);
        out.writeVInt(max);
    }

    private SliceBuilder setField(String field) {
        if (Strings.isEmpty(field)) {
            throw new IllegalArgumentException("field name is null or empty");
        }
        this.field = field;
        return this;
    }

    /**
     * The name of the field to slice against
     */
    public String getField() {
        return this.field;
    }

    private SliceBuilder setId(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be greater than or equal to 0");
        }
        if (max != -1 && id >= max) {
            throw new IllegalArgumentException("max must be greater than id");
        }
        this.id = id;
        return this;
    }

    /**
     * The id of the slice.
     */
    public int getId() {
        return id;
    }

    private SliceBuilder setMax(int max) {
        if (max <= 1) {
            throw new IllegalArgumentException("max must be greater than 1");
        }
        if (id != -1 && id >= max) {
            throw new IllegalArgumentException("max must be greater than id");
        }
        this.max = max;
        return this;
    }

    /**
     * The maximum number of slices.
     */
    public int getMax() {
        return max;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        innerToXContent(builder);
        builder.endObject();
        return builder;
    }

    void innerToXContent(XContentBuilder builder) throws IOException {
        builder.field(FIELD_FIELD.getPreferredName(), field);
        builder.field(ID_FIELD.getPreferredName(), id);
        builder.field(MAX_FIELD.getPreferredName(), max);
    }

    public static SliceBuilder fromXContent(XContentParser parser) throws IOException {
        SliceBuilder builder = PARSER.parse(parser, new SliceBuilder(), null);
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SliceBuilder)) {
            return false;
        }

        SliceBuilder o = (SliceBuilder) other;
        return ((field == null && o.field == null) || field.equals(o.field))
            && id == o.id && o.max == max;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.field, this.id, this.max);
    }

    public Query toFilter(QueryShardContext context, int shardId, int numShards) {
        final MappedFieldType type = context.fieldMapper(field);
        if (type == null) {
            throw new IllegalArgumentException("field " + field + " not found");
        }

        String field = this.field;
        boolean useTermQuery = false;
        if (UidFieldMapper.NAME.equals(field)) {
            if (context.getIndexSettings().isSingleType()) {
                // on new indices, the _id acts as a _uid
                field = IdFieldMapper.NAME;
            }
            useTermQuery = true;
        } else if (type.hasDocValues() == false) {
            throw new IllegalArgumentException("cannot load numeric doc values on " + field);
        } else {
            IndexFieldData ifm = context.getForField(type);
            if (ifm instanceof IndexNumericFieldData == false) {
                throw new IllegalArgumentException("cannot load numeric doc values on " + field);
            }
        }

        if (numShards == 1) {
            return useTermQuery ? new TermsSliceQuery(field, id, max) :
                new DocValuesSliceQuery(field, id, max);
        }
        if (max >= numShards) {
            // the number of slices is greater than the number of shards
            // in such case we can reduce the number of requested shards by slice

            // first we check if the slice is responsible of this shard
            int targetShard = id % numShards;
            if (targetShard != shardId) {
                // the shard is not part of this slice, we can skip it.
                return new MatchNoDocsQuery("this shard is not part of the slice");
            }
            // compute the number of slices where this shard appears
            int numSlicesInShard = max / numShards;
            int rest = max % numShards;
            if (rest > targetShard) {
                numSlicesInShard++;
            }

            if (numSlicesInShard == 1) {
                // this shard has only one slice so we must check all the documents
                return new MatchAllDocsQuery();
            }
            // get the new slice id for this shard
            int shardSlice = id / numShards;

            return useTermQuery ?
                new TermsSliceQuery(field, shardSlice, numSlicesInShard) :
                new DocValuesSliceQuery(field, shardSlice, numSlicesInShard);
        }
        // the number of shards is greater than the number of slices

        // check if the shard is assigned to the slice
        int targetSlice = shardId % max;
        if (id != targetSlice) {
            // the shard is not part of this slice, we can skip it.
            return new MatchNoDocsQuery("this shard is not part of the slice");
        }
        return new MatchAllDocsQuery();
    }
}
