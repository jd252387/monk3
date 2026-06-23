#!/usr/bin/env bash
#
# copy-by-ext.sh — Recursively copy files matching given extensions.
#
# Walks a source directory, copies every file whose extension is in the
# configured list into a destination directory, and lets you exclude folders.
#
# Usage:
#   ./copy-by-ext.sh -e EXT[,EXT...] -d DEST [options] [SOURCE]
#
# Options:
#   -e, --ext EXTS        Comma-separated extensions to copy (e.g. "json,java,md").
#                         Leading dots are optional. Required.
#   -d, --dest DIR        Destination directory. Required.
#   -x, --exclude DIRS    Comma-separated folder names/paths to exclude.
#                         May be given multiple times.
#   -f, --flatten         Copy all files into DEST without recreating the
#                         source directory structure.
#   -n, --dry-run         Show what would be copied without copying.
#   -h, --help            Show this help and exit.
#
# Positional:
#   SOURCE                Directory to scan (default: current directory).
#
# Examples:
#   ./copy-by-ext.sh -e json,md -d /tmp/out
#   ./copy-by-ext.sh -e .java -d ./out -x build,.git,target src
#   ./copy-by-ext.sh --ext png,jpg --dest ./images --flatten --dry-run .

set -euo pipefail

usage() {
    sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'
    exit "${1:-0}"
}

# --- defaults ---------------------------------------------------------------
exts=()
excludes=()
dest=""
source_dir="."
flatten=0
dry_run=0

# --- argument parsing -------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -e|--ext)
            IFS=',' read -ra parts <<< "$2"
            exts+=("${parts[@]}")
            shift 2
            ;;
        -d|--dest)
            dest="$2"
            shift 2
            ;;
        -x|--exclude)
            IFS=',' read -ra parts <<< "$2"
            excludes+=("${parts[@]}")
            shift 2
            ;;
        -f|--flatten)
            flatten=1
            shift
            ;;
        -n|--dry-run)
            dry_run=1
            shift
            ;;
        -h|--help)
            usage 0
            ;;
        --)
            shift
            break
            ;;
        -*)
            echo "Unknown option: $1" >&2
            usage 1
            ;;
        *)
            source_dir="$1"
            shift
            ;;
    esac
done
# any args after `--`
[[ $# -gt 0 ]] && source_dir="$1"

# --- validation -------------------------------------------------------------
if [[ ${#exts[@]} -eq 0 ]]; then
    echo "Error: at least one extension is required (-e)." >&2
    usage 1
fi
if [[ -z "$dest" ]]; then
    echo "Error: a destination directory is required (-d)." >&2
    usage 1
fi
if [[ ! -d "$source_dir" ]]; then
    echo "Error: source '$source_dir' is not a directory." >&2
    exit 1
fi

# Normalise extensions: strip a leading dot, drop empties.
norm_exts=()
for e in "${exts[@]}"; do
    e="${e#.}"
    [[ -n "$e" ]] && norm_exts+=("$e")
done

# --- build the find expression ----------------------------------------------
find_args=("$source_dir")

# Prune excluded directories. Matches either a path segment by name
# (e.g. "build") or a path prefix (e.g. "src/test").
if [[ ${#excludes[@]} -gt 0 ]]; then
    find_args+=("(")
    first=1
    for x in "${excludes[@]}"; do
        x="${x%/}"
        [[ -z "$x" ]] && continue
        [[ $first -eq 0 ]] && find_args+=("-o")
        find_args+=(-name "$x" -o -path "*/$x" -o -path "*/$x/*")
        first=0
    done
    find_args+=(")" -prune -o)
fi

# Match files by extension (case-insensitive).
find_args+=("-type" "f" "(")
for i in "${!norm_exts[@]}"; do
    [[ $i -gt 0 ]] && find_args+=("-o")
    find_args+=(-iname "*.${norm_exts[$i]}")
done
find_args+=(")" "-print0")

# --- copy loop --------------------------------------------------------------
count=0
while IFS= read -r -d '' file; do
    if [[ $flatten -eq 1 ]]; then
        target="$dest/$(basename "$file")"
    else
        rel="${file#"$source_dir"/}"
        target="$dest/$rel"
    fi

    if [[ $dry_run -eq 1 ]]; then
        echo "would copy: $file -> $target"
    else
        mkdir -p "$(dirname "$target")"
        cp -p "$file" "$target"
        echo "copied: $file -> $target"
    fi
    count=$((count + 1))
done < <(find "${find_args[@]}")

if [[ $dry_run -eq 1 ]]; then
    echo "Dry run complete: $count file(s) would be copied."
else
    echo "Done: $count file(s) copied to '$dest'."
fi
