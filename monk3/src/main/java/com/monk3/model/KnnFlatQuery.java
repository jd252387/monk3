package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.NotBlank;
import jd.nomad.mapping.MappedField;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A k-nearest-neighbour vector query. The free {@code text} is sent to the embedding API to obtain a dense
 * vector, which is searched against a {@code vector} field's family of physical fields (its {@code %i}
 * template expanded over {@code start..end}). The per-field similarity clauses are combined as a max-score
 * query: an Elasticsearch {@code dis_max} of {@code knn} clauses, or a Solr {@code {!maxscore}} of
 * {@code {!knn}} subqueries.
 */
@Schema(description = "A vector (kNN) query that embeds free text and searches a vector field's physical fields", example = """
        {
          "type": "knnFlat",
          "text": "machine learning",
          "k": 10
        }
        """)
public record KnnFlatQuery(
        @NotBlank String text,
        @Schema(description = "Number of nearest neighbours per field; defaults to 10 when omitted")
        Integer k
) implements QueryPayload {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int DEFAULT_K = 10;

    @JsonProperty
    public String type() {
        return "knnFlat";
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        MappedField field = context.requireVectorField("knnFlat");
        JsonNode vector = context.embeddingClient().embed(text);
        int neighbors = effectiveK();

        ObjectNode root = JSON.objectNode();
        ArrayNode queries = root.putObject("dis_max").putArray("queries");
        for (String vectorField : field.vectorFields()) {
            ObjectNode disjunct = JSON.objectNode();
            ObjectNode knn = disjunct.putObject("knn");
            knn.put("field", vectorField);
            knn.set("query_vector", vector.deepCopy());
            knn.put("k", neighbors);
            queries.add(disjunct);
        }
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        MappedField field = context.requireVectorField("knnFlat");
        JsonNode vector = context.embeddingClient().embed(text);
        int neighbors = effectiveK();
        String vectorLiteral = solrVectorLiteral(vector);

        String query = field.vectorFields().stream()
                .map(vectorField -> "({!knn f=" + vectorField + " topK=" + neighbors + "}" + vectorLiteral + ")")
                .collect(Collectors.joining(" "));

        ObjectNode root = JSON.objectNode();
        root.putObject("maxscore").put("query", query);
        return root;
    }

    private int effectiveK() {
        return k != null ? k : DEFAULT_K;
    }

    /** Renders the embedding vector as a Solr knn literal, e.g. {@code [0.12,0.56,...]}. */
    private static String solrVectorLiteral(JsonNode vector) {
        return StreamSupport.stream(vector.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
