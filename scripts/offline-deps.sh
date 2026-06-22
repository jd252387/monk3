#!/usr/bin/env bash
#
# offline-deps.sh — build and ship a complete offline Gradle dependency bundle.
#
#   download   Resolve every dependency/plugin (jars + poms + .module + -sources + -javadoc)
#              for all subprojects on an internet-connected machine, grab the Gradle
#              distribution, and zip it all up.
#
#   upload     Push a previously built bundle into Artifactory from inside the air-gapped
#              network using the JFrog CLI (jf).
#
# See README-airgap.md (generated into the bundle) for the full air-gapped workflow.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Configuration (override via environment) --------------------------------------------
GRADLE_VERSION="${GRADLE_VERSION:-9.5.0}"
GRADLE_DIST_FILE="gradle-${GRADLE_VERSION}-bin.zip"

# Isolated Gradle home so the harvested cache contains ONLY this project's dependencies.
OFFLINE_GUH="${OFFLINE_GUH:-$REPO_ROOT/.offline-gradle-home}"

# Staging + output locations.
STAGE_DIR="${STAGE_DIR:-$REPO_ROOT/build/offline-bundle}"
OFFLINE_REPO_DIR="$STAGE_DIR/offline-repo"
GRADLE_DIST_DIR="$STAGE_DIR/gradle-dist"
OUTPUT_ZIP="${OUTPUT_ZIP:-$REPO_ROOT/monk3-offline-deps-$(date +%Y%m%d).zip}"

# Artifactory targets (upload mode).
ARTIFACTORY_URL="${ARTIFACTORY_URL:-}"
ARTIFACTORY_MAVEN_REPO="${ARTIFACTORY_MAVEN_REPO:-monk3-offline-maven}"
ARTIFACTORY_GENERIC_REPO="${ARTIFACTORY_GENERIC_REPO:-monk3-offline-generic}"
ARTIFACTORY_TOKEN="${ARTIFACTORY_TOKEN:-}"
ARTIFACTORY_USER="${ARTIFACTORY_USER:-}"
ARTIFACTORY_PASSWORD="${ARTIFACTORY_PASSWORD:-}"
JFROG_SERVER_ID="${JFROG_SERVER_ID:-}"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

usage() {
    cat <<EOF
Usage: $(basename "$0") <download|upload> [options]

download                       Resolve all dependencies and produce the offline bundle zip.
  --no-tests                   Skip test execution (faster; may miss some test-time deps).

upload [bundle.zip|bundle-dir] Upload a bundle to Artifactory via the JFrog CLI (jf).
                               Defaults to $STAGE_DIR if no path is given.

Environment (download):
  GRADLE_VERSION   Gradle version of the wrapper distribution (default: $GRADLE_VERSION)
  OFFLINE_GUH      Isolated Gradle user home (default: $OFFLINE_GUH)
  STAGE_DIR        Bundle staging dir (default: $STAGE_DIR)
  OUTPUT_ZIP       Output zip path (default: monk3-offline-deps-<date>.zip)

Environment (upload):
  ARTIFACTORY_URL          Base Artifactory URL, e.g. https://artifactory.internal/artifactory
  ARTIFACTORY_MAVEN_REPO   Target Maven repo key (default: $ARTIFACTORY_MAVEN_REPO)
  ARTIFACTORY_GENERIC_REPO Target generic repo key for the Gradle dist (default: $ARTIFACTORY_GENERIC_REPO)
  ARTIFACTORY_TOKEN        Access token (preferred), or
  ARTIFACTORY_USER / ARTIFACTORY_PASSWORD
  JFROG_SERVER_ID          Use a preconfigured 'jf' server-id instead of URL/credentials.
EOF
}

# --- download ----------------------------------------------------------------------------

run_gradle_resolution() {
    local skip_tests="$1"
    local -a gradle_args=(
        -g "$OFFLINE_GUH"
        -I "$SCRIPT_DIR/download-offline.init.gradle.kts"
        --refresh-dependencies
        --no-configuration-cache
        build
        resolveOfflineDependencies
    )
    if [ "$skip_tests" = "true" ]; then
        gradle_args+=(-x test)
        log "Resolving dependencies WITHOUT running tests (some test-time deps may be missed)."
    fi
    log "Populating isolated Gradle cache at $OFFLINE_GUH"
    ( cd "$REPO_ROOT" && ./gradlew "${gradle_args[@]}" )
}

harvest_cache() {
    local files_root="$OFFLINE_GUH/caches/modules-2/files-2.1"
    [ -d "$files_root" ] || die "Gradle cache not found at $files_root (did the resolution step run?)"

    log "Harvesting cache into Maven layout: $OFFLINE_REPO_DIR"
    rm -rf "$OFFLINE_REPO_DIR"
    mkdir -p "$OFFLINE_REPO_DIR"

    local count=0 f rel group artifact version filename group_path dest
    while IFS= read -r -d '' f; do
        # Cache layout: files-2.1/<group>/<artifact>/<version>/<sha1>/<filename>
        rel="${f#"$files_root"/}"
        group="${rel%%/*}";    rel="${rel#*/}"
        artifact="${rel%%/*}"; rel="${rel#*/}"
        version="${rel%%/*}";  rel="${rel#*/}"
        filename="${rel#*/}"   # strip the <sha1>/ segment
        group_path="${group//.//}"
        dest="$OFFLINE_REPO_DIR/$group_path/$artifact/$version"
        mkdir -p "$dest"
        cp -n "$f" "$dest/$filename"
        count=$((count + 1))
    done < <(find "$files_root" -type f -print0)

    [ "$count" -gt 0 ] || die "No artifacts harvested — cache appears empty."
    log "Harvested $count files."
}

fetch_gradle_dist() {
    mkdir -p "$GRADLE_DIST_DIR"
    local props="$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"
    local url
    url="$(grep -E '^distributionUrl=' "$props" | cut -d= -f2- | sed 's/\\:/:/g')"
    [ -n "$url" ] || die "Could not read distributionUrl from $props"
    log "Downloading Gradle distribution: $url"
    curl -fL --retry 3 "$url" -o "$GRADLE_DIST_DIR/$GRADLE_DIST_FILE"
}

write_readme() {
    cat > "$STAGE_DIR/README-airgap.md" <<EOF
# monk3 offline dependency bundle

Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) for Gradle $GRADLE_VERSION.

Contents:
- \`offline-repo/\`            Maven-layout repository (jars, poms, .module, -sources, -javadoc, plugin markers)
- \`gradle-dist/$GRADLE_DIST_FILE\`  Gradle distribution
- \`offline.init.gradle.kts\`  Init script that repoints Gradle at Artifactory

## 1. Upload into Artifactory (run inside the air-gapped network)

Create two Artifactory repos: a **Maven** repo (e.g. \`$ARTIFACTORY_MAVEN_REPO\`) and a
**generic** repo (e.g. \`$ARTIFACTORY_GENERIC_REPO\`). Then:

    export ARTIFACTORY_URL="https://artifactory.internal/artifactory"
    export ARTIFACTORY_TOKEN="<access-token>"
    ./offline-deps.sh upload .          # or: ./offline-deps.sh upload monk3-offline-deps-<date>.zip

## 2. Point the build at Artifactory

a) Wrapper distribution — set in \`gradle/wrapper/gradle-wrapper.properties\`:

    distributionUrl=https\\://artifactory.internal/artifactory/$ARTIFACTORY_GENERIC_REPO/gradle/distributions/$GRADLE_DIST_FILE

b) Dependencies + plugins — build with the bundled init script (do NOT pass --offline):

    export ARTIFACTORY_MAVEN_URL="https://artifactory.internal/artifactory/$ARTIFACTORY_MAVEN_REPO"
    ./gradlew -I offline.init.gradle.kts build

## Prerequisite: JDK 25

The build uses a Java 25 toolchain. This project configures no toolchain download resolver,
so Gradle will NOT try to fetch a JDK — it requires JDK 25 to already be installed on the
machine. Ensure a JDK 25 is present (and discoverable, e.g. via JAVA_HOME or
\`org.gradle.java.installations.paths\`).
EOF
}

cmd_download() {
    local skip_tests="false"
    for arg in "$@"; do
        case "$arg" in
            --no-tests) skip_tests="true" ;;
            *) die "Unknown download option: $arg" ;;
        esac
    done

    command -v curl >/dev/null || die "curl is required for download mode."
    command -v zip  >/dev/null || die "zip is required for download mode."

    mkdir -p "$STAGE_DIR"
    run_gradle_resolution "$skip_tests"
    harvest_cache
    fetch_gradle_dist
    cp "$SCRIPT_DIR/offline.init.gradle.kts" "$STAGE_DIR/offline.init.gradle.kts"
    write_readme

    log "Zipping bundle -> $OUTPUT_ZIP"
    rm -f "$OUTPUT_ZIP"
    ( cd "$STAGE_DIR" && zip -qr "$OUTPUT_ZIP" offline-repo gradle-dist offline.init.gradle.kts README-airgap.md )

    log "Done. Bundle: $OUTPUT_ZIP ($(du -h "$OUTPUT_ZIP" | cut -f1))"
}

# --- upload ------------------------------------------------------------------------------

cmd_upload() {
    local input="${1:-}"
    local workdir cleanup=""

    if [ -n "$input" ] && [ -f "$input" ]; then
        command -v unzip >/dev/null || die "unzip is required to expand a bundle zip."
        workdir="$(mktemp -d)"
        cleanup="$workdir"
        log "Expanding $input -> $workdir"
        unzip -q "$input" -d "$workdir"
    elif [ -n "$input" ] && [ -d "$input" ]; then
        workdir="$input"
    elif [ -d "$STAGE_DIR" ]; then
        workdir="$STAGE_DIR"
    else
        die "Provide a bundle zip or directory (or run from a machine that has $STAGE_DIR)."
    fi

    command -v jf >/dev/null || die "jf (JFrog CLI) not found on PATH."
    [ -d "$workdir/offline-repo" ] || die "offline-repo/ not found in $workdir"

    local -a auth=()
    if [ -n "$JFROG_SERVER_ID" ]; then
        auth+=(--server-id="$JFROG_SERVER_ID")
    else
        [ -n "$ARTIFACTORY_URL" ] || die "Set ARTIFACTORY_URL or JFROG_SERVER_ID."
        auth+=(--url="$ARTIFACTORY_URL")
        if [ -n "$ARTIFACTORY_TOKEN" ]; then
            auth+=(--access-token="$ARTIFACTORY_TOKEN")
        elif [ -n "$ARTIFACTORY_USER" ]; then
            auth+=(--user="$ARTIFACTORY_USER" --password="$ARTIFACTORY_PASSWORD")
        else
            die "Set ARTIFACTORY_TOKEN, or ARTIFACTORY_USER + ARTIFACTORY_PASSWORD."
        fi
    fi

    log "Uploading Maven artifacts -> $ARTIFACTORY_MAVEN_REPO"
    ( cd "$workdir" && jf rt upload "${auth[@]}" "offline-repo/(**)" "$ARTIFACTORY_MAVEN_REPO/{1}" )

    if [ -f "$workdir/gradle-dist/$GRADLE_DIST_FILE" ]; then
        log "Uploading Gradle distribution -> $ARTIFACTORY_GENERIC_REPO"
        ( cd "$workdir" && jf rt upload "${auth[@]}" \
            "gradle-dist/$GRADLE_DIST_FILE" "$ARTIFACTORY_GENERIC_REPO/gradle/distributions/" )
    fi

    [ -n "$cleanup" ] && rm -rf "$cleanup"
    log "Upload complete."
}

# --- main --------------------------------------------------------------------------------

main() {
    local mode="${1:-}"
    shift || true
    case "$mode" in
        download) cmd_download "$@" ;;
        upload)   cmd_upload "$@" ;;
        -h|--help|help|"") usage ;;
        *) usage; die "Unknown mode: $mode" ;;
    esac
}

main "$@"
