# Taskfile Usage Guide

This repository ships with a [Taskfile](Taskfile.yml) at the repository root to streamline common development workflows around Docker Compose, Kompose, and document utilities. Run `task` from the repository root. The following sections describe the available tasks, the variables they accept, and how those variables influence behaviour.

## Docker Compose Tasks

The Compose tasks that start or build the stack — `compose:up`, `compose:build`, and `compose:rebuild` — support a common set of variables (`compose:down` ignores them):

| Variable | Default | Description |
| --- | --- | --- |
| `PROFILE` | `streaming` | Comma-separated list of profiles to enable. Each name becomes a `docker compose --profile <name>` flag and is added to `REPLICATOR_ONLINE_DATA_SOURCES`. Available profiles: `streaming`, `mongo`, `s3`, `hbase`, `rest`. |
| `ALL` | `false` | When set to `true`, the task includes every available profile (`streaming,mongo,s3,hbase,rest`), overriding `PROFILE`. |
| `DOCKER_ARGS` | Task-specific | Extra arguments appended to the `docker compose` command. |
| `EVENT_DS` | _unset_ | Optional data source name forwarded as `REPLICATOR_EVENT_DATA_SOURCE` for the documents replicator. When provided, the syncer publishes Kafka indexing events from the specified source; when unset, no event data source is configured. |

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

### Selecting profiles and data sources (`PROFILE`, `ALL`, `EVENT_DS`)

`PROFILE`, `ALL`, and `EVENT_DS` are honoured by every Compose task that starts or builds the stack — `compose:up`, `compose:build`, and `compose:rebuild` (`compose:down` ignores them and stops whatever is running). Internally these tasks expand to:

```bash
REPLICATOR_ONLINE_DATA_SOURCES=<profiles> [REPLICATOR_EVENT_DATA_SOURCE=<EVENT_DS>] \
  docker compose [--profile <p1> --profile <p2> …] <command> <DOCKER_ARGS>
```

A profile name therefore does double duty: it is passed to Docker Compose as a `--profile` flag *and* listed in `REPLICATOR_ONLINE_DATA_SOURCES`, the set of data sources the documents replicator keeps online.

#### `PROFILE`

Comma-separated list of profiles to enable; defaults to `streaming` when unset. The available profiles are `streaming`, `mongo`, `s3`, `hbase`, and `rest`. Use commas with no surrounding spaces.

```bash
# Default: only the `streaming` profile
task compose:up
#   -> REPLICATOR_ONLINE_DATA_SOURCES=streaming docker compose --profile streaming up -d

# A subset of profiles
task compose:up PROFILE=streaming,mongo
#   -> REPLICATOR_ONLINE_DATA_SOURCES=streaming,mongo \
#        docker compose --profile streaming --profile mongo up -d
```

#### `ALL`

When `true`, enables every available profile (`streaming,mongo,s3,hbase,rest`) and overrides whatever `PROFILE` is set to. Defaults to `false`.

```bash
# Every profile
task compose:up ALL=true
#   -> REPLICATOR_ONLINE_DATA_SOURCES=streaming,mongo,s3,hbase,rest \
#        docker compose --profile streaming --profile mongo --profile s3 \
#          --profile hbase --profile rest up -d

# PROFILE is ignored when ALL=true
task compose:up ALL=true PROFILE=mongo   # still enables all five profiles
```

#### `EVENT_DS`

Names the single data source that should emit Kafka indexing events. When set, the command is prefixed with `REPLICATOR_EVENT_DATA_SOURCE=<value>`; when unset, no event data source is configured and the replicator emits no events. `EVENT_DS` is typically one of the profiles you enabled.

```bash
# Bring up streaming + rest, and have the `rest` data source publish indexing events
task compose:up PROFILE=streaming,rest EVENT_DS=rest
#   -> REPLICATOR_ONLINE_DATA_SOURCES=streaming,rest REPLICATOR_EVENT_DATA_SOURCE=rest \
#        docker compose --profile streaming --profile rest up -d
```

> Pass these as plain `KEY=value` task variables (e.g. `task compose:up PROFILE=mongo`), not after a `--`. Add `--dry` to print the fully resolved `docker compose` command without running it.

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
- Combine multiple variable overrides as needed, e.g. `task compose:up PROFILE=streaming,rest EVENT_DS=rest DOCKER_ARGS="--build"`.
- `AVAILABLE_PROFILES` (the master list `streaming,mongo,s3,hbase,rest` that `ALL=true` expands to) is typically left untouched; override it only when introducing new profiles.

