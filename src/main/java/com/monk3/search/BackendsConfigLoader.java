package com.monk3.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.mapping.BackendConfigEntry;
import com.monk3.mapping.SearchMappingConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class BackendsConfigLoader {
    private final SearchMappingConfig config;
    private final ObjectMapper objectMapper;

    private Map<String, SearchMappingConfig.Backend> backends;

    void onStartup(@Observes StartupEvent event) throws IOException {
        backends = load(config.backendsFile());
    }

    public Map<String, SearchMappingConfig.Backend> backends() {
        return backends;
    }

    private Map<String, SearchMappingConfig.Backend> load(String location) throws IOException {
        Path path = resolveAbsolute(location);
        try (InputStream in = Files.newInputStream(path)) {
            BackendsFile file = objectMapper.readValue(in, BackendsFile.class);
            return Map.copyOf(new LinkedHashMap<>(file.backends()));
        }
    }

    private static Path resolveAbsolute(String location) {
        return Paths.get(location).toAbsolutePath();
    }

    private record BackendsFile(
        @JsonDeserialize(contentAs = BackendConfigEntry.class)
        Map<String, SearchMappingConfig.Backend> backends
    ) {}
}
