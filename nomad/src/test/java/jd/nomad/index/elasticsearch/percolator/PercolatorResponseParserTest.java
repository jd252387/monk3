package jd.nomad.index.elasticsearch.percolator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PercolatorResponseParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsIdsFromSearchResponse() {
        Map<String, Object> response = Map.of(
                "hits",
                Map.of(
                        "hits",
                        List.of(
                                Map.of("_id", "query-1", "_score", 1.0),
                                Map.of("_id", "query-2", "_score", 0.9))));

        List<String> ids = PercolatorResponseParser.extractMatchIds(response, objectMapper);

        Assertions.assertEquals(List.of("query-1", "query-2"), ids);
    }

    @Test
    void returnsEmptyListWhenStructureUnexpected() {
        Map<String, Object> response = Map.of("hits", Map.of("total", 0));

        List<String> ids = PercolatorResponseParser.extractMatchIds(response, objectMapper);

        Assertions.assertTrue(ids.isEmpty());
    }

    @Test
    void returnsEmptyListWhenResponseCannotBeConverted() {
        List<String> ids = PercolatorResponseParser.extractMatchIds("unexpected", objectMapper);

        Assertions.assertTrue(ids.isEmpty());
    }
}
