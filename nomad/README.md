# Nomad
![img (Small).png](img%20%28Small%29.png)

This project provides a high-throughput indexing pipeline built with **Java 21**, **Quarkus**, **Apache Camel (Camel Quarkus)**, and **Mutiny**. It consumes document change events from Kafka, enriches them from external data sources, applies configurable field mappings (including flattening and nested document rules), and writes the results to either Elasticsearch or Solr. Partial updates are supported for both targets via Elasticsearch's `_update` API and Solr atomic update semantics. The application can also be compiled into a native executable using GraalVM.

## Features

- Reactive backpressure-aware processing using Mutiny and Camel Reactive Streams.
- Configurable batching, concurrency, and flush intervals for each stage of the pipeline.
- Pluggable data source fetchers:
  - Inline payload (Kafka message contains the full JSON document).
  - REST API fetch using Camel's HTTP component.
  - HBase fetch with JSON and raw string handling.
  - Amazon S3 object fetch (JSON documents stored in S3).
  - MongoDB fetch with JSON or string parsing.
- Field mapping engine supporting:
  - Generic field remapping (`source.field -> targetField`).
  - Flattening of arrays via dotted paths containing `$`.
  - Nested document extraction with their own mapping rules.
- Per-field partial update control, automatically generating Elasticsearch update requests and Solr atomic updates.
- Nested document indexing for both targets (child documents in Solr, nested arrays in Elasticsearch).
- Gradle-based build with Lombok and Logback.

## Architecture Overview

```
Kafka -> Camel Kafka component -> Camel Reactive Streams -> Mutiny pipeline
      -> Data fetchers (Camel HTTP/HBase/S3/Mongo) -> Mapping Engine
      -> Index sinks (Elasticsearch or Solr via Camel Quarkus components)
```

Each Kafka event is converted into an `IndexEvent`. The pipeline determines which source fields are required based on the configured mappings, fetches the necessary data from the configured data source, applies mappings/flatten/nested rules, and batches the resulting `IndexCommand`s before sending them to the configured index target.

## Event Schema

Kafka messages contain JSON payloads with a required `primaryKey` field, an optional `datasourceKey` used when fetching the document from an external data source, and, when available, an `inlineDocument` object containing the full document. When `inlineDocument` is omitted or null the fetchers rely on the configured external data source to load the document using the `datasourceKey` (falling back to `primaryKey` when not provided). The search backend (Elasticsearch or Solr) is selected globally via the `indexer.target` application property (expected values: `elasticsearch` or `solr`).

```
Kafka payload:
{
  "primaryKey": "user-42",
  "datasourceKey": "doc-4242",
  "inlineDocument": {
    "id": "user-42",
    "profile": {
      "fullName": "Ada Lovelace",
      "country": "UK"
    }
  }
}
```

## Data Source Configuration

Each data source implementation expects specific options:

| Type | Required Options | Behaviour |
| ---- | ---------------- | --------- |
| `NONE` | – | Uses `inlineDocument` from the event. |
| `REST_API` | `url` or `reference` | Performs a GET request; required fields from the mapping configuration are appended to the query string (`fieldsParam`, default `fields`). Headers prefixed with `header.` are forwarded. |
| `HBASE` | `table`, optional `family` | Uses row id from `reference`. Cell payloads are parsed as JSON when possible; otherwise treated as strings. |
| `S3` | `bucket` | Fetches the object whose key matches `reference`. |
| `MONGODB` | `database`, `collection`, optional `client` bean name | Performs `findById` using `reference`. Projections are built from required fields. String values are parsed as JSON when possible. |

All fetchers request the fields inferred from the mapping configuration whenever the backing data source supports selective field retrieval.

## Mapping Configuration

Mappings are defined in [`src/main/resources/mapping/document.yaml`](src/main/resources/mapping/document.yaml) under `indexer.mapping.document.fields`. Each key represents the source JSON path, and its value describes how the extracted content should be indexed. Paths containing the `$` segment are automatically flattened into lists (e.g. `orders.$.items.$.sku`). The location of the mapping file can be customised with the `indexer.mapping-location` property (defaults to `mapping/document.yaml`).

Field options:

- `target` – Destination field name in the index (required).
- `type` – JSON scalar type expected for the field (`string`, `number`, or `boolean`).
- `partial-update` – When `true`, the field is treated as a partial update in Solr while still being merged with regular fields in a single Elasticsearch or Solr update request.
- `multi-value` – When `true`, multiple values are allowed and will be indexed as a list; otherwise multiple values trigger a mapping error.
- `allow-missing` – When `true`, the field may be absent or null in the source document without failing the event.
- `document` – Optional nested mapping containing its own `fields` map. Nested mappings can themselves contain nested entries, allowing arbitrarily deep document hierarchies.

See [`src/main/resources/application.yaml`](src/main/resources/application.yaml) and [`src/main/resources/mapping/document.yaml`](src/main/resources/mapping/document.yaml) for sample configuration.

## Seeding Data Sources in Docker Compose

The Docker Compose environment now exposes a [`documents/`](documents) directory. Place one or more `.json` files in this
folder before running `docker compose`. Each JSON file can contain a single document object or an array of objects. The
`document-api` FastAPI service reads directly from this mount and serves documents through `GET /documents/{id}`, so REST
clients always observe the latest payload without waiting for replication. When any of the `rest`, `mongo`, `s3`, or `hbase`
profiles are active, the `documents-replicator` service will automatically:

- Mirror the same payloads into MongoDB (collection `documents`).
- Upload `<document-id>.json` objects to the MinIO bucket configured for S3 access.
- Store the JSON payload under the `data:json` column in the HBase `documents` table.
- Emit a Kafka indexing event for each document once replication succeeds (when Kafka is reachable).

The replicator now loads its configuration from `document-sync/config/settings.toml`. Docker Compose mounts that file into the container so the defaults in source control are always respected. To change which targets are active, update the `data_sources.enabled` list (or create a personal `document-sync/config/settings.local.toml` that overrides only the keys you care about). Compose still honours the `REPLICATOR_ONLINE_DATA_SOURCES` and `REPLICATOR_EVENT_DATA_SOURCE` environment variables if you prefer to toggle behaviour without editing the file.

Each document **must** include a `primaryKey` field. The replicator validates its presence, uses the value as the identifier for every target data source, and publishes the same primary key within the Kafka payload after replication (default topic `documents`). Adjust Kafka and other connection settings in the same Dynaconf file (or with matching environment overrides) before starting the stack.

## Running Locally

1. **Configure environment** – Update `application.yaml` with Kafka brokers, target endpoints, and mapping rules. Ensure Camel components for external systems (HTTP, HBase, AWS S3, MongoDB, Elasticsearch, Solr) have the required credentials available via Quarkus configuration or environment variables.
2. **Build** – `./gradlew clean build`
3. **Run (dev mode)** – `./gradlew quarkusDev`

Native executables can be produced with `./gradlew build -Dquarkus.package.type=native`. When GraalVM isn't installed locally you can enable containerised native compilation with `-Dquarkus.native.container-build=true`.

## Building and Testing

```bash
./gradlew clean build
```

Unit tests can be added under `src/test/java`. Quarkus JUnit 5 support is available for integration testing.

## Extending the Pipeline

- Add new data sources by implementing `DataFetcher` and exposing them as CDI beans (e.g. `@ApplicationScoped`).
- Add new index targets by implementing `IndexSink` and guarding the bean with `@IfBuildProperty(name = "indexer.target", stringValue = "<value>")`.
- Adjust concurrency, prefetch, and batching through `indexer.pipeline` properties without redeploying code.

## License

Apache License 2.0.
