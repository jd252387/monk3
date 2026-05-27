package jd.nomad.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BackendConfig(
        @JsonProperty BackendEngine engine,
        @JsonProperty URI url,
        @JsonProperty String index,
        @JsonProperty String collection,
        @JsonProperty int defaultSize
) {
    public BackendConfig {
        if (defaultSize <= 0) defaultSize = 10;
    }
}
