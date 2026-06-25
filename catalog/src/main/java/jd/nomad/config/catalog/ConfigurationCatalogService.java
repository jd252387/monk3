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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ConfigurationCatalogService implements CatalogUpdateSink {
    private final List<Runnable> updateListeners = Collections.synchronizedList(new ArrayList<>());
    private final Instance<CatalogDatastore> datastoreInstance;
    private final AtomicReference<CatalogSnapshot> snapshot = new AtomicReference<>();

    void onStartup(@Observes StartupEvent event) throws Exception {
        snapshot.set(datastoreInstance.get().start(this));
    }

    public void registerListener(Runnable callback) {
        updateListeners.add(callback);
    }

    /** Mapping bound to a backend; used by the query side (monk3) which resolves a query to a backend. */
    public SearchMapping mappingForBackend(String backend) {
        return Optional.ofNullable(snapshot.get().mappingsByBackend().get(backend))
                .orElseThrow(() -> new IllegalStateException(
                        "No mapping is configured for backend '" + backend + "'"));
    }

    /** Mapping for a material type; used by the indexer (nomad) via the material type's default backend. */
    public SearchMapping mappingForMaterialType(String materialType) {
        return mappingForBackend(backendForMaterialType(materialType));
    }

    public Optional<VirtualMapping> virtualMappingForBackend(String backend) {
        return Optional.ofNullable(snapshot.get().virtualMappingsByBackend().get(backend));
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

    /** Optional filter (a monk3 DSL {@code QueryNode}) restricting results for a material type. */
    public Optional<JsonNode> filterForMaterialType(String materialType) {
        return Optional.ofNullable(snapshot.get().filtersByMaterialType().get(materialType));
    }

    public BackendConfig backendConfig(String name) {
        return Optional.ofNullable(snapshot.get().backends().get(name))
                .orElseThrow(() -> new IllegalStateException(
                        "No backend configuration found for backend '" + name + "'"));
    }

    public DatasourceDescriptor datasource(String name) {
        return Optional.ofNullable(snapshot.get().datasources().get(name))
                .orElseThrow(() -> new IllegalStateException(
                        "No datasource is configured with name '" + name + "'"));
    }

    public Map<String, DatasourceDescriptor> datasources() {
        return snapshot.get().datasources();
    }

    @Override
    public synchronized void replaceSnapshot(CatalogSnapshot newSnapshot) {
        snapshot.set(newSnapshot);

        for (Runnable listener : List.copyOf(updateListeners)) {
            listener.run();
        }

        log.atInfo().log("Reloaded catalog snapshot");
    }
}
