#!/usr/bin/env bash
#
# copy-default.sh — Convenience wrapper around copy-by-ext.sh with project
# defaults: copy yaml/java/kts sources into ./extracted, skipping build output.
#
# Any extra arguments are forwarded to copy-by-ext.sh, so you can still pass
# a SOURCE directory or flags like --dry-run / --flatten, e.g.:
#   ./copy-default.sh --dry-run monk3

set -euo pipefail

# Resolve this script's directory so it works from any working directory.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$script_dir/copy-by-ext.sh" \
    --ext yaml,java,kts,json,gradle \
    --dest extracted \
    --exclude bin,build,.vscode,.quarkus,docker,extracted \
    "$@"
