package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import jd.nomad.routing.RoutingRule;
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
public class ConfigurationCatalogService implements CatalogUpdateSink {
    private final List<Runnable> updateListeners = Collections.synchronizedList(new ArrayList<>());
    private final CatalogSnapshotBuilder snapshotBuilder;
    private final Instance<CatalogDatastore> datastoreInstance;
    private final AtomicReference<CatalogSnapshot> snapshot = new AtomicReference<>();

    void onStartup(@Observes StartupEvent event) throws Exception {
        snapshot.set(datastoreInstance.get().start(this));
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

    public String backendForMaterialType(String materialType) {
        return Optional.ofNullable(snapshot.get().backendsByMaterialType().get(materialType))
                .orElseThrow(() -> new IllegalStateException(
                        "No backend is configured for material type '" + materialType + "'"));
    }

    public List<RoutingRule> routingRulesForMaterialType(String materialType) {
        List<RoutingRule> rules = snapshot.get().routingRulesByMaterialType().get(materialType);
        return rules != null ? rules : List.of();
    }

    public BackendConfig backendConfig(String name) {
        return Optional.ofNullable(snapshot.get().backends().get(name))
                .orElseThrow(() -> new IllegalStateException(
                        "No backend configuration found for backend '" + name + "'"));
    }

    @Override
    public synchronized void replaceSnapshot(CatalogSnapshot newSnapshot) {
        snapshot.set(newSnapshot);

        for (Runnable listener : List.copyOf(updateListeners)) {
            listener.run();
        }

        log.atInfo().log("Reloaded catalog snapshot");
    }

    @Override
    public synchronized void updateMapping(String materialType, JsonNode updatedNode) {
        SearchMapping updated = snapshotBuilder.parseMapping(materialType, updatedNode);
        this.snapshot.getAndUpdate(currentSnapshot -> {
            Map<String, SearchMapping> next = new LinkedHashMap<>(currentSnapshot.mappings());
            next.put(materialType, updated);
            return new CatalogSnapshot(
                    Map.copyOf(next),
                    currentSnapshot.virtualMappings(),
                    currentSnapshot.backendsByMaterialType(),
                    currentSnapshot.routingRulesByMaterialType(),
                    currentSnapshot.backends());
        });

        for (Runnable listener : List.copyOf(updateListeners)) {
            listener.run();
        }

        log.atInfo().addArgument(materialType).log("Reloaded mapping for material type {}");
    }

    @Override
    public synchronized void updateBackends(Map<String, BackendConfig> updatedBackends) {
        snapshot.getAndUpdate(current -> new CatalogSnapshot(
                current.mappings(),
                current.virtualMappings(),
                current.backendsByMaterialType(),
                current.routingRulesByMaterialType(),
                Map.copyOf(updatedBackends)));

        for (Runnable listener : List.copyOf(updateListeners)) {
            listener.run();
        }

        log.atInfo().log("Reloaded backends configuration");
    }
}
