package jd.nomad.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;

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

        Map<String, String> mappings();

        @WithDefault("{}")
        Map<String, String> virtualMappings();
    }

    interface EtcdSource {

        @WithDefault("http://localhost:2379")
        List<String> endpoints();

        Map<String, String> mappings();
    }
}
