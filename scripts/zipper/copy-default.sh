#!/usr/bin/env bash
#
# copy-default.sh — Convenience wrapper around copy-by-ext.sh with project
# defaults: copy yaml/java/kts sources into ./extracted, skipping build output,
# then zip ./extracted into ./extracted.zip.
#
# Any extra arguments are forwarded to copy-by-ext.sh, so you can still pass
# a SOURCE directory or flags like --dry-run / --flatten, e.g.:
#   ./copy-default.sh --dry-run monk3

set -euo pipefail

# Resolve this script's directory so it works from any working directory.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

dest="extracted"

"$script_dir/copy-by-ext.sh" \
    --ext yaml,java,kts,json,gradle \
    --dest "$dest" \
    --exclude bin,build,.vscode,.quarkus,docker,extracted \
    "../.."

# Nothing was actually copied on a dry run, so there's nothing to zip.
for arg in "$@"; do
    case "$arg" in
        -n|--dry-run) exit 0 ;;
    esac
done

# Zip the destination into <dest>.zip, replacing any previous archive so it
# doesn't retain files that are no longer present.
rm -f "$dest.zip"
zip -r "$dest.zip" "$dest"
echo "Zipped '$dest' into '$dest.zip'."
