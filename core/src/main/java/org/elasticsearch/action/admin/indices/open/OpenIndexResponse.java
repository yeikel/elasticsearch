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

package org.elasticsearch.action.admin.indices.open;

import org.elasticsearch.Version;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;

/**
 * A response for a open index action.
 */
public class OpenIndexResponse extends AcknowledgedResponse implements ToXContentObject  {
    private static final String SHARDS_ACKNOWLEDGED = "shards_acknowledged";
    private static final ParseField SHARDS_ACKNOWLEDGED_PARSER = new ParseField(SHARDS_ACKNOWLEDGED);

    private static final ConstructingObjectParser<OpenIndexResponse, Void> PARSER = new ConstructingObjectParser<>("open_index", true,
            args -> new OpenIndexResponse((boolean) args[0], (boolean) args[1]));

    static {
        declareAcknowledgedField(PARSER);
        PARSER.declareField(constructorArg(), (parser, context) -> parser.booleanValue(), SHARDS_ACKNOWLEDGED_PARSER,
                ObjectParser.ValueType.BOOLEAN);
    }

    private boolean shardsAcknowledged;

    OpenIndexResponse() {
    }

    OpenIndexResponse(boolean acknowledged, boolean shardsAcknowledged) {
        super(acknowledged);
        assert acknowledged || shardsAcknowledged == false; // if its not acknowledged, then shards acked should be false too
        this.shardsAcknowledged = shardsAcknowledged;
    }

    /**
     * Returns true if the requisite number of shards were started before
     * returning from the indices opening operation.  If {@link #isAcknowledged()}
     * is false, then this also returns false.
     */
    public boolean isShardsAcknowledged() {
        return shardsAcknowledged;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        readAcknowledged(in);
        if (in.getVersion().onOrAfter(Version.V_6_1_0)) {
            shardsAcknowledged = in.readBoolean();
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        writeAcknowledged(out);
        if (out.getVersion().onOrAfter(Version.V_6_1_0)) {
            out.writeBoolean(shardsAcknowledged);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        addAcknowledgedField(builder);
        builder.field(SHARDS_ACKNOWLEDGED, isShardsAcknowledged());
        builder.endObject();
        return builder;
    }

    public static OpenIndexResponse fromXContent(XContentParser parser) throws IOException {
        return PARSER.apply(parser, null);
    }
}
