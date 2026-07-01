package com.monk.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk.search.QueryParseContext;
import com.monk.search.QueryTranslationException;
import com.monk.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = """
        A node in the search query tree. Two shapes:
        <ul>
          <li><b>Leaf node</b> — non-empty <code>field</code> with a <code>QueryPayload</code> (text, range, or exact).</li>
          <li><b>Boolean node</b> — empty <code>field</code> with <code>BooleanQueryData</code> (a flat array of clause
              QueryNodes, each tagged with a <code>bool</code> of should/must/mustNot).</li>
        </ul>
        """, example = """
        {
          "field": "title",
          "data": {
            "type": "text",
            "phrases": [{ "type": "phrase", "value": "machine learning" }]
          }
        }
        """)
public record QueryNode(
        @NotNull @Schema(description = "Logical field name; empty for boolean nodes, non-empty for leaf/subdocument nodes") String field,
        @Positive @Schema(description = "Minimum number of should-clauses that must match (boolean nodes only)") Integer minimumMatch,
        @Schema(description = "How this node combines with its siblings (required when this node is a clause of a boolean node)") BooleanOccur bool,
        @Valid @Schema(description = "Query payload (leaf) or boolean clause list (boolean node); absent for predicate virtual fields") QueryData data
) {
    public JsonNode translate(SearchEngine engine, QueryParseContext context) {
        return translateData(engine, context);
    }

    private JsonNode translateData(SearchEngine engine, QueryParseContext context) {
        if (!field.isEmpty()) {
            var virtualField = context.findVirtualField(field);
            if (virtualField.isPresent()) {
                return context.expandVirtual(virtualField.get(), data, engine);
            }
        }
        if (data == null) {
            throw new QueryTranslationException("Query node for field '" + field + "' requires data");
        }
        return data.translate(engine, context, this);
    }
}
