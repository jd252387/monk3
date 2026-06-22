package jd.nomad.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BackendConfig(
        @JsonProperty BackendEngine engine,
        @JsonProperty URI url,
        @JsonProperty String index,
        @JsonProperty String collection,
        @JsonProperty String primaryKey,
        @JsonProperty String physical,
        @JsonProperty String virtual,
        @JsonProperty int defaultSize,
        // Indexer-side connection details for clustered sinks; the query side (monk3) ignores these.
        @JsonProperty String zk,
        @JsonProperty String chroot,
        @JsonProperty List<URI> hosts
) {
    public BackendConfig {
        if (defaultSize <= 0) defaultSize = 10;
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
    }
}
