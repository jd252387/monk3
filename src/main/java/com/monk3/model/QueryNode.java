package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.json.QueryNodeDeserializer;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = """
        A node in the search query tree. Two shapes:
        <ul>
          <li><b>Leaf node</b> — non-empty <code>field</code> with a <code>QueryPayload</code> (text, range, or exact).</li>
          <li><b>Boolean node</b> — empty <code>field</code> with <code>BooleanQueryData</code> (list-of-lists of QueryNodes, outer = OR/should, inner = AND/must).</li>
        </ul>
        """, example = """
        {
          "field": "title",
          "data": {
            "type": "text",
            "phrases": ["machine learning"]
          }
        }
        """)
@JsonDeserialize(using = QueryNodeDeserializer.class)
public record QueryNode(
        @NotNull @Schema(description = "Logical field name; empty for boolean nodes, non-empty for leaf/subdocument nodes") String field,
        @Positive @Schema(description = "Minimum number of should-clauses that must match (boolean nodes only)") Integer minimumMatch,
        @Schema(description = "Negate this node's result") Boolean isNot,
        @NotNull @Valid @Schema(description = "Query payload (leaf) or boolean clause list (boolean node)") QueryData data
) {
    @AssertTrue(message = "field determines data shape")
    public boolean hasMatchingDataShape() {
        if (field == null || data == null) {
            return true;
        }
        return field.isEmpty() ? data instanceof BooleanQueryData : data instanceof QueryPayload || data instanceof BooleanQueryData;
    }

    public JsonNode toElasticsearch(QueryParseContext context) {
        if (!field.isEmpty() && data instanceof QueryPayload payload) {
            var vf = context.findVirtualField(field);
            if (vf.isPresent()) {
                return context.expandVirtual(vf.get(), payload, isNegated(), SearchEngine.ELASTICSEARCH);
            }
        }
        return data.toElasticsearch(context, this);
    }

    public JsonNode toSolr(QueryParseContext context) {
        if (!field.isEmpty() && data instanceof QueryPayload payload) {
            var vf = context.findVirtualField(field);
            if (vf.isPresent()) {
                return context.expandVirtual(vf.get(), payload, isNegated(), SearchEngine.SOLR);
            }
        }
        return data.toSolr(context, this);
    }

    @JsonIgnore
    boolean isNegated() {
        return Boolean.TRUE.equals(isNot);
    }
}
