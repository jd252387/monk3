package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.json.PhraseDeserializer;
import com.monk.json.QueryPayloadParser;
import com.monk.search.QueryJson;
import com.monk.search.QueryParseContext;
import com.monk.search.QueryTranslationException;
import com.monk.search.SearchEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "A free-text / phrase search query", example = """
        {
          "type": "text",
          "phrases": [{ "type": "phrase", "value": "machine learning" }]
        }
        """)
public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@Valid @NotNull Phrase> phrases,
        @Schema(description = "Optional morphology name; routes to the field's configured morphology destination field")
        String morphology
) implements QueryPayload {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * A single phrase to match. Polymorphic on {@code type}: a {@link StandardPhrase} ({@code "phrase"})
     * runs through the field query parser, while a {@link CapsulePhrase} ({@code "capsule"}) is a Solr-only
     * value that resolves to an external krembox endpoint.
     */
    @JsonDeserialize(using = PhraseDeserializer.class)
    @Schema(description = "A single phrase to match", oneOf = {StandardPhrase.class, CapsulePhrase.class})
    public sealed interface Phrase permits StandardPhrase, CapsulePhrase {
        String type();

        String value();
    }

    @Schema(description = "A standard phrase matched with the field query parser", example = """
            { "type": "phrase", "value": "machine learning", "isExact": false }
            """)
    public record StandardPhrase(
            @NotBlank String value,
            @Schema(description = "When true, morphology is not applied to this phrase even if the query specifies a morphology")
            Boolean isExact
    ) implements Phrase {
        @Override
        @JsonProperty("type")
        public String type() {
            return "phrase";
        }
    }

    @Schema(description = "A Solr-only capsule value of the form {endpointType}_{id}", example = """
            { "type": "capsule", "value": "endpointName_1234" }
            """)
    public record CapsulePhrase(
            @NotBlank String value
    ) implements Phrase {
        @Override
        @JsonProperty("type")
        public String type() {
            return "capsule";
        }
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        return QueryJson.shouldOrSingle(SearchEngine.ELASTICSEARCH, phrases.stream()
                .<JsonNode>map(phrase -> switch (phrase) {
                    case StandardPhrase standard -> JSON.objectNode()
                            .set("match_phrase", JSON.objectNode().put(fieldFor(context, standard), standard.value()));
                    case CapsulePhrase ignored -> throw new QueryTranslationException(
                            "Capsule phrases are only supported on Solr backends");
                })
                .toList());
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        return QueryJson.shouldOrSingle(SearchEngine.SOLR, phrases.stream()
                .<JsonNode>map(phrase -> switch (phrase) {
                    case StandardPhrase standard -> QueryJson.solrFieldQuery(fieldFor(context, standard), standard.value());
                    case CapsulePhrase capsule -> capsuleSolr(context, capsule);
                })
                .toList());
    }

    private String fieldFor(QueryParseContext context, StandardPhrase phrase) {
        String effectiveMorphology = Boolean.TRUE.equals(phrase.isExact()) ? null : morphology;
        return context.requireSearchField("text", effectiveMorphology);
    }

    /**
     * Translates a capsule into a Solr eDisMax query. The capsule {@code value} is {@code {endpointType}_{id}};
     * {@code endpointType} (the part before the last {@code _}) is resolved to a krembox URL via configuration.
     * The emitted query is {@code {baseField}:{"id":...,"altField":...,"kremboxUrl":...}}, where {@code altField}
     * is the morphology destination field when a morphology is set, otherwise the base field.
     */
    private JsonNode capsuleSolr(QueryParseContext context, CapsulePhrase capsule) {
        String value = capsule.value();
        int separator = value.lastIndexOf('_');
        if (separator < 1 || separator == value.length() - 1) {
            throw new QueryTranslationException(
                    "Capsule value '" + value + "' must be of the form '{endpointType}_{id}'");
        }
        String endpointType = value.substring(0, separator);
        String id = value.substring(separator + 1);

        String kremboxUrl = context.vapiEndpoints().get(endpointType);
        if (kremboxUrl == null) {
            throw new QueryTranslationException(
                    "No krembox endpoint configured for capsule endpoint type '" + endpointType + "'");
        }

        String baseField = context.requireSearchField("text");
        String altField = context.requireSearchField("text", morphology);

        ObjectNode capsuleArgs = JSON.objectNode();
        capsuleArgs.put("id", id);
        capsuleArgs.put("altField", altField);
        capsuleArgs.put("kremboxUrl", kremboxUrl);

        ObjectNode root = JSON.objectNode();
        root.putObject("edismax").put("query", baseField + ":" + capsuleArgs);
        return root;
    }

    @ApplicationScoped
    public static class Parser implements QueryPayloadParser {
        @Override
        public String type() {
            return "text";
        }

        @Override
        public TextQuery parse(JsonParser parser, ObjectMapper mapper, ObjectNode node)
                throws JsonProcessingException {
            return mapper.treeToValue(node, TextQuery.class);
        }
    }
}
