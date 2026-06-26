# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **nomad is a subproject of the monk3 monorepo.** Run Gradle from the repository root with the `:nomad`
> prefix. It targets Java 25 / Quarkus 3.33.1.1 and depends on `project(':catalog')` for its configuration.
> There is no spotless gate (neither `:monk3` nor `:catalog` use one); the legacy `palantir-java-format`
> version is incompatible with JDK 25.

## Build and Development Commands

### Building (from the repository root)
- `./gradlew :nomad:build` - Full build with tests
- `./gradlew :nomad:build -Dquarkus.native.enabled=true` - Native executable build (requires GraalVM)

### Running
- `./gradlew :nomad:quarkusDev` - Run in Quarkus dev mode with live reload. The indexer reads the **shared**
  `config/` files at the repository root via relative paths, so run it from the repo root (or point
  `INDEXER_CATALOG_FILE_CONFIG` / `_BACKENDS` / `_DATASOURCES` at absolute paths).

### Testing
- `./gradlew :nomad:test` - Run unit tests (the test task sets `workingDir = rootDir` so `config/...` resolves)

### Docker Compose (via Taskfile, from `nomad/`)
- `task compose:up` - Start Docker Compose stack (use `PROFILE=<profiles>` to specify profiles)
- `task compose:down` - Stop Docker Compose stack
- `task compose:rebuild` - Rebuild and restart stack
- `task generate:document` - Generate a sample document from `../config/mappings/document.mapping.json`
  (override with `MAPPING=...`, `MULTI=true`, `EXCLUDE_MISSING=true DATASOURCE=s3-audio`)

The compose stack provides infrastructure (kafka/solr/es/mongo/minio/hbase/document-api/replicator); the
indexer itself runs on the host via `quarkusDev` against the shared `config/`.

## Architecture Overview

This is a **reactive, high-throughput indexing pipeline** built with Java 25, Quarkus, Apache Camel, and Mutiny. The system consumes document change events from Kafka, enriches them from external data sources, applies configurable field mappings, and writes results to Elasticsearch or Solr.

### Core Pipeline Flow

```
Kafka → CamelReactiveStreams → Mutiny Multi
  → DocumentFetchService (fetch from datasource)
  → MappingEngine (apply field mappings)
  → IndexSink (write to Elasticsearch/Solr)
```

The pipeline is orchestrated in `IndexingStream.java:48-77`:
1. Kafka events are consumed via Camel Reactive Streams and converted to `IndexEvent` objects
2. Events are deserialized and filtered (invalid events are acknowledged and dropped)
3. Documents are fetched from the configured datasource with controlled concurrency (`pipeline.concurrency`) and prefetch (`pipeline.prefetch`)
4. Field mappings are applied by `MappingEngine` to transform source documents into index-ready commands
5. Commands are submitted to the active `IndexSink` with batching and retry logic

### Configuration Catalog System

The catalog system is now **provided by the shared `:catalog` module** (`jd.nomad.config.catalog.*`,
`jd.nomad.mapping.*`); nomad consumes the *same* configuration as monk3. The same `config/` files drive both
apps (file- or etcd-backed, selected via `indexer.catalog.source`):

- **Catalog** (`config/catalog.json`): per material type — `physical` mapping file, optional `virtual`, default `backend`, optional `routing`.
- **Mappings** (`config/mappings/*.mapping.json`): `primaryKey`, a `root` block, and named subdocument blocks. Each field has a `type` and a single `destinationField` (the physical field name). Indexer-only keys: `sourcing` (per-datasource value extraction), and on subdocument fields `primaryKey` (per-datasource child-id extraction) and `partialUpdate` (per-datasource array op).
- **Backends** (`config/backends.json`): each backend has an `engine` (`SOLR`/`ELASTICSEARCH`), `url`, `index`/`collection`, and optional `zk`/`chroot` (SolrCloud) or `hosts` (ES cluster).
- **Datasources** (`config/datasources.json`): named datasources (kafka-inline, s3, mongodb, rest-api) with a `type` and type-specific settings, wrapped as `{ "datasources": { ... } }`.

The active material type / datasource / (optional) backend override are selected via `indexer.material-type`,
`indexer.data-source`, `indexer.backend` in `application.yaml` (the `IndexingConfig` mapping — renamed from
`IndexerConfig` to avoid clashing with the catalog's own `IndexerConfig`). `ConfigurationCatalogService`
(loaded at startup, hot-reloaded) exposes `mappingForMaterialType`, `backendConfig`, `datasource`, and
`registerListener(Runnable)` for reload callbacks.

### Field Mapping and Data Sourcing

- **Destination field**: a single `destinationField` per field is the physical name sent to the engine (the old per-sink `routing` map is gone — one physical name, no per-engine map).
- **Sourcing**: each field declares per-datasource extraction via `SourceExpression`. Each entry sets exactly one of `jq` (jackson-jq) or `jsonPointer` (RFC 6901), plus optional `partialUpdate` (array op) and `required`. A bare string is shorthand for a required `jq`. Lookups fall back `<datasource>` → `default` → `*`. Note: JSON Pointer cannot iterate a nested array like JQ's `[]`, so multi-valued nested-array alignment requires `jq`.
- **Subdocuments**: `type: subdocument` fields reference another block via `subdocumentType`; child ids come from `primaryKey` sourcing and the per-engine nested-id field name (`item_id` for Solr, `_id` for ES). Indexed as child documents (Solr) or nested arrays (ES).
- **Walking**: `MappingEngine` walks the `SearchMapping` for the active material type; `MappingFieldCollector` collects the top-level source fields (jq root or JSON Pointer first segment) so selective-retrieval datasources fetch only what they need.

### Data Fetchers

All data fetchers implement the `DataFetcher` interface. `DataFetcherDelegate` selects the `DataFetcherFactory` matching the active datasource's `type` (from `ConfigurationCatalogService.datasource(...)`) and re-resolves it on catalog reload:

- `InlineDocumentFetcher` (`kafka-inline`): Extracts `inlineDocument` from the Kafka event payload
- `RestApiDataFetcher` (`rest-api`): Performs HTTP requests with jq-based field selection
- `S3DataFetcher` (`s3`): Fetches JSON objects from S3
- `MongoDataFetcher` (`mongodb`): Queries MongoDB with projection-based field filtering

> **HBase is not supported on this platform.** `camel-quarkus-hbase` has no Quarkus 3.x / Camel 4 release
> (the HBase component was removed in Camel 4), so the HBase fetcher was dropped. A `hbase` datasource entry
> may remain in `datasources.json`, but selecting it yields "Unsupported datasource type 'hbase'".

Fetchers use `MappingFieldCollector` to determine which fields are required by the mapping, enabling selective retrieval when the datasource supports it (MongoDB projections, REST API field parameters).

### Index Sinks

`IndexSinkProducer` selects the `IndexSinkFactory` whose `getEngine()` matches the active backend's
`BackendEngine` (`SOLR`/`ELASTICSEARCH`), builds it from the `BackendConfig`, and re-resolves on catalog reload:

- `ElasticsearchIndexSink` (`ELASTICSEARCH`): reads `hosts` (fallback `url`) + `index`; writes via Camel's Elasticsearch component with `_update` API support
- `SolrIndexSink` (`SOLR`): reads `zk`/`chroot`/`collection` (SolrCloud) or falls back to `url` (HTTP); writes via Camel's Solr component with atomic update semantics

Both sinks support partial updates (`indexer.is-partial`) and nested document indexing. The percolator sink
classes are retained but are no longer wired to a backend engine (the old `SinkDescriptor`-based factory was
removed); `PercolatorResponseParser` remains covered by tests.

### Error Handling and Resilience

- **Deserialization failures**: Logged and acknowledged (event is dropped)
- **Fetch/mapping failures**: Logged and acknowledged (event is dropped)
- **Search engine request errors** (`SearchEngineRequestException`): Logged and acknowledged (malformed command)
- **Search engine unavailability** (`SearchEngineUnavailableException`): Retries with exponential backoff until `searchEngineFailureThreshold` is exceeded, then acknowledges

Retry logic is in `IndexingStream.handleSearchEngineUnavailable()` (lines 134-160).

## Key Extension Points

- **Add a new datasource type**: Implement `DataFetcherFactory` (returning your `type`) + `DataFetcher`, annotate the factory `@ApplicationScoped`; `DataFetcherDelegate` discovers it.
- **Add a new sink engine**: Implement `IndexSinkFactory` (returning the `BackendEngine`) + `IndexSink`, annotate `@ApplicationScoped`; `IndexSinkProducer` discovers it.
- **Modify field mapping logic**: Update `MappingEngine` (walks the catalog `SearchMapping`); add mapping keys in the shared `:catalog` `CatalogSnapshotBuilder` + `mappings.schema.json`.
- **Adjust pipeline throughput**: Tune `indexer.pipeline.concurrency`, `indexer.pipeline.prefetch`, `indexer.pipeline.fetch-batch-size`, and `indexer.pipeline.index-batch-size` in `application.yaml`.

> **Note:** `nomad/.git` is a nested git repository carried over from when nomad was standalone. It is left
> in place (not modified by the monorepo move); be mindful when running git commands inside `nomad/`.
