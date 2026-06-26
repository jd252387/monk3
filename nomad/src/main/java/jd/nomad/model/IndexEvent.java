package jd.nomad.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexEvent {

    @JsonProperty(value = "primaryKey", required = true)
    @NotBlank
    private String primaryKey;

    /**
     * Optional identifier used to fetch the document from an external data source.
     * Falls back to {@link #primaryKey} when omitted or blank.
     */
    @JsonProperty("datasourceKey")
    private String datasourceKey;

    /**
     * Present only when the document is fully provided in the Kafka payload.
     */
    @JsonProperty("inlineDocument")
    private JsonNode inlineDocument;

    @JsonProperty("_root_")
    private String rootId;

    @JsonIgnore
    public String getDatasourceKeyOrPrimary() {
        if (datasourceKey == null || datasourceKey.isBlank()) {
            return primaryKey;
        }
        return datasourceKey;
    }

    public String getInlineDocumentBody() {
        return inlineDocument == null ? null : inlineDocument.toString();
    }
}
