# Migrate `nomad` onto the shared `:catalog` configuration

## Context

`monk3` (a query-translation REST service) and `nomad` (a Kafka→Solr/Elasticsearch
indexing pipeline) were originally one codebase — both use the `jd.nomad.*` package and
both once had a `jd.nomad.config.catalog.ConfigurationCatalogService` /
`FileCatalogDatastore` / `EtcdCatalogDatastore`. The `:catalog` subproject is the
*refactored, query-side* extraction of that config system; `monk3` already consumes it.
`nomad` was pasted in (`nomad/`) still carrying its **own, older** copy of the config
classes plus an indexing-oriented config model.

Goal: make `nomad` read the **same configuration** as `monk3` by consuming the `:catalog`
subproject, so a single set of config files (in `config/`, file- or etcd-backed) drives
both apps. Because `nomad` is an *indexer*, it needs config the query-side model lacks —
per-datasource **sourcing** (JQ / JSON Pointer), partial-update ops, datasources, and
richer backend connection info. Those get added to the catalog mapping model and to the
mapping JSON Schema. `nomad`'s `datasources.json` becomes a new shared config file beside
`config/catalog.json` and `config/backends.json`.

### Decisions (from clarifying questions)
1. **Build**: fold `nomad` into the monk3 monorepo; align it to Java 25 + Quarkus
   3.33.1.1 and depend on `project(':catalog')`.
2. **Backends**: extend `BackendConfig` with optional `zk` / `chroot` / `hosts` so
   `nomad`'s SolrCloud / ES-cluster sinks keep working; `monk3` ignores the extra fields.
3. **Field naming**: **single** `destinationField` per field (the old per-sink `routing`
   map collapses to one physical name; no per-engine map).

### Key facts that shape the approach
- `nomad` is a standalone Gradle build (`nomad/settings.gradle`, own nested `.git`,
  `repositories { mavenCentral() }`, Quarkus 3.20.1 / Java 21, Camel stack). Root
  `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`, so nomad's own `repositories` block
  must go.
- `nomad` and `:catalog` share the package `jd.nomad.config.catalog`, so deleting nomad's
  copies and using the catalog's keeps most **imports** valid; only the changed **method
  names/signatures** need updating in consumers.
- The catalog's `CatalogSnapshotBuilder.parseMapping/parseField` is shared by both apps;
  every addition there is **additive** (monk3 mapping files simply omit the new keys).

---

## Phase A — Extend the `:catalog` subproject (`catalog/src/main/java/jd/nomad/...`)

**A1. Sourcing model (new records in `jd.nomad.mapping`)**
- `SourceExpression(String jq, String jsonPointer, String partialUpdate, boolean required)`
  — exactly one of `jq`/`jsonPointer` set; `hasPartialUpdate()` helper. Mirrors nomad's
  old `SourceExpression` but adds `jsonPointer` (the requested JSON Pointer option).
- Extend `MappedField` (currently `logicalName, type, subdocumentType, destinationField`)
  with three indexing fields, defaulting empty:
  - `Map<String,SourceExpression> sourcing` — leaf value extraction, keyed by datasource.
  - `Map<String,SourceExpression> primaryKeySourcing` — subdocument child-id extraction.
  - `Map<String,String> subdocumentPartialUpdate` — subdocument array op, by datasource.
  Add helpers `sourcingFor(ds)` / `primaryKeyFor(ds)` implementing the old
  `default` → `*` fallback (see nomad `FieldSourcing`/`PrimaryKeySourcing`). Add a compact
  secondary constructor `(logicalName,type,subdocumentType,destinationField)` defaulting
  the maps empty, so existing `monk3`/test call sites compile unchanged.

**A2. Mapping parser** — `catalog/.../config/catalog/CatalogSnapshotBuilder.java`
- In `parseField`, parse optional `sourcing` (value = string ⇒ `{jq}` required, or object
  `{jq|jsonPointer, partialUpdate, required}` — replicate the dual-format logic from
  nomad's `FieldDefinitionDeserializer`). For `subdocument` fields also parse optional
  `primaryKey` (datasource→expr) and `partialUpdate` (datasource→op string).
- Add `RESERVED`/document-reserved handling so the new keys aren't treated as fields.

**A3. Mapping JSON Schema** — `config/mappings/mappings.schema.json`
- Add optional `sourcing`, and (for subdocument fields) `primaryKey` / `partialUpdate`
  properties to the per-field schema, documenting `jq`, `jsonPointer`, `required`,
  `partialUpdate` operations. Keep everything optional so monk3 mappings still validate.

**A4. Backend connection** — `catalog/.../mapping/BackendConfig.java`
- Add optional `String zk`, `String chroot`, `List<URI> hosts` (keeps
  `@JsonIgnoreProperties(ignoreUnknown=true)`; monk3 ignores them).

**A5. Datasources in the catalog**
- Add `jd.nomad.config.catalog.DatasourceDescriptor(name, type, JsonNode configuration)`
  (port from nomad).
- `CatalogSnapshot`: add `Map<String,DatasourceDescriptor> datasources`.
- `CatalogSnapshotBuilder`: add `parseDatasources(JsonNode)` (accept either a bare object
  or `{ "datasources": { ... } }`, like the old sinks parser).
- `IndexerConfig` (`catalog/.../config/IndexerConfig.java`): add
  `Optional<String> datasources()` to `FileSource` and `EtcdSource`.
- `FileCatalogDatastore` / `EtcdCatalogDatastore`: when the datasources path/key is set,
  load + parse it into the snapshot and add it to the watch set (file monitor / etcd
  watcher) for hot reload; absent ⇒ empty map.
- `ConfigurationCatalogService`: add `datasource(String name)` and `datasources()`;
  ensure `replaceSnapshot`/`updateMapping`/`updateBackends` preserve the datasources map.

---

## Phase B — Shared config files (repo `config/`)

**B1. New `config/datasources.json`** — port `nomad/src/main/resources/config/datasources.json`
content, wrapped as `{ "datasources": { ... } }` to match the catalog/backends convention
(kafka-inline, s3, hbase, mongodb, rest-api entries).

**B2. `config/backends.json`** — add nomad's sinks as backends, translating
`nomad/.../config/sinks.json`:
- `solr-collection-text` → `{ "engine":"SOLR", "zk":"localhost:2181", "chroot":"/solr", "collection":"text" }`
- `elastic-index-text` → `{ "engine":"ELASTICSEARCH", "hosts":["http://es01:9200","http://es02:9200"], "index":"index-text" }`
- …and the `-small` / `-semantic` variants. Keep the existing monk3 backends.

**B3. New nomad material-type mapping** — `config/mappings/document.mapping.json`, converting
`nomad/.../config/mapping.json` into the catalog structure:
- `primaryKey` (doc id field), `root` with `itemName`/`itemContent`/`timestamp` carrying
  `type`, single `destinationField`, and `sourcing` (per-datasource jq/jsonPointer).
- nested `subItems` becomes a `root` field `{ "type":"subdocument", "subdocumentType":"subItems",
  "destinationField":"subItems", "primaryKey":{…}, "partialUpdate":{…} }` plus a top-level
  `subItems` block holding the child fields (each with `sourcing`).

**B4. `config/catalog.json`** — add a `document` material type referencing
`config/mappings/document.mapping.json` with a default `backend` (e.g. `solr-collection-text`).

---

## Phase C — Fold `nomad` into the monorepo build

- Root `settings.gradle.kts`: add `include("nomad")`.
- Delete `nomad/settings.gradle` (root settings owns project structure).
- `nomad/build.gradle`:
  - Remove the `repositories { mavenCentral() }` block (root uses `FAIL_ON_PROJECT_REPOS`).
  - Bump to `alias(libs.plugins.quarkus)` (3.33.1.1) and Java toolchain 25 / `release = 25`;
    bump the Quarkus + `quarkus-camel-bom` platforms to 3.33.1.1; switch lombok to `libs.lombok`.
  - Add `implementation project(':catalog')`; drop now-transitive `commons-vfs2` / `jetcd-core`.
  - Re-align `camel-quarkus-hbase` to the BOM-managed version (drop the suspicious explicit
    `:2.16.0`). **Verify** all camel-quarkus components nomad uses exist in the 3.33 BOM.
- Leave `nomad/.git` in place; note it for the user (nested repo) — not modified here.

---

## Phase D — Rewrite nomad to consume `:catalog`

**D1. Delete** nomad's superseded config/model classes:
- `nomad/.../config/catalog/*` (all 8: `ConfigurationCatalogService`, `CatalogSnapshot`,
  `CatalogSnapshotBuilder`, `CatalogDatastore`, `FileCatalogDatastore`,
  `EtcdCatalogDatastore`, `SinkDescriptor`, `DatasourceDescriptor`).
- `nomad/.../config/CatalogSource.java`.
- `nomad/.../mapping/definition/*` and `nomad/.../mapping/config/MappingParser.java`.
(Imports of `jd.nomad.config.catalog.ConfigurationCatalogService` /
`DatasourceDescriptor` / `CatalogSnapshot` stay valid — same package, now from `:catalog`.)

**D2. `nomad/.../config/IndexerConfig.java`** — drop `catalog()`/`Catalog`/`FileSource`/
`EtcdSource` and the `mappingCatalog/datasourcesCatalog/sinksCatalog` defaults (the
catalog's own `IndexerConfig` now owns `indexer.catalog.*`). Keep `kafka()`, `pipeline()`,
`isPartial()`, `partialUpdateHierarchy()`, `dataSource()`; rename `sink()` → `backend()`
(optional override) and add `materialType()` (which mapping to index). Both interfaces sit
at prefix `indexer` mapping disjoint subtrees — **verify** SmallRye accepts the co-located
mappings (mirrors how monk3 + the catalog's IndexerConfig already coexist).

**D3. `MappingEngine`** (`nomad/.../mapping/MappingEngine.java`) — walk `SearchMapping`
instead of `MappingDefinition`:
- Resolve the active mapping via `catalog.mappingForMaterialType(indexerConfig.materialType())`
  and active backend via `backendConfig(backend ?? backendForMaterialType(mt))`.
- Physical name = `MappedField.searchField()` (single destinationField; the old
  `resolveTarget(sink)`→empty "skip" branch is gone).
- Leaf value = `field.sourcingFor(datasource)` → evaluate (D6). Partial-update op comes
  from the `SourceExpression`.
- Subdocument fields: look up the block via `mapping.document(subdocumentType)`, use
  `field.primaryKeyFor(datasource)` for child ids, `subdocumentPartialUpdate` for the array
  op, child `sourcingFor` for child values; nested-id field (`_id` vs `item_id`) from a
  small engine helper keyed off `BackendEngine` (replaces `SinkDescriptor.nestedPrimaryKeyField`).
- Keep the `partial-update-hierarchy` path, retargeted to the new field walk.

**D4. `MappingFieldCollector`** — adapt to walk `SearchMapping` and read `sourcingFor(ds)`;
for JQ use `JqSourceFieldExtractor`, for JSON Pointer derive the top-level field from the
pointer's first segment.

**D5. Data fetchers** — `DataFetcherDelegate` + `*DataFetcherFactory`: use the catalog's
`ConfigurationCatalogService.datasource(name)` / `DatasourceDescriptor`; change
`registerListener(ConfigType, Runnable)` → `registerListener(Runnable)`. Per-type settings
(`S3DataSourceSettings`, etc.) still parse `descriptor.configuration()` — unchanged.

**D6. Evaluation** — `JqEvaluationService`: add `evaluate(SourceExpression, JsonNode)` that
dispatches: `jq` → existing jackson-jq path; `jsonPointer` → `node.at(pointer)` (array ⇒
its elements as the result list, else single value; missing ⇒ empty). Document that
multi-valued nested array alignment requires JQ `[]` (JSON Pointer can't iterate like that).

**D7. Index sinks** — `IndexSinkFactory.create(BackendConfig, CamelContext)`;
`IndexSinkProducer`/active-sink selection keys off `BackendEngine`. `SolrIndexSinkFactory`
reads `backend.zk()/chroot()/collection()` (fallback to `backend.url()` when zk absent);
`ElasticsearchIndexSinkFactory` reads `backend.hosts()` (fallback `backend.url()`) +
`backend.index()`. Preserve the `is-partial` flag and percolator sink.

---

## Phase E — Python scripts, docker-compose, Taskfile (`nomad/`)

- `nomad/scripts/generate_document.py` — read the new JSON mapping
  (`config/mappings/document.mapping.json`: `root` + subdocument blocks, `type`-driven
  value generation, follow `subdocumentType`) instead of the old mapping YAML.
- `nomad/document-sync/sync.py` — update any references to old config paths/material-type
  assumptions; keep its env-var driven replication.
- `nomad/docker-compose.yml` — mount the shared repo `config/` into the indexer service and
  set `INDEXER_CATALOG_SOURCE=FILE`, `INDEXER_CATALOG_FILE_CONFIG=config/catalog.json`,
  `_FILE_BACKENDS=config/backends.json`, `_FILE_DATASOURCES=config/datasources.json` (plus
  `INDEXER_MATERIAL_TYPE` / `INDEXER_DATA_SOURCE` / `INDEXER_BACKEND`). Keep the
  kafka/solr/es/mongo/minio/hbase/document-api/replicator services.
- `nomad/Taskfile.yml` — point `generate:document` at the new mapping path; fix any compose
  paths changed above.

## Phase F — Docs
- Update `nomad/CLAUDE.md` (catalog system now provided by `:catalog`; sourcing supports
  `jq`/`jsonPointer`; single `destinationField`; backends.json + datasources.json).
- Note the new mapping keys in the root `CLAUDE.md` "Configuration catalog" section.

---

## Verification
1. **Build**: `./gradlew :catalog:build :monk3:build :nomad:build` from repo root — all
   compile; monk3 unchanged behaviorally (its mappings omit the new keys).
2. **monk3 tests**: `./gradlew :monk3:test` still green (confirms additive catalog changes
   didn't regress query-side parsing; fix any `MappedField`/`CatalogSnapshot`/`BackendConfig`
   constructor call sites in tests via the secondary constructor).
3. **Catalog parsing**: a small `:catalog` (or `:nomad`) test loading
   `config/catalog.json` + `config/datasources.json` + `document.mapping.json`, asserting
   `mappingForMaterialType("document")` exposes `sourcing`/`primaryKey`, `datasource(...)`
   resolves, and `backendConfig(...)` returns `zk`/`hosts`.
4. **Schema**: validate `document.mapping.json` and the existing monk3 mappings against the
   updated `mappings.schema.json`.
5. **Indexer smoke (optional, heavy)**: `task compose:up PROFILE=streaming` in `nomad/`,
   produce a Kafka `documents` event, confirm a doc lands in Solr/ES with mapped+sourced
   fields. Requires the docker stack.
6. **`generate_document.py`**: run against `config/mappings/document.mapping.json` and
   confirm it emits a valid sample document.
