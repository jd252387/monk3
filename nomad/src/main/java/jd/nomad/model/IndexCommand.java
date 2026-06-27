package jd.nomad.model;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IndexCommand {

    String primaryKey;

    String rootId;

    @Builder.Default
    Map<String, UpdateField> fields = new HashMap<>();

    public Map<String, UpdateField> getFields() {
        return fields == null ? Map.of() : fields;
    }
}
