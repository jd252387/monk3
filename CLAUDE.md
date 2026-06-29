# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run in dev mode (hot reload) — the app lives in the :monk subproject
./gradlew :monk:quarkusDev

# Run all tests
./gradlew test

# Run a single test class
./gradlew :monk:test --tests "com.monk.QueryResourceTest"

# Run a single test method
./gradlew :monk:test --tests "com.monk.QueryResourceTest.parsesTextQueryToElasticsearchDslUsingConfiguredMapping"
```

## Architecture

monk is a Quarkus REST service (Java 25) that accepts a custom search query DSL and translates it into Elasticsearch or Solr query syntax, optionally executing searches across configured backends.

### Package layout

`monk` subproject (`com.monk`) — the Quarkus application:

| Package | Role |
|---|---|
| `api` | JAX-RS resources and `ExceptionMapper` implementations |
| `json` | Custom Jackson deserializers for the query DSL |
| `mapping` | Application-level config mappings (`VapiConfig`, `EmbeddingConfig`) |
| `model` | Immutable records for the domain model |
| `routing` | Query analysis and routing of material types to backends |
| `search` | Query translation and search execution services |

`catalog` subproject (`jd.nomad.*`): configuration catalog with hot reload — `config` (catalog datastores: `FileCatalogDatastore`, `EtcdCatalogDatastore`, `ConfigurationCatalogService`, `DatasourceDescriptor`), `mapping` (mapping/backend config records, `FieldType`, `SourceExpression`), `routing` (`RoutingRule` / `RoutingCondition`). It is shared by both `:monk` (query side) and `:nomad` (indexing side).

`nomad` subproject (`jd.nomad.*`): a Kafka→Solr/Elasticsearch indexing pipeline (Quarkus + Apache Camel) that consumes the **same** catalog config as monk. It adds indexer-only config the query side ignores (per-datasource sourcing, datasources, richer backend connection info). See `nomad/CLAUDE.md`.

### REST endpoints (`/queries`)

Both `POST` endpoints take a `SearchExecutionRequest`: a `username` (forwarded to Solr backends as the `uc` request param, ignored by Elasticsearch), a list of `query` (`SearchQueryRequest`s; queries targeting the same backend are merged with a boolean `should`), the `fields` to project, an optional `size`, and an optional `aggs` map of named aggregations.

- `POST /queries/parse` — accepts the same body as `/queries/search`; translates the full request (query, result fields, size, and aggregations) into each backend's native Elasticsearch or Solr body without executing it (the returned body per backend is exactly what `/queries/search` would POST)
- `POST /queries/search` — executes against configured backends and returns merged, normalized results
- `GET /queries/schema` — serves the bundled `search-query-dsl.schema.json`

### Query DSL model

`SearchQueryRequest` carries a list of `materialTypes` and a root `QueryNode`. A `QueryNode` has a `field` and `data`:

- **Leaf node**: non-empty `field` with `QueryPayload` data — one of `TextQuery`, `RangeQuery` (`Numeric` / `Datetime`), `ExactQuery` (`Numeric` / `Datetime` / `BooleanValues`), `ExistsQuery` (field has any value), `PrefixQuery` (value starts with a prefix), or `KnnFlatQuery` (embeds free text and searches a vector field).
- **Boolean node**: empty `field` with `BooleanQueryData` (a flat array of `QueryNode` clauses, each carrying a required `bool` of `should` (OR), `must` (AND), or `mustNot` (negation); clauses are grouped by that tag).
- **Subdocument node**: non-empty `field` pointing to a subdocument type, with `BooleanQueryData` — translates to ES `nested` or Solr `{!parent}` queries.

A `QueryNode`'s `minimumMatch` is only meaningful on boolean nodes (it sets minimum-should-match over the `should` clauses); its `bool` is only meaningful when the node is itself a clause of a boolean node.

### Aggregations

The request's optional `aggs` is a map of name → `Aggregation` (a sealed interface deserialized by `AggregationDeserializer`, dispatching on `aggType`), computed per backend and translated to native ES aggregations / Solr JSON facets. Each carries an `args` object and may declare nested `aggs` (sub-aggregations that run over each bucket the parent produces). Types: `terms`, `range`, `subfacets`, `filter`, `unique`, the metrics `sum` / `avg` / `min` / `max` (`MetricAggregation`), and `nested`. `unique` and the metrics reject sub-aggregations.

A `nested` aggregation (`NestedAggregation`) runs its sub-aggregations within the domain of a subdocument hierarchy: its `args.path` is an ordered, non-empty list of subdocument field names to descend into (e.g. `["chapters", "pages"]`), and the sub-aggregations resolve their fields at the deepest level. It translates to an ES `nested` aggregation and a Solr `blockChildren` domain change. `AggregationContext.enterNested` walks the path segment by segment, deriving the engine path/mask the same way `BooleanQueryData.translate` does for subdocument query nodes (Solr block mask from the root `identifier` at the top level, or the parent hierarchy's nest path below it), and fails if the path resolves to different nested paths across the requested material types. An aggregation over a non-`aggregatable` field is rejected (see `FieldCapabilities` below).

### Configuration catalog and field mapping

Configuration is loaded (and hot-reloaded) by the `catalog` subproject, selected via `indexer.catalog.source` (`FILE` or `etcd`) in `application.yaml`. For the file source, `indexer.catalog.file.config` points at `config/catalog.json` (per material type: a default `backend`, optional `routing` rules, and an optional `filter`) and `indexer.catalog.file.backends` points at `config/backends.json` (per backend: connection details plus the `physical` mapping file, optional `virtual` mapping file, and `primaryKey`). Mappings are loaded from each backend's `physical` file; `catalog.json` only routes material types to backends, so the snapshot keys mappings by backend (`mappingsByBackend`).

A material type's optional `filter` is a query-DSL `QueryNode` (stored raw as a `JsonNode`, like a mapping's `root.identifier`). On the query side `QueryTranslationService` parses it (`objectMapper.convertValue(filter, QueryNode.class)`), translates it for the target engine, and adds it to the query's `bool.filter` (multiple material types resolving to one backend are OR-combined via `QueryJson.shouldOrSingle`). This replaces the former hardcoded material-type filter; a material type with no `filter` adds no restriction.

Each mapping file (`config/mappings/*.mapping.json`) declares:
- `root` — root-level fields, plus an optional `identifier` (a query-DSL query that matches documents of this material type)
- Additional named blocks for subdocument types

Each field entry has a `type` (`string`, `freetext`, `number`, `datetime`, `boolean`, `subdocument`) and optionally a `destinationField` (the actual field name sent to the search engine). Subdocument fields also declare `subdocumentType` referencing another block in the same file.

A field entry may also declare optional capability flags (`FieldCapabilities`, parsed by `CatalogSnapshotBuilder`) gating which operations a query may perform on it: `searchable` and `fetchable` default to `true`, `aggregatable` and `sortable` default to `false`. The query side fails the query when a constraint is violated: a leaf query on a non-`searchable` field (`QueryPayload`), a projection of a non-`fetchable` field (`SearchExecutionService`), or an aggregation over a non-`aggregatable` field (`AggregationContext`) are all rejected. `sortable` is parsed and retained but has no enforcement point yet because the DSL exposes no sort surface.

The root `identifier` is used as the Solr `{!parent}` block mask (the `which` local param) for root-level nested queries: it is translated to engine JSON and referenced via a root-level `queries` block (`{!v=$root_identifier}`). Intermediate block joins instead derive their mask from the parent hierarchy's nest path (e.g. `_nest_path_:/chapters`). A root `identifier` is required whenever a Solr-backed mapping is queried with a subdocument node.

Field entries may also carry **indexer-only** keys (parsed by the shared `CatalogSnapshotBuilder` but ignored by monk): `sourcing` (per-datasource value extraction — a bare jq string, or an object with exactly one of `jq`/`jsonPointer` plus optional `partialUpdate`/`required`), and on subdocument fields `primaryKey` (per-datasource child-id extraction) and `partialUpdate` (per-datasource array op). The per-field maps fall back `<datasource>` → `default` → `*`. The nomad indexer also reads a `config/datasources.json` (selected via `indexer.catalog.file.datasources` / etcd `…datasources`) and the optional `BackendConfig` connection fields `zk` / `chroot` / `hosts` from `backends.json`. All of these are optional, so monk mappings/backends that omit them still validate. The mapping JSON Schema (`config/mappings/mappings.schema.json`) documents the optional keys.

Virtual mapping files (`*.virtual.json`) declare virtual fields that expand to query templates (with a `{{data}}` placeholder) via `VirtualFieldExpander`; `predicate`-typed virtual fields take no data.

### Search execution

`SearchExecutionService` fans out to all configured backends in parallel using virtual threads, translates the query per backend's engine, resolves field projections, POSTs the JSON body, then merges and normalizes scores before returning sorted results.

Backends are configured in `config/backends.json`. Each backend declares its `engine` (`ELASTICSEARCH` or `SOLR`), `url`, either `index` (ES) or `collection` (Solr), a `primaryKey` (the stored field used as the document ID), its `physical` mapping file and optional `virtual` mapping file, and an optional `defaultSize`. Optional indexer-side connection fields (`zk` / `chroot` for SolrCloud, `hosts` for an ES cluster) may also appear; connection-only backends (which declare no `physical`) contribute no mapping. Each material type routes to its default backend from `catalog.json` unless a `routing` rule matches (e.g. `datetimeRangeWithin` sends recent-range queries elsewhere); `QueryAnalyzer` + `RoutingEngine` evaluate the rules per request.

### Testing

Tests are full Quarkus integration tests (`@QuarkusTest`) using REST-assured. `SearchBackendTestResource` is a `@QuarkusTestResource` that starts a mock HTTP server impersonating Elasticsearch and Solr backends.

### Code style

**General / Java**

- Target Java 25 and follow the existing Quarkus project structure.
- Prefer clear, immutable models (records) and small methods with explicit names.
- Keep business logic out of REST resource classes when it grows beyond simple request handling.

**Gradle**

- Use the Gradle wrapper (`./gradlew`) for all build, test, and verification commands.
- Prefer adding dependencies and plugin configuration in Gradle files over IDE-only setup; keep changes focused and consistent with existing conventions.

**Lombok**

- Use Lombok to remove low-value boilerplate, not to hide behavior.
- Prefer constructor injection with `final` fields and `@RequiredArgsConstructor` over field injection.

**Streams**

- Use streams for collection transformations, filtering, grouping, and short declarative pipelines; prefer ordinary loops when mutation, branching, exception handling, or debugging would make a stream harder to read.
- Keep pipelines small and readable; extract named helper methods instead of nesting complex lambdas.
- Avoid side effects inside `map`, `filter`, `flatMap`, and `peek` (`peek` only for temporary diagnostics).
- Prefer intent-revealing terminal operations (`toList`, `findFirst`, `anyMatch`, `allMatch`, `noneMatch`, `collect(groupingBy(...))`, `collect(toMap(...))`); be explicit about duplicate-key handling with `Collectors.toMap`.
- Do not use parallel streams unless the workload is proven CPU-bound, thread-safe, and worth the complexity.

**Optional**

- Use `Optional<T>` as a return type when a value may legitimately be absent; not for fields, method parameters, DTO properties, or serialization contracts (unless a framework integration requires it).
- Prefer `map`, `flatMap`, `filter`, `orElseGet`, `orElseThrow`, and `ifPresentOrElse` over manual presence checks; use `orElseGet` when the fallback is expensive or has side effects.
- Avoid `Optional.get()` unless presence has already been guaranteed in the same local scope.
- Do not wrap nullable collections in `Optional`; return an empty collection instead.
- Use absence to represent "not found" / "not provided", not failure; use exceptions or result types for errors that need diagnostics.
