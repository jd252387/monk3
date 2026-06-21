package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Set;

@Schema(description = "A free-text / phrase search query", example = """
        {
          "type": "text",
          "phrases": ["machine learning"]
        }
        """)
public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@NotBlank String> phrases,
        @Schema(description = "Optional morphology name; routes to the field's configured morphology destination field")
        String morphology
) implements QueryPayload {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.STRING, FieldType.FREETEXT);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("text", SUPPORTED_FIELD_TYPES, morphology);
        return QueryJson.shouldOrSingle(SearchEngine.ELASTICSEARCH, phrases.stream()
                .<JsonNode>map(phrase -> JSON.objectNode().set("match_phrase", JSON.objectNode().put(field, phrase)))
                .toList());
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", SUPPORTED_FIELD_TYPES, morphology);
        return QueryJson.shouldOrSingle(SearchEngine.SOLR, phrases.stream()
                .<JsonNode>map(phrase -> QueryJson.solrFieldQuery(field, phrase))
                .toList());
    }
}
