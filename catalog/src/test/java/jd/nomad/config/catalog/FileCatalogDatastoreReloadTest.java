package jd.nomad.config.catalog;

import jd.nomad.config.CatalogSource;
import jd.nomad.config.IndexerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises hot-reload of the file-backed catalog: editing a mapping file or {@code catalog.json} on disk
 * must replace the live {@link CatalogSnapshot}, while an invalid edit retains the last-good configuration.
 *
 * <p>Mappings are loaded from each backend's {@code physical} file (declared in {@code backends.json});
 * {@code catalog.json} only routes material types to backends. The default
 * {@link org.apache.commons.vfs2.impl.DefaultFileMonitor} poll interval is ~1s, so the tests mutate files
 * after a short settle and then await the resulting snapshot.
 */
class FileCatalogDatastoreReloadTest {

    private static final Duration AWAIT = Duration.ofSeconds(15);
    private static final long MONITOR_SETTLE_MILLIS = 1_100;

    @TempDir
    Path tempDir;

    private FileCatalogDatastore datastore;

    @AfterEach
    void tearDown() {
        if (datastore != null) {
            datastore.stop();
        }
    }

    @Test
    void physicalMappingChangeReloadsSnapshot() throws Exception {
        Path bookMapping = writeFile("book.mapping.json", mappingJson("title_s"));
        Path backends = writeFile("backends.json", backendsJson(Map.of("es", bookMapping)));
        Path catalog = writeFile("catalog.json", catalogJson("book", "es"));

        CapturingSink sink = new CapturingSink();
        CatalogSnapshot initial = start(catalog, backends, sink);
        assertEquals("title_s", destinationField(initial, "es"), "sanity: initial mapping");

        Thread.sleep(MONITOR_SETTLE_MILLIS);
        writeFile("book.mapping.json", mappingJson("title_t"));

        await(() -> sink.latest.get() != null && "title_t".equals(destinationField(sink.latest.get(), "es")));
        assertEquals("title_t", destinationField(sink.latest.get(), "es"));
    }

    @Test
    void catalogJsonChangeAddsMaterialType() throws Exception {
        Path bookMapping = writeFile("book.mapping.json", mappingJson("title_s"));
        Path articleMapping = writeFile("article.mapping.json", mappingJson("headline_s"));
        Path backends = writeFile("backends.json", backendsJson(Map.of(
                "es", bookMapping,
                "es-articles", articleMapping)));
        Path catalog = writeFile("catalog.json", catalogJson("book", "es"));

        CapturingSink sink = new CapturingSink();
        start(catalog, backends, sink);

        Thread.sleep(MONITOR_SETTLE_MILLIS);
        writeFile("catalog.json", catalogJson("book", "es", "article", "es-articles"));

        await(() -> sink.latest.get() != null
                && "es-articles".equals(sink.latest.get().backendsByMaterialType().get("article")));
        CatalogSnapshot snapshot = sink.latest.get();
        assertEquals("headline_s", destinationField(snapshot, "es-articles"));
        assertEquals("es-articles", snapshot.backendsByMaterialType().get("article"));
    }

    @Test
    void invalidEditRetainsLastGoodConfiguration() throws Exception {
        Path bookMapping = writeFile("book.mapping.json", mappingJson("title_s"));
        Path backends = writeFile("backends.json", backendsJson(Map.of("es", bookMapping)));
        Path catalog = writeFile("catalog.json", catalogJson("book", "es"));

        CapturingSink sink = new CapturingSink();
        start(catalog, backends, sink);

        Thread.sleep(MONITOR_SETTLE_MILLIS);
        writeFile("book.mapping.json", mappingJson("title_t"));
        await(() -> sink.latest.get() != null && "title_t".equals(destinationField(sink.latest.get(), "es")));
        int goodReloadCount = sink.replaceCount.get();

        // A malformed mapping must NOT clobber the live snapshot.
        writeFile("book.mapping.json", "{ this is not valid json");

        Thread.sleep(2_500);
        assertEquals(goodReloadCount, sink.replaceCount.get(), "invalid edit should not push a snapshot");
        assertEquals("title_t", destinationField(sink.latest.get(), "es"), "last-good config retained");
    }

    private CatalogSnapshot start(Path catalog, Path backends, CapturingSink sink) throws Exception {
        datastore = new FileCatalogDatastore(
                fileIndexerConfig(catalog.toString(), backends.toString()), new CatalogSnapshotBuilder());
        return datastore.start(sink);
    }

    private Path writeFile(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static String destinationField(CatalogSnapshot snapshot, String backend) {
        return snapshot.mappingsByBackend().get(backend).root().field("title")
                .or(() -> snapshot.mappingsByBackend().get(backend).root().field("headline"))
                .orElseThrow()
                .searchField();
    }

    private static String mappingJson(String destinationField) {
        String logical = destinationField.startsWith("headline") ? "headline" : "title";
        return """
                {
                  "root": {
                    "%s": { "type": "string", "destinationField": "%s" }
                  }
                }
                """.formatted(logical, destinationField);
    }

    private static String backendsJson(Map<String, Path> physicalByBackend) {
        String entries = physicalByBackend.entrySet().stream()
                .map(entry -> """
                        "%s": { "engine": "ELASTICSEARCH", "url": "http://localhost:9200", "index": "%s", "primaryKey": "id", "physical": "%s" }"""
                        .formatted(entry.getKey(), entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(",\n"));
        return "{ \"backends\": {\n" + entries + "\n} }";
    }

    private static String catalogJson(String materialType, String backend) {
        return """
                {
                  "mappings": {
                    "%s": { "backend": "%s" }
                  }
                }
                """.formatted(materialType, backend);
    }

    private static String catalogJson(String firstType, String firstBackend,
                                      String secondType, String secondBackend) {
        return """
                {
                  "mappings": {
                    "%s": { "backend": "%s" },
                    "%s": { "backend": "%s" }
                  }
                }
                """.formatted(firstType, firstBackend, secondType, secondBackend);
    }

    private static IndexerConfig fileIndexerConfig(String configPath, String backendsPath) {
        IndexerConfig.FileSource fileSource = new IndexerConfig.FileSource() {
            @Override
            public Optional<String> config() {
                return Optional.of(configPath);
            }

            @Override
            public Optional<String> backends() {
                return Optional.of(backendsPath);
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

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Condition not met within " + AWAIT);
    }

    private static final class CapturingSink implements CatalogUpdateSink {
        private final AtomicReference<CatalogSnapshot> latest = new AtomicReference<>();
        private final AtomicInteger replaceCount = new AtomicInteger();

        @Override
        public void replaceSnapshot(CatalogSnapshot snapshot) {
            latest.set(snapshot);
            replaceCount.incrementAndGet();
        }
    }
}
