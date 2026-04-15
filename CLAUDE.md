# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run in dev mode (hot reload)
./gradlew quarkusDev

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.monk3.QueryResourceTest"

# Run a single test method
./gradlew test --tests "com.monk3.QueryResourceTest.parsesTextQueryToElasticsearchDslUsingConfiguredMapping"
```

## Architecture

monk3 is a Quarkus REST service (Java 25) that accepts a custom search query DSL and translates it into Elasticsearch or Solr query syntax, optionally executing searches across configured backends.

### Package layout

| Package | Role |
|---|---|
| `api` | JAX-RS resources and `ExceptionMapper` implementations |
| `json` | Custom Jackson deserializers for the query DSL |
| `mapping` | Field mapping definitions (logical name → search-engine field name) |
| `model` | Immutable records for the domain model |
| `search` | Query translation and search execution services |

### REST endpoints (`/queries`)

- `POST /queries/parse` — translates to Elasticsearch or Solr DSL for each configured backend
- `POST /queries/search` — executes against configured backends and returns merged, normalized results
- `GET /queries/schema` — serves the bundled `search-query-dsl.schema.json`

### Query DSL model

`SearchQueryRequest` carries a list of `materialTypes` and a root `QueryNode`. A `QueryNode` has a `field` and `data`:

- **Leaf node**: non-empty `field` with `QueryPayload` data — one of `TextQuery`, `RangeQuery` (`NumericRangeQuery` / `DatetimeRangeQuery`), or `ExactQuery` (`NumericExactQuery` / `DatetimeExactQuery` / `BooleanExactQuery`).
- **Boolean node**: empty `field` with `BooleanQueryData` (a list-of-lists of `QueryNode`s, outer = OR/should clauses, inner = AND/must clauses).
- **Subdocument node**: non-empty `field` pointing to a subdocument type, with `BooleanQueryData` — translates to ES `nested` or Solr `{!parent}` queries.

`QueryNode` fields `minimumMatch` and `isNot` are only meaningful on boolean nodes.

### Field mapping

Mappings live in `src/main/resources/mappings/*.mapping.json` and are loaded at startup by `MappingRepository` using the `monk3.search.material-type-mappings` config. Each mapping file declares:
- `primaryKey` — the stored field name used as the document ID
- `root` — root-level fields
- Additional named blocks for subdocument types

Each field entry has a `type` (`string`, `freetext`, `number`, `datetime`, `boolean`, `subdocument`) and optionally a `destinationField` (the actual field name sent to the search engine). Subdocument fields also declare `subdocumentType` referencing another block in the same file.

### Search execution

`SearchExecutionService` fans out to all configured backends in parallel using virtual threads, translates the query per backend's engine, resolves field projections, POSTs the JSON body, then merges and normalizes scores before returning sorted results.

Backends are configured under `monk3.search.backends` in `application.yaml`. Each backend declares its `engine` (`ELASTICSEARCH` or `SOLR`), `url`, either `index` (ES) or `collection` (Solr), and the `materialTypes` it serves.

### Testing

Tests are full Quarkus integration tests (`@QuarkusTest`) using REST-assured. `SearchBackendTestResource` is a `@QuarkusTestResource` that starts a mock HTTP server impersonating Elasticsearch and Solr backends.

### Code style (from AGENTS.md)

- Prefer `@RequiredArgsConstructor` with `final` fields over field injection.
- Use records for immutable models.
- Use `Optional<T>` as a return type for absent values; not for fields, parameters, or DTOs.
- Stream pipelines for declarative transformations; ordinary loops when mutation or exception handling is involved.
