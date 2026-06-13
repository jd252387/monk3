package jd.nomad.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "indexer")
public interface IndexerConfig {

    Catalog catalog();

    interface Catalog {

        @WithDefault("FILE")
        CatalogSource source();

        FileSource file();

        EtcdSource etcd();
    }

    interface FileSource {

        Optional<String> config();

        Optional<String> backends();
    }

    interface EtcdSource {

        @WithDefault("http://localhost:2379")
        List<String> endpoints();

        /** Etcd key holding the catalog document (same shape as the file-source {@code catalog.json}). */
        Optional<String> catalog();

        /** Optional etcd key holding the backends document (same shape as {@code backends.json}). */
        Optional<String> backends();
    }
}
