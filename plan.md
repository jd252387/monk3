# Plan: make `/queries/parse` emit the full per-backend request body

## Context

Today `/queries/parse` (`QueryResource.parseQuery`) only translates the `query`
field of `SearchExecutionRequest`. It calls
`QueryTranslationService.translateByBackend(request.query())`, which produces a
`BackendQuery` whose `query` holds just the translated query clause
(`{ "bool": {...} }`). The other request fields — `fields`, `size`, `aggs` — are
silently ignored.

Meanwhile `/queries/search` (`SearchExecutionService.searchBackend`) already
assembles the **complete** backend request body: the `query`, the result options
(`size`/`limit` + `_source`/`fields` projection), and the aggregations
(`aggs`/`facet`). That body is the single source of truth for "what gets sent to
the engine."

Goal: `/queries/parse` should return that exact body per backend, so a caller can
see the aggregation/facet and the result parameters (ES `_source`/`size`, Solr
`fields`/`limit` — i.e. Solr's `fl`/`rows` in JSON Request API form) that
`/queries/search` would POST, without executing anything.

Decision (confirmed with user): expose **one full request body** per backend
(not separate labeled fields). Parse output == the body `/search` POSTs.

## Approach

Reuse the existing body-building path from `SearchExecutionService` for the parse
endpoint. No new translation logic — just extract and share what `searchBackend`
already does.

### 1. Extract a shared body builder in `SearchExecutionService`
File: `src/main/java/com/monk3/search/SearchExecutionService.java`

Pull the body assembly out of `searchBackend` into a private helper, e.g.:

```java
private ObjectNode buildRequestBody(
        BackendTarget target,
        SearchExecutionRequest request,
        List<FieldProjection> projections,
        List<String> primaryKeys) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set("query", queryTranslationService.translate(target.engine(), target.request()));
    applyResultOptions(target, body, projections, primaryKeys, request);
    if (hasAggregations(request)) {
        body.set(target.engine().aggregationsRequestProperty(),
                queryTranslationService.translateAggregations(
                        target.engine(), target.materialTypes(), request.aggs()));
    }
    return body;
}
```

`searchBackend` keeps computing `projections`/`primaryKeys` (still needed for
response parsing) and now calls `buildRequestBody(...)` before `postJson`. No
behavior change for `/search` — same body, same projections reused for parsing.

### 2. Add a non-executing `parse` method in `SearchExecutionService`

```java
public List<BackendQuery> parse(SearchExecutionRequest request) {
    return queryTranslationService.resolveTargets(request.query()).stream()
            .map(target -> new BackendQuery(
                    target.name(), target.engine(), target.materialTypes(),
                    buildRequestBody(target, request,
                            projections(target.materialTypes(), request.fields()),
                            primaryKeys(target.materialTypes()))))
            .toList();
}
```

This stays pure translation: it never calls `postJson`, so no backend is
contacted. It reuses `resolveTargets`, `projections`, `primaryKeys`,
`buildRequestBody` — all existing code. Aggregation/field validation now runs for
parse too (a bad facet field yields the same `QueryTranslationException` → 400 as
search), which is desirable. Size reflects the effective value (`request.size()`
or the backend `defaultSize()`), exactly as search sends it.

### 3. Point the endpoint at the new method
File: `src/main/java/com/monk3/api/QueryResource.java`

- `parseQuery` body → `return searchExecutionService.parse(request);`
- Remove the now-unused `queryTranslationService` field (only `parseQuery` used
  it; `search` already uses `searchExecutionService`). Confirm no other reference
  before deleting.
- Update the `/parse` `@Operation` description to say it translates the full
  request (query, result fields, size, and aggregations) into each backend's
  native body without executing. Optionally enrich one `@ExampleObject` with
  `size` + `aggs` to show the richer output.

### 4. Update `BackendQuery` field + docs
File: `src/main/java/com/monk3/model/BackendQuery.java`

- Rename the `ObjectNode query` component to `ObjectNode body` (it now holds the
  whole request body, which itself contains a nested `query` key — keeping the
  name `query` would be confusing).
- Update the `@Schema` description ("The full translated request body … including
  query, result options, and aggregations") and the class-level example to a
  complete body (query + size + `_source` + aggs for the ES example).

### 5. Clean up dead code
File: `src/main/java/com/monk3/search/QueryTranslationService.java`

- `translateByBackend(SearchQueryRequest)` becomes unused once `parseQuery`
  switches over. Remove it (verify no other caller first). Keep `resolveTargets`,
  `translate`, `translateAggregations`, `BackendTarget` — still used by
  `SearchExecutionService`.

## Files to modify

- `src/main/java/com/monk3/search/SearchExecutionService.java` — extract
  `buildRequestBody`; add public `parse`.
- `src/main/java/com/monk3/api/QueryResource.java` — call `parse`; drop unused
  field; tweak docs.
- `src/main/java/com/monk3/model/BackendQuery.java` — rename `query` → `body`;
  update schema/example.
- `src/main/java/com/monk3/search/QueryTranslationService.java` — remove unused
  `translateByBackend`.
- `src/test/java/com/monk3/QueryResourceTest.java` — see below.

## Tests

Existing parse-endpoint assertions reference `[n].query.bool...`. With the new
shape they become `[n].body.query.bool...` (the query clause now sits under
`body.query`). Update those, then add coverage for the newly-surfaced parts:

- ES backend: `[0].body.size` (explicit and default-fallback cases),
  `[0].body._source` contains the projected stored fields (+ `material_type` +
  primary key), `[0].body.aggs.<name>.terms.field` for a `terms` agg.
- Solr backend: `[n].body.limit`, `[n].body.fields` (includes `score`),
  `[n].body.facet.<name>.type == "terms"`.
- A subfacets/range agg case to confirm the facet/aggs JSON matches what
  `/search` builds.

Keep the `parseRequest(...)` helper but extend (or add a variant) so tests can
include `size` and `aggs`.

## Verification

- `./gradlew test --tests "com.monk3.QueryResourceTest"` — all parse + search
  tests green.
- `./gradlew build` — full build.
- Manual smoke (optional): `./gradlew quarkusDev`, POST a request with `fields`,
  `size`, and `aggs` to `/queries/parse`, and confirm the returned `body` per
  backend matches the body `/queries/search` would send (same `query`, `size`/
  `limit`, `_source`/`fields`, `aggs`/`facet`).
