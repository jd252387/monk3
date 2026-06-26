package jd.nomad.index.solr;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.*;

import jd.nomad.index.IndexSink;
import jd.nomad.index.exception.SearchEngineExceptions;
import jd.nomad.model.IndexCommand;
import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.component.solr.SolrComponent;
import org.apache.camel.component.solr.SolrConstants;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.common.SolrInputDocument;

public class SolrIndexSink implements IndexSink {

    private final CamelContext camelContext;
    private final SolrSinkSettings settings;
    private final boolean isPartial;
    private final SolrClient solrClient;

    public SolrIndexSink(CamelContext camelContext, SolrSinkSettings settings, boolean isPartial) {
        this.camelContext = camelContext;
        this.settings = settings;
        this.isPartial = isPartial;

        if (settings.hasZkConnection()) {
            // SolrCloud connection via ZooKeeper.
            CloudSolrClient cloudSolrClient = new CloudSolrClient.Builder(
                            Collections.singletonList(settings.zkConnection()), Optional.of(settings.chroot()))
                    .build();
            // Test connection
            cloudSolrClient.getClusterStateProvider().getLiveNodes();
            this.solrClient = cloudSolrClient;
        } else {
            // Standalone/HTTP connection via base URL.
            this.solrClient = new Http2SolrClient.Builder(settings.baseUrl()).build();
        }

        // Configure Camel Solr component to use this client
        SolrComponent solrComponent = camelContext.getComponent("solr", SolrComponent.class);
        solrComponent.setSolrClient(solrClient);
    }

    @Override
    public Uni<Void> indexBatch(List<IndexCommand> commands) {
        if (commands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        List<Uni<Void>> operations = commands.stream().map(this::processCommand).toList();
        return Uni.combine().all().unis(operations).discardItems();
    }

    private Uni<Void> processCommand(IndexCommand command) {
        if (command.getPrimaryKey() == null) {
            throw new IllegalArgumentException("Primary key is required for Solr update");
        }

        if (command.getFields().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        SolrInputDocument document = new SolrInputDocument();
        document.setField("id", command.getPrimaryKey());

        if (command.getRootId() != null && !command.getRootId().isBlank()) {
            document.setField("_root_", Map.of("set", command.getRootId()));
        }

        // Process all fields uniformly
        for (Map.Entry<String, jd.nomad.model.UpdateField> entry :
                command.getFields().entrySet()) {
            String fieldName = entry.getKey();
            jd.nomad.model.UpdateField updateField = entry.getValue();
            Object value = updateField.value();

            // Determine the operation to use
            String operation;
            if (isPartial && updateField.isPartialUpdate()) {
                // Use the specific partial update operation
                operation = updateField.operation();
            } else {
                // Use "set" for full updates or regular fields
                operation = "set";
            }

            // Wrap the value with the operation
            document.setField(fieldName, Map.of(operation, value));
        }

        return sendToSolr(document);
    }

    private Uni<Void> sendToSolr(SolrInputDocument document) {
        return Uni.createFrom()
                .item(() -> {
                    FluentProducerTemplate template = camelContext
                            .createFluentProducerTemplate()
                            .to("solr://localhost")
                            .withHeader(SolrConstants.PARAM_OPERATION, SolrConstants.OPERATION_INSERT)
                            .withBody(document);

                    template = template.withHeader(SolrConstants.PARAM_COLLECTION, this.settings.collection());

                    try {
                        template.request(Object.class);
                    } catch (Exception e) {
                        throw SearchEngineExceptions.classify(
                                "Failed to index document %s into Solr"
                                        .formatted(document.getFieldValue("id")),
                                e);
                    }
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }
}
