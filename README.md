# monk3

A [Quarkus](https://quarkus.io/) REST service (Java 25) that accepts a custom search query DSL and
translates it into **Elasticsearch** or **Solr** query syntax, optionally executing searches across
configured backends and returning merged, normalized results.

## Features

- A backend-agnostic JSON **query DSL** (text, range, exact, boolean, and nested/subdocument queries)
  with a published [JSON Schema](src/main/resources/search-query-dsl.schema.json).
- Translation to **Elasticsearch** and **Solr** query syntax, including aggregations (terms, unique,
  range, and sub-facets).
- A hot-reloadable **configuration catalog** (file- or etcd-backed) mapping material types to field
  mappings and backends.
- **Routing rules** that send specific queries (e.g. recent datetime ranges) to alternate backends.
- Parallel fan-out execution across backends using virtual threads, with score normalization and merging.

## Requirements

- **JDK 25**
- No local Gradle install needed — use the bundled wrapper (`./gradlew`).

## Quick start

```bash
# Build (compiles and runs the full test suite)
./gradlew build

# Run in dev mode with hot reload
./gradlew quarkusDev

# Run the tests
./gradlew test

# Run a single test class / method
./gradlew test --tests "com.monk3.QueryResourceTest"
./gradlew test --tests "com.monk3.QueryResourceTest.parsesTextQueryToElasticsearchDslUsingConfiguredMapping"
```

The service listens on `http://localhost:8080`. Swagger UI is available at
`http://localhost:8080/q/swagger-ui`.

## REST endpoints (`/queries`)

| Method & path          | Description                                                           |
|------------------------|-----------------------------------------------------------------------|
| `POST /queries/parse`  | Translates a DSL request to Elasticsearch or Solr DSL per backend.    |
| `POST /queries/search` | Executes against the configured backends and returns merged results.  |
| `GET  /queries/schema` | Serves the query DSL JSON Schema (draft 2020-12).                     |

## Query DSL

A `SearchQueryRequest` carries a list of `materialTypes` and a root `QueryNode`. Each `QueryNode`
has a `field` and `data`:

- **Leaf node** — non-empty `field` with a `TextQuery`, `RangeQuery`, or `ExactQuery` payload.
- **Boolean node** — empty `field` with `BooleanQueryData` (list-of-lists; outer = OR/should,
  inner = AND/must). The `minimumMatch` and `isNot` fields are meaningful only on boolean nodes.
- **Subdocument node** — `field` pointing at a subdocument type with `BooleanQueryData`; translates
  to an Elasticsearch `nested` or Solr `{!parent}` query.

See [`search-query-dsl.schema.json`](src/main/resources/search-query-dsl.schema.json) for the
authoritative description.

## Configuration

Configuration is loaded and hot-reloaded by the `catalog` subproject. The source is selected via
`indexer.catalog.source` (`FILE` or `etcd`) in [`application.yaml`](src/main/resources/application.yaml).

For the file source:

- `indexer.catalog.file.config` → [`config/catalog.json`](config/catalog.json) — per material type:
  a `physical` mapping file, an optional `virtual` mapping file, a default `backend`, and optional
  `routing` rules.
- `indexer.catalog.file.backends` → [`config/backends.json`](config/backends.json) — each backend's
  `engine` (`ELASTICSEARCH` or `SOLR`), `url`, `index` (ES) or `collection` (Solr), and optional `defaultSize`.

### Mapping files (`config/mappings/*.mapping.json`)

Each mapping declares a `primaryKey` (the stored field used as the document ID), a `root` block of
fields, and additional named blocks for subdocument types. A field has a `type` (`string`,
`freetext`, `number`, `datetime`, `boolean`, `subdocument`) and an optional `destinationField`
(the name actually sent to the search engine). Subdocument fields declare a `subdocumentType`
referencing another block in the same file.

**Virtual mapping files** (`*.virtual.json`) declare virtual fields that expand to query templates
(with a `{{data}}` placeholder); `predicate`-typed virtual fields take no data.

## Project structure

```
monk3/
├── src/main/java/com/monk3/   Root service
│   ├── api/                   JAX-RS resources and exception mappers
│   ├── json/                  Jackson deserializers for the query DSL
│   ├── mapping/               Application-level mapping config
│   ├── model/                 Immutable domain records
│   ├── routing/               Query analysis and backend routing
│   └── search/                Query translation and search execution
├── catalog/                   Subproject: hot-reloading configuration catalog (jd.nomad.*)
├── config/                    Catalog, backends, and mapping configuration
└── src/test/                  Quarkus integration tests (REST-assured)
```

## Testing

Tests are full Quarkus integration tests (`@QuarkusTest`) driven with REST-assured.
`SearchBackendTestResource` starts a mock HTTP server impersonating the Elasticsearch and Solr
backends, so no external services are required.
