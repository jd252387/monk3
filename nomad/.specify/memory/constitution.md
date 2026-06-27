<!--
Sync Impact Report:
- Version change: [none] → 1.0.0 (initial constitution)
- Modified principles: N/A (initial version)
- Added sections: All sections created from template
- Removed sections: None
- Templates requiring updates:
  ✅ plan-template.md: Reviewed - Constitution Check section references this document
  ✅ spec-template.md: Reviewed - No changes needed (focuses on requirements, not implementation)
  ✅ tasks-template.md: Reviewed - TDD principles align with Principle III
  ✅ agent-file-template.md: Not found (no updates needed)
- Follow-up TODOs: None
-->

# Nomad Constitution

## Core Principles

### I. Catalog-Driven Configuration
All system components (datasources, sinks, mappings) MUST be defined in declarative YAML catalogs. Configuration catalogs MUST be version-controlled and independently testable. Runtime selection of components MUST occur via configuration properties (`indexer.data-source`, `indexer.sink`), never hardcoded dependencies.

**Rationale**: Enables runtime flexibility, supports multiple deployment profiles, and ensures configuration changes don't require code modifications.

### II. Reactive Backpressure
All pipeline stages MUST use reactive streams with explicit backpressure handling. Concurrency and prefetch limits MUST be configurable (`pipeline.concurrency`, `pipeline.prefetch`). Blocking operations MUST be avoided in reactive chains; use appropriate schedulers when unavoidable.

**Rationale**: Prevents resource exhaustion, maintains predictable performance under load, and ensures graceful degradation when downstream systems slow down.

### III. Test-First Development (NON-NEGOTIABLE)
TDD is mandatory: Tests MUST be written → User approved → Tests MUST fail → Then implement. The Red-Green-Refactor cycle MUST be strictly enforced. Contract tests MUST be created for all new DataFetchers and IndexSinks before implementation begins.

**Rationale**: Ensures correctness of reactive flows (which are difficult to debug post-facto), validates component contracts, and prevents regressions in the pipeline.

### IV. Pluggable Component Architecture
All extension points (DataFetcher, IndexSink) MUST be interface-based with CDI injection. Components MUST be conditionally registered using `@LookupIfProperty` or equivalent guards. New components MUST NOT break existing implementations or require changes to core pipeline logic.

**Rationale**: Supports adding new datasources and search engines without modifying core pipeline, enables isolated testing, and maintains backward compatibility.

### V. Resilience and Error Boundaries
Each pipeline stage MUST have explicit error handling: deserialization failures → log and acknowledge, fetch/mapping failures → log and acknowledge, search engine errors → retry with exponential backoff until threshold. Error handling MUST prevent pipeline stalls.

**Rationale**: Ensures one bad event doesn't block the entire pipeline, provides observable failure modes, and maintains throughput under partial failures.

### VI. Field-Level Configuration Granularity
Mapping definitions MUST support per-datasource sourcing (jq expressions) and per-sink routing (field name mappings). Field filtering MUST occur at configuration load time to minimize runtime overhead. Nested document mappings MUST be first-class with their own schema and primaryKey sourcing.

**Rationale**: Enables single mapping catalog to support multiple datasources and sinks, optimizes field retrieval (MongoDB projections, REST API field params), and supports complex document hierarchies.

### VII. Observable Pipeline Flow
All pipeline stages MUST emit structured logs with correlation IDs. Performance metrics (throughput, latency, batch sizes) MUST be configurable and observable. Error scenarios MUST be logged with sufficient context for diagnosis (event ID, stage, root cause).

**Rationale**: Enables production debugging of reactive flows, supports capacity planning, and provides visibility into pipeline health.

## Performance and Scale Standards

### Throughput Requirements
- Pipeline MUST support configurable batching (`fetch-batch-size`, `index-batch-size`) to optimize network round-trips
- Concurrency settings MUST be tunable without code changes
- Component implementations MUST NOT introduce blocking calls in reactive chains

### Resource Constraints
- Native executable builds MUST be supported via GraalVM
- Configuration catalogs MUST be loaded once at startup (no runtime reloading)
- Field filtering MUST happen at configuration time, not per-event

## Development Workflow

### Code Quality Gates
- **Formatting**: Palantir Java Format enforced via `spotlessCheck` (runs automatically with `./gradlew check`)
- **Testing**: Unit tests (`./gradlew test`), integration tests (`./gradlew quarkusIntTest`), native tests (`./gradlew testNative`)
- **Contract Verification**: New DataFetchers and IndexSinks MUST have contract tests validating interface compliance

### Contribution Requirements
- All new datasources MUST implement `DataFetcher` with `@ApplicationScoped` and `@LookupIfProperty` guards
- All new search engines MUST implement `IndexSink` with appropriate engine-based guards
- Pipeline modifications MUST NOT break existing catalog configurations

## Governance

### Amendment Procedure
1. Constitution changes MUST be proposed with rationale and impact analysis
2. Version MUST be incremented following semantic versioning:
   - **MAJOR**: Backward-incompatible principle removals or redefinitions
   - **MINOR**: New principle additions or material expansions
   - **PATCH**: Clarifications, wording fixes, non-semantic refinements
3. All dependent templates (plan, spec, tasks) MUST be reviewed for consistency
4. Migration plan MUST be provided for breaking changes

### Compliance Verification
- All PRs MUST verify compliance with Core Principles
- Architecture decisions violating principles MUST be explicitly justified in PR description
- Constitution supersedes all other development practices

### Version Control
- Constitution MUST be version-controlled in `.specify/memory/constitution.md`
- Templates MUST reference current constitution version in footers
- Runtime guidance (CLAUDE.md, README.md) MUST align with constitution principles

**Version**: 1.0.0 | **Ratified**: 2025-10-03 | **Last Amended**: 2025-10-03
