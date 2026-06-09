package jd.nomad.config.catalog;

public interface CatalogDatastore {

    CatalogSnapshot start(CatalogUpdateSink sink) throws Exception;
}
