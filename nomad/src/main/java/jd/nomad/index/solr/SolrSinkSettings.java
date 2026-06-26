package jd.nomad.index.solr;

/**
 * Solr sink connection settings. A SolrCloud sink sets {@code zkConnection} (+ optional {@code chroot}); a
 * standalone/HTTP sink leaves {@code zkConnection} blank and sets {@code baseUrl} instead.
 */
public record SolrSinkSettings(String zkConnection, String collection, String chroot, String baseUrl) {

    public boolean hasZkConnection() {
        return zkConnection != null && !zkConnection.isBlank();
    }
}
