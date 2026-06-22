package jd.nomad.config.catalog;

/**
 * Receives configuration updates pushed by a {@link CatalogDatastore} while the application is running.
 *
 * <p>A datastore rebuilds the whole {@link CatalogSnapshot} on any change and replaces it atomically.
 */
public interface CatalogUpdateSink {

    void replaceSnapshot(CatalogSnapshot snapshot);
}
