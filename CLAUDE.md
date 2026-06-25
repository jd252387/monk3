# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run in dev mode (hot reload) — the app lives in the :monk3 subproject
./gradlew :monk3:quarkusDev

# Run all tests
./gradlew test

# Run a single test class
./gradlew :monk3:test --tests "com.monk3.QueryResourceTest"

# Run a single test method
./gradlew :monk3:test --tests "com.monk3.QueryResourceTest.parsesTextQueryToElasticsearchDslUsingConfiguredMapping"
```

## Architecture

monk3 is a Quarkus REST service (Java 25) that accepts a custom search query DSL and translates it into Elasticsearch or Solr query syntax, optionally executing searches across configured backends.

### Package layout

`monk3` subproject (`com.monk3`) — the Quarkus application:

| Package | Role |
|---|---|
| `api` | JAX-RS resources and `ExceptionMapper` implementations |
| `json` | Custom Jackson deserializers for the query DSL |
| `mapping` | Application-level mapping config (`SearchMappingConfig`) |
| `model` | Immutable records for the domain model |
| `routing` | Query analysis and routing of material types to backends |
| `search` | Query translation and search execution services |

`catalog` subproject (`jd.nomad.*`): configuration catalog with hot reload — `config` (catalog datastores: `FileCatalogDatastore`, `EtcdCatalogDatastore`, `ConfigurationCatalogService`, `DatasourceDescriptor`), `mapping` (mapping/backend config records, `FieldType`, `SourceExpression`), `routing` (`RoutingRule` / `RoutingCondition`). It is shared by both `:monk3` (query side) and `:nomad` (indexing side).

`nomad` subproject (`jd.nomad.*`): a Kafka→Solr/Elasticsearch indexing pipeline (Quarkus + Apache Camel) that consumes the **same** catalog config as monk3. It adds indexer-only config the query side ignores (per-datasource sourcing, datasources, richer backend connection info). See `nomad/CLAUDE.md`.

### REST endpoints (`/queries`)

- `POST /queries/parse` — accepts the same body as `/queries/search`; translates the query to Elasticsearch or Solr DSL for each configured backend
- `POST /queries/search` — executes against configured backends and returns merged, normalized results
- `GET /queries/schema` — serves the bundled `search-query-dsl.schema.json`

### Query DSL model

`SearchQueryRequest` carries a list of `materialTypes` and a root `QueryNode`. A `QueryNode` has a `field` and `data`:

- **Leaf node**: non-empty `field` with `QueryPayload` data — one of `TextQuery`, `RangeQuery` (`Numeric` / `Datetime`), or `ExactQuery` (`Numeric` / `Datetime` / `BooleanValues`).
- **Boolean node**: empty `field` with `BooleanQueryData` (a flat array of `QueryNode` clauses, each carrying a required `bool` of `should` (OR), `must` (AND), or `mustNot` (negation); clauses are grouped by that tag).
- **Subdocument node**: non-empty `field` pointing to a subdocument type, with `BooleanQueryData` — translates to ES `nested` or Solr `{!parent}` queries.

A `QueryNode`'s `minimumMatch` is only meaningful on boolean nodes (it sets minimum-should-match over the `should` clauses); its `bool` is only meaningful when the node is itself a clause of a boolean node.

### Configuration catalog and field mapping

Configuration is loaded (and hot-reloaded) by the `catalog` subproject, selected via `indexer.catalog.source` (`FILE` or `etcd`) in `application.yaml`. For the file source, `indexer.catalog.file.config` points at `config/catalog.json` (per material type: a default `backend`, optional `routing` rules, and an optional `filter`) and `indexer.catalog.file.backends` points at `config/backends.json` (per backend: connection details plus the `physical` mapping file, optional `virtual` mapping file, and `primaryKey`). Mappings are loaded from each backend's `physical` file; `catalog.json` only routes material types to backends, so the snapshot keys mappings by backend (`mappingsByBackend`).

A material type's optional `filter` is a query-DSL `QueryNode` (stored raw as a `JsonNode`, like a mapping's `root.identifier`). On the query side `QueryTranslationService` parses it (`objectMapper.convertValue(filter, QueryNode.class)`), translates it for the target engine, and adds it to the query's `bool.filter` (multiple material types resolving to one backend are OR-combined via `QueryJson.shouldOrSingle`). This replaces the former hardcoded material-type filter; a material type with no `filter` adds no restriction.

Each mapping file (`config/mappings/*.mapping.json`) declares:
- `root` — root-level fields, plus an optional `identifier` (a query-DSL query that matches documents of this material type)
- Additional named blocks for subdocument types

Each field entry has a `type` (`string`, `freetext`, `number`, `datetime`, `boolean`, `subdocument`) and optionally a `destinationField` (the actual field name sent to the search engine). Subdocument fields also declare `subdocumentType` referencing another block in the same file.

The root `identifier` is used as the Solr `{!parent}` block mask (the `which` local param) for root-level nested queries: it is translated to engine JSON and referenced via a root-level `queries` block (`{!v=$root_identifier}`). Intermediate block joins instead derive their mask from the parent hierarchy's nest path (e.g. `_nest_path_:/chapters`). A root `identifier` is required whenever a Solr-backed mapping is queried with a subdocument node.

Field entries may also carry **indexer-only** keys (parsed by the shared `CatalogSnapshotBuilder` but ignored by monk3): `sourcing` (per-datasource value extraction — a bare jq string, or an object with exactly one of `jq`/`jsonPointer` plus optional `partialUpdate`/`required`), and on subdocument fields `primaryKey` (per-datasource child-id extraction) and `partialUpdate` (per-datasource array op). The per-field maps fall back `<datasource>` → `default` → `*`. The nomad indexer also reads a `config/datasources.json` (selected via `indexer.catalog.file.datasources` / etcd `…datasources`) and the optional `BackendConfig` connection fields `zk` / `chroot` / `hosts` from `backends.json`. All of these are optional, so monk3 mappings/backends that omit them still validate. The mapping JSON Schema (`config/mappings/mappings.schema.json`) documents the optional keys.

Virtual mapping files (`*.virtual.json`) declare virtual fields that expand to query templates (with a `{{data}}` placeholder) via `VirtualFieldExpander`; `predicate`-typed virtual fields take no data.

### Search execution

`SearchExecutionService` fans out to all configured backends in parallel using virtual threads, translates the query per backend's engine, resolves field projections, POSTs the JSON body, then merges and normalizes scores before returning sorted results.

Backends are configured in `config/backends.json`. Each backend declares its `engine` (`ELASTICSEARCH` or `SOLR`), `url`, either `index` (ES) or `collection` (Solr), a `primaryKey` (the stored field used as the document ID), its `physical` mapping file and optional `virtual` mapping file, and an optional `defaultSize`. Optional indexer-side connection fields (`zk` / `chroot` for SolrCloud, `hosts` for an ES cluster) may also appear; connection-only backends (which declare no `physical`) contribute no mapping. Each material type routes to its default backend from `catalog.json` unless a `routing` rule matches (e.g. `datetimeRangeWithin` sends recent-range queries elsewhere); `QueryAnalyzer` + `RoutingEngine` evaluate the rules per request.

### Testing

Tests are full Quarkus integration tests (`@QuarkusTest`) using REST-assured. `SearchBackendTestResource` is a `@QuarkusTestResource` that starts a mock HTTP server impersonating Elasticsearch and Solr backends.

### Code style (from AGENTS.md)

- Prefer `@RequiredArgsConstructor` with `final` fields over field injection.
- Use records for immutable models.
- Use `Optional<T>` as a return type for absent values; not for fields, parameters, or DTOs.
- Stream pipelines for declarative transformations; ordinary loops when mutation or exception handling is involved.
