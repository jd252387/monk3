package jd.nomad.index;

import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.BackendEngine;
import org.apache.camel.CamelContext;

public interface IndexSinkFactory {

    /**
     * Returns the backend engine this factory supports.
     */
    BackendEngine getEngine();

    /**
     * Creates an IndexSink instance for the given backend configuration.
     *
     * @param backend      the backend configuration (connection details for the sink)
     * @param camelContext the Camel context for routing/integration
     * @return a configured IndexSink instance
     */
    IndexSink create(BackendConfig backend, CamelContext camelContext);
}
