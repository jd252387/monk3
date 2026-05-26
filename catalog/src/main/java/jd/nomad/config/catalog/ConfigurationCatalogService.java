package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ConfigurationCatalogService {
    private final List<Runnable> updateListeners = Collections.synchronizedList(new ArrayList<>());
    private final CatalogSnapshotBuilder snapshotBuilder;
    private final Instance<CatalogDatastore> datastoreInstance;
    private final AtomicReference<CatalogSnapshot> snapshot = new AtomicReference<>();

    void onStartup(@Observes StartupEvent event) throws Exception {
        snapshot.set(datastoreInstance.get().start(this::updateMapping));
    }

    public void registerListener(Runnable callback) {
        updateListeners.add(callback);
    }

    public SearchMapping mappingForMaterialType(String materialType) {
        return Optional.ofNullable(snapshot.get().mappings().get(materialType))
                .orElseThrow(() -> new IllegalStateException(
                        "No mapping is configured for material type '" + materialType + "'"));
    }

    public Map<String, SearchMapping> mappings() {
        return snapshot.get().mappings();
    }

    public Optional<VirtualMapping> virtualMappingForMaterialType(String materialType) {
        return Optional.ofNullable(snapshot.get().virtualMappings().get(materialType));
    }

    private synchronized void updateMapping(String materialType, JsonNode updatedNode) {
        SearchMapping updated = snapshotBuilder.parseMapping(materialType, updatedNode);
        this.snapshot.getAndUpdate(currentSnapshot -> {
            Map<String, SearchMapping> next = new LinkedHashMap<>(currentSnapshot.mappings());
            next.put(materialType, updated);
            return new CatalogSnapshot(Map.copyOf(next), currentSnapshot.virtualMappings());
        });

        for (Runnable listener : List.copyOf(updateListeners)) {
            listener.run();
        }

        log.atInfo().addArgument(materialType).log("Reloaded mapping for material type {}");
    }
}
