package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Set;

@Schema(description = "A free-text / phrase search query", example = """
        {
          "type": "text",
          "phrases": [{ "value": "machine learning" }]
        }
        """)
public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@Valid @NotNull Phrase> phrases,
        @Schema(description = "Optional morphology name; routes to the field's configured morphology destination field")
        String morphology
) implements QueryPayload {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.STRING, FieldType.FREETEXT);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Schema(description = "A single phrase to match", example = """
            { "value": "machine learning", "isExact": false }
            """)
    public record Phrase(
            @NotBlank @JsonProperty("value") String value,
            @Schema(description = "When true, morphology is not applied to this phrase even if the query specifies a morphology")
            @JsonProperty("isExact") Boolean isExact
    ) {
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        return QueryJson.shouldOrSingle(SearchEngine.ELASTICSEARCH, phrases.stream()
                .<JsonNode>map(phrase -> JSON.objectNode()
                        .set("match_phrase", JSON.objectNode().put(fieldFor(context, phrase), phrase.value())))
                .toList());
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        return QueryJson.shouldOrSingle(SearchEngine.SOLR, phrases.stream()
                .<JsonNode>map(phrase -> QueryJson.solrFieldQuery(fieldFor(context, phrase), phrase.value()))
                .toList());
    }

    private String fieldFor(QueryParseContext context, Phrase phrase) {
        String effectiveMorphology = Boolean.TRUE.equals(phrase.isExact()) ? null : morphology;
        return context.requireSearchField("text", SUPPORTED_FIELD_TYPES, effectiveMorphology);
    }
}
