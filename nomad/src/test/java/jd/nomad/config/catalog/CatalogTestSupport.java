package jd.nomad.config.catalog;

import jd.nomad.config.CatalogSource;
import jd.nomad.config.IndexerConfig;
import jd.nomad.config.IndexingConfig;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test helper that loads the repository's shared {@code config/} files (catalog, mappings, backends,
 * datasources) into a live {@link ConfigurationCatalogService}, plus a minimal {@link IndexingConfig} for the
 * indexer beans under test. Nomad unit tests run with the working directory set to the repository root, so the
 * relative {@code config/...} paths resolve as they do at runtime. Lives in {@code jd.nomad.config.catalog} so
 * it can stop the datastore's file monitor after capturing the initial snapshot.
 */
public final class CatalogTestSupport {

    private CatalogTestSupport() {
    }

    public static ConfigurationCatalogService loadRepositoryCatalog() throws Exception {
        CatalogSnapshotBuilder builder = new CatalogSnapshotBuilder();
        FileCatalogDatastore datastore = new FileCatalogDatastore(catalogIndexerConfig(), builder);
        CatalogSnapshot snapshot = datastore.start(NO_OP_SINK);
        datastore.stop();

        ConfigurationCatalogService service = new ConfigurationCatalogService(null);
        Field snapshotField = ConfigurationCatalogService.class.getDeclaredField("snapshot");
        snapshotField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<CatalogSnapshot> reference = (AtomicReference<CatalogSnapshot>) snapshotField.get(service);
        reference.set(snapshot);
        return service;
    }

    /** A minimal indexer config for the given material type and datasource (no backend override, full updates). */
    public static IndexingConfig indexingConfig(String materialType, String datasource) {
        return new IndexingConfig() {
            @Override
            public Kafka kafka() {
                return null;
            }

            @Override
            public Pipeline pipeline() {
                return null;
            }

            @Override
            public String materialType() {
                return materialType;
            }

            @Override
            public String dataSource() {
                return datasource;
            }

            @Override
            public Optional<String> backend() {
                return Optional.empty();
            }

            @Override
            public boolean isPartial() {
                return false;
            }

            @Override
            public Optional<PartialUpdateHierarchy> partialUpdateHierarchy() {
                return Optional.empty();
            }
        };
    }

    private static IndexerConfig catalogIndexerConfig() {
        IndexerConfig.FileSource fileSource = new IndexerConfig.FileSource() {
            @Override
            public Optional<String> config() {
                return Optional.of("config/catalog.json");
            }

            @Override
            public Optional<String> backends() {
                return Optional.of("config/backends.json");
            }

            @Override
            public Optional<String> datasources() {
                return Optional.of("config/datasources.json");
            }
        };
        IndexerConfig.Catalog catalog = new IndexerConfig.Catalog() {
            @Override
            public CatalogSource source() {
                return CatalogSource.FILE;
            }

            @Override
            public IndexerConfig.FileSource file() {
                return fileSource;
            }

            @Override
            public IndexerConfig.EtcdSource etcd() {
                return null;
            }
        };
        return () -> catalog;
    }

    private static final CatalogUpdateSink NO_OP_SINK = new CatalogUpdateSink() {
        @Override
        public void replaceSnapshot(CatalogSnapshot snapshot) {
        }
    };
}
