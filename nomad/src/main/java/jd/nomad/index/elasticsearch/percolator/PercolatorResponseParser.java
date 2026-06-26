package jd.nomad.index.elasticsearch.percolator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PercolatorResponseParser {

    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<>() {};

    private PercolatorResponseParser() {}

    public static List<String> extractMatchIds(Object response, ObjectMapper objectMapper) {
        if (response == null || objectMapper == null) {
            return List.of();
        }
        Map<String, Object> responseMap;
        try {
            responseMap = objectMapper.convertValue(response, RESPONSE_TYPE);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        Object hitsNode = responseMap.get("hits");
        if (!(hitsNode instanceof Map<?, ?> hitsMap)) {
            return List.of();
        }
        Object innerHitsNode = hitsMap.get("hits");
        if (!(innerHitsNode instanceof List<?> innerHits)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(innerHits.size());
        for (Object hitObj : innerHits) {
            if (hitObj instanceof Map<?, ?> hitMap) {
                Object id = hitMap.get("_id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
        }
        return ids;
    }
}
