# Taskfile Usage Guide

This repository ships with a [Taskfile](Taskfile.yml) at the repository root to streamline common development workflows around Docker Compose, Kompose, and document utilities. Run `task` from the repository root. The following sections describe the available tasks, the variables they accept, and how those variables influence behaviour.

## Docker Compose Tasks

All Compose tasks support a common set of variables:

| Variable | Default | Description |
| --- | --- | --- |
| `PROFILE` | _unset_ | Comma-separated list of profiles to enable. When omitted, the default is to include every profile from `PROFILES`. |
| `ALL` | `false` | When set to `true`, the task includes every profile regardless of `PROFILE`. |
| `DOCKER_ARGS` | Task-specific | Extra arguments appended to the `docker compose` command. |
| `EVENT_DS` | _unset_ | Optional data source name forwarded as `REPLICATOR_EVENT_DATA_SOURCE` for the documents replicator. When provided, the syncer publishes Kafka indexing events using the specified source. |

### `jib:build`

Builds the monk3 and nomad container images with [Quarkus Jib](https://quarkus.io/guides/container-image#jib)
directly into the local Docker daemon (no Dockerfile). A running Docker daemon is required.

- Default command: `./gradlew :monk3:build :nomad:build -Dquarkus.container-image.build=true -x test`
- `PROJECTS` (default `:monk3:build :nomad:build`) — narrow to one image, e.g. `PROJECTS=':monk3:build'`.
- `GRADLE_ARGS` (default `-x test`) — extra Gradle args; pass `GRADLE_ARGS=''` to also run tests.
- Produces `monk3/monk3:latest` and `monk3/nomad:latest` (group/name/tag from each app's `application.yaml`).

### `compose:up`

Starts the Docker Compose stack. The `monk3` and `nomad` services run the Jib-built `monk3/monk3:latest`
and `monk3/nomad:latest` images, so this task first builds them into the local Docker daemon
(`task jib:build PROJECTS=':monk3:build :nomad:build'`) before `docker compose ... up`, ensuring Compose
finds them locally instead of trying to pull them.

- Default command: `./gradlew :monk3:build :nomad:build -Dquarkus.container-image.build=true -x test` then `docker compose --profile ... up -d`
- Overrides: pass `DOCKER_ARGS` for flags like `--remove-orphans`.

### `compose:build`

Builds images for the selected profiles without starting the stack.

- Default command: `docker compose --profile ... build`
- Useful when you want to prepare images prior to deployment.

### `compose:rebuild`

Rebuilds images and then starts the stack by chaining `compose:build` and `compose:up`.

- Accepts the same variables as the underlying tasks.
- Equivalent to running `task compose:build` followed by `task compose:up`.

### `compose:down`

Stops the Docker Compose stack for all profiles.

| Variable | Default | Description |
| --- | --- | --- |
| `DOCKER_ARGS` | `--remove-orphans` | Extra flags added to `docker compose down`. |

### Additional Notes

- When `PROFILE` is supplied, you can target a subset of services, e.g. `task compose:up -- PROFILE=streaming,mongo`.
- Setting `ALL=true` forces all profiles to be included regardless of `PROFILE`, mirroring `docker compose --profile <all-profiles>`.
- The special `event-datasource` flag controls whether the documents replicator emits Kafka events. For example: `task compose:up -- event-datasource=rest`.

## Kompose Conversion

### `kompose:convert`

Converts the Compose project into Kubernetes manifests using Kompose.

| Variable | Default | Description |
| --- | --- | --- |
| `OUTPUT_DIR` | `kompose-manifests` | Destination directory for the generated manifests. |
| `APPLY` | `false` | When `true`, runs `kubectl apply -f <OUTPUT_DIR>` after conversion. |

Example:

```bash
task kompose:convert -- OUTPUT_DIR=dist/manifests APPLY=true
```

## Document Utilities

### `generate:document`

Generates one or more example documents from a field mapping YAML file.

| Variable | Default | Description |
| --- | --- | --- |
| `MAPPING` | _required_ | Path to the mapping YAML file. This is the only required variable for the task. |
| `OUTPUT_DIR` | `nomad/documents` | Directory where generated JSON documents are written. Created automatically when missing. |
| `MULTI` | `false` | When `true`, creates multiple sample documents instead of a single output. |
| `EXCLUDE_MISSING` | `false` | When `true`, omits fields that have no sample data in the mapping definitions. |

Example:

```bash
task generate:document -- MAPPING=src/main/resources/mapping/document.yaml MULTI=true OUTPUT_DIR=tmp/docs
```

## Tips

- To inspect the exact command that will run, execute the task with `--dry` to print the resolved command without running it.
- Combine multiple variable overrides as needed, e.g. `task compose:up -- PROFILE=streaming DOCKER_ARGS="--build" event-datasource=rest`.
- `PROFILES` and `PROFILE_FLAGS` are typically left untouched; override them only when introducing new profiles or bespoke Compose workflows.

