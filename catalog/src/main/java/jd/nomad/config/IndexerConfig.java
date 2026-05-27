package jd.nomad.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "indexer")
public interface IndexerConfig {

    Catalog catalog();

    interface Catalog {

        @WithDefault("file")
        CatalogSource source();

        FileSource file();

        EtcdSource etcd();
    }

    interface FileSource {

        String config();

        Optional<String> backends();
    }

    interface EtcdSource {

        @WithDefault("http://localhost:2379")
        List<String> endpoints();

        Map<String, String> mappings();

        Optional<String> backends();
    }
}
