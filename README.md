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

# Run in dev mode with hot reload (the app lives in the :monk3 subproject)
./gradlew :monk3:quarkusDev

# Run the tests
./gradlew test

# Run a single test class / method
./gradlew :monk3:test --tests "com.monk3.QueryResourceTest"
./gradlew :monk3:test --tests "com.monk3.QueryResourceTest.parsesTextQueryToElasticsearchDslUsingConfiguredMapping"
```

The service listens on `http://localhost:8080`. Swagger UI is available at
`http://localhost:8080/q/swagger-ui`.

## REST endpoints (`/queries`)

| Method & path          | Description                                                                                |
|------------------------|--------------------------------------------------------------------------------------------|
| `POST /queries/parse`  | Accepts the same body as `/queries/search`; translates the query to ES or Solr per backend. |
| `POST /queries/search` | Executes against the configured backends and returns merged results.                      |
| `GET  /queries/schema` | Serves the query DSL JSON Schema (draft 2020-12).                                          |

## Query DSL

A `SearchQueryRequest` carries a list of `materialTypes` and a root `QueryNode`. Each `QueryNode`
has a `field` and `data`:

- **Leaf node** — non-empty `field` with a `TextQuery`, `RangeQuery`, or `ExactQuery` payload.
- **Boolean node** — empty `field` with `BooleanQueryData` (a flat array of clauses, each a
  `QueryNode` with a required `bool` of `should`/`must`/`mustNot`). `minimumMatch` is meaningful only
  on boolean nodes; a clause's `bool` is meaningful only within its parent boolean node.
- **Subdocument node** — `field` pointing at a subdocument type with `BooleanQueryData`; translates
  to an Elasticsearch `nested` or Solr `{!parent}` query.

See [`search-query-dsl.schema.json`](src/main/resources/search-query-dsl.schema.json) for the
authoritative description.

## Configuration

Configuration is loaded and hot-reloaded by the `catalog` subproject. The source is selected via
`indexer.catalog.source` (`FILE` or `ETCD`) in [`application.yaml`](src/main/resources/application.yaml).

For the file source:

- `indexer.catalog.file.config` → [`config/catalog.json`](config/catalog.json) — per material type:
  a `physical` mapping file, an optional `virtual` mapping file, a default `backend`, and optional
  `routing` rules.
- `indexer.catalog.file.backends` → [`config/backends.json`](config/backends.json) — each backend's
  `engine` (`ELASTICSEARCH` or `SOLR`), `url`, `index` (ES) or `collection` (Solr), and optional `defaultSize`.

For the etcd source, the same documents live under etcd keys instead of files:

- `indexer.catalog.etcd.endpoints` → comma-separated etcd client endpoints (default `http://localhost:2379`).
- `indexer.catalog.etcd.catalog` → key holding a `catalog.json`-shaped document, whose `physical`/`virtual`
  entries reference **other etcd keys** rather than file paths.
- `indexer.catalog.etcd.backends` → key holding a `backends.json`-shaped document.

Each referenced key is watched; any change rebuilds the live snapshot, and a failed rebuild retains the
last-good configuration (the same hot-reload semantics as the file source).

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

## Running the stack with Docker

### Full stack with a containerized monk3 (root `docker-compose.yml`)

The repository-root [`docker-compose.yml`](docker-compose.yml) brings up the shared data-platform
backends and a containerized **monk3** that searches them. The monk3 image is built with
[Quarkus Jib](https://quarkus.io/guides/container-image#jib) (no Dockerfile) straight into the local
Docker daemon — `task compose:up` does this for you before starting the stack. It is profile-gated;
the `streaming` profile (the default used by the [Taskfile](Taskfile.yml)) is the one that runs
**both** Solr and Elasticsearch alongside monk3:

```bash
task compose:up                      # builds the monk3 Jib image, then starts the streaming profile
# or, without go-task:
task jib:build PROJECTS=':monk3:build'   # or: ./gradlew :monk3:build -Dquarkus.container-image.build=true
docker compose --profile streaming up -d
```

This starts, among others:

- **Solr** (`solr1`, `:8983`) and **Elasticsearch** (`es01`/`es02`, `:9200`/`:9201`) search backends.
- **monk3-init** — a one-shot job that creates the `sample` Solr collection and Elasticsearch index
  on those backends and seeds the sample documents. It also creates the empty `documents` Solr
  collection that the nomad indexer writes into.
- **monk3** — the query API on <http://localhost:8090> (Swagger UI at
  <http://localhost:8090/q/swagger-ui>). It reads [`config/catalog-docker.json`](config/catalog-docker.json)
  and [`config/backends-nomad.json`](config/backends-nomad.json) (baked into the Jib image from the
  repo-root `config/`), which point `product` → Solr (`solr1`) and `product_elastic` →
  Elasticsearch (`es01`).
- **nomad** — the Kafka→Solr indexing pipeline. It consumes the `documents` Kafka topic and indexes
  into the `documents` Solr collection on `solr1`, reading [`config/catalog-nomad.json`](config/catalog-nomad.json)
  and [`config/backends-nomad.json`](config/backends-nomad.json) (baked into the Jib image like monk3).
  The `documents-replicator` publishes Kafka events only when run with `EVENT_DS=streaming`
  (`task compose:up EVENT_DS=streaming`), with each event carrying the document inline for the
  `default-datasource` (kafka-inline) source; generate sample documents into `nomad/documents/` with
  `task generate:document`.

Tear it down with `task compose:down`. Other profiles (`mongo`, `s3`, `hbase`, `rest`) bring up the
indexing-side data sources; see [`TASKFILE.md`](TASKFILE.md). Both the monk3 and nomad images are
built by `task jib:build` (run automatically by `task compose:up`).

### monk3-only sample stack with monk3 on the host (`docker/docker-compose.yml`)

[`docker/docker-compose.yml`](docker/docker-compose.yml) brings up the supporting services and seeds
the configuration into etcd, which is what `application.yaml` reads from by default:

```bash
docker compose -f docker/docker-compose.yml up -d
```

This starts:

- **Elasticsearch** (`:9200`) and **Solr** (`:8983`) search backends.
- **Kibana** (`:5601`) — web UI for Elasticsearch.
- **etcd** (`:2379`) and **etcd-workbench** (`:8002`) — etcd plus a web UI; open
  <http://localhost:8002> and add a connection to host `etcd`, port `2379`.
- **etcd-seed** — a one-shot job that loads [`config/catalog-etcd.json`](config/catalog-etcd.json),
  the sample mapping, and [`config/backends-docker.json`](config/backends-docker.json) into the etcd
  `/monk3/*` keys via the v3 gateway.
- **init** — a one-shot job that creates the `sample` Solr collection and Elasticsearch index and
  indexes the sample documents.

Once the stack is up, run the service against it with `./gradlew :monk3:quarkusDev` (it connects to etcd on
`localhost:2379` and to the backends on `localhost:9200`/`localhost:8983`).

## Testing

Tests are full Quarkus integration tests (`@QuarkusTest`) driven with REST-assured.
`SearchBackendTestResource` starts a mock HTTP server impersonating the Elasticsearch and Solr
backends, so no external services are required.
