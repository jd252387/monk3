package jd.nomad.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Indexer runtime configuration. The {@code indexer.catalog.*} subtree (catalog source, file/etcd locations)
 * is owned by the shared {@code :catalog} module's {@code jd.nomad.config.IndexerConfig}; this interface maps
 * the disjoint indexer-specific subtree (Kafka, pipeline, active material type / datasource / backend). It is
 * named {@code IndexingConfig} to avoid clashing with the catalog's identically-prefixed {@code IndexerConfig}.
 */
@ConfigMapping(prefix = "indexer")
public interface IndexingConfig {

    Kafka kafka();

    Pipeline pipeline();

    /** Material type whose mapping this indexer instance applies (selects which catalog mapping to use). */
    String materialType();

    /** Name of the datasource (from the catalog) this indexer fetches documents from. */
    String dataSource();

    /** Optional backend override; when absent the material type's default backend from the catalog is used. */
    Optional<String> backend();

    @WithDefault("false")
    boolean isPartial();

    @WithName("partial-update-hierarchy")
    Optional<PartialUpdateHierarchy> partialUpdateHierarchy();

    interface Kafka {

        String topic();

        String bootstrapServers();

        @WithDefault("indexer")
        String groupId();

        @WithDefault("500")
        int maxPollRecords();

        @WithDefault("PT5M")
        Duration maxPollInterval();
    }

    interface Pipeline {

        @WithDefault("8")
        int concurrency();

        @WithDefault("32")
        int prefetch();

        @WithDefault("32")
        int fetchBatchSize();

        @WithDefault("100")
        int indexBatchSize();

        @WithDefault("PT5S")
        Duration indexFlushInterval();

        @WithDefault("PT5S")
        Duration searchEngineRetryInterval();

        @WithDefault("PT30M")
        Duration searchEngineFailureThreshold();
    }

    interface PartialUpdateHierarchy {

        List<String> path();
    }
}
