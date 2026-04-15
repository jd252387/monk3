#!/usr/bin/env python3
"""
Migrate an old-style mapping file (config/old/old-mapping.json) into the new
two-file format:

  1. <name>.mapping.json  — physical fields (type + destinationField)
  2. <name>.virtual.json  — virtual fields (expansion templates for link fields)

Old format:
  {
    "settings": {
      "fields": {
        "<fieldName>": {
          "filterType": "term",          // → physical field (type: "string")
          "path": "<indexField>"         // → destinationField
        },
        "<fieldName>": {
          "isMultipleLink": true,        // → virtual field
          "fields": [
            { "linkedField": "<ref>", "bool": "should" },
            { "linkedField": "<ref>", "bool": "must"   }
          ]
        }
      }
    }
  }

Usage:
  python migrate_mapping.py config/old/old-mapping.json -n mytype -o config/mappings
"""

import json
import os
import sys
from typing import Any


# Map old filterType values to new mapping type enum values
FILTER_TYPE_MAP: dict[str, str] = {
    "term":       "string",
    "freetext":   "freetext",
    "number":     "number",
    "datetime":   "datetime",
    "date":       "datetime",
    "boolean":    "boolean",
    "contentNew": "freetext",
}

# filterTypes that become virtual delegation fields (type → new virtual field type)
# These do NOT produce physical fields; instead they delegate to a sibling physical
# field that shares the same `path`.
DELEGATING_FILTER_TYPES: dict[str, str] = {
    "exists":   "boolean",
    "duration": "number",
}


def _infer_virtual_type(linked_fields: list[dict[str, Any]], all_fields: dict[str, Any]) -> str:
    """Guess the virtual field's type from the first linked field that has a known filterType."""
    for link in linked_fields:
        ref = link.get("linkedField", "")
        ref_cfg = all_fields.get(ref, {})
        ft = ref_cfg.get("filterType", "")
        if ft in FILTER_TYPE_MAP:
            return FILTER_TYPE_MAP[ft]
    return "string"


def _leaf_expansion(delegated_field: str) -> dict[str, Any]:
    return {"field": delegated_field, "data": "{{data}}"}


def _boolean_expansion(data: list[list[dict[str, Any]]]) -> dict[str, Any]:
    return {"field": "", "data": data}


def migrate(old_path: str, output_dir: str, material_type: str, primary_key: str = "id") -> None:
    """Convert an old mapping file into .mapping.json + .virtual.json."""

    # ── 1. Load old file ──────────────────────────────────────────────
    with open(old_path, "r", encoding="utf-8") as fh:
        old = json.load(fh)

    fields: dict[str, Any] = old.get("settings", {}).get("fields", {})
    if not fields:
        print("Warning: no fields found under settings.fields — output will be empty.")

    physical: dict[str, Any] = {}
    virtual:  dict[str, Any] = {}

    # ── 2. Pass 1 — collect physical fields, build path → logical-name index ──
    path_to_logical: dict[str, str] = {}
    delegating: list[tuple[str, dict[str, Any]]] = []   # deferred for pass 2
    link_fields: list[tuple[str, dict[str, Any]]] = []  # deferred for pass 2

    for field_name, cfg in fields.items():
        if cfg.get("isMultipleLink"):
            link_fields.append((field_name, cfg))
            continue

        ft = cfg.get("filterType", "term")

        if ft in DELEGATING_FILTER_TYPES:
            delegating.append((field_name, cfg))
            continue

        new_type = FILTER_TYPE_MAP.get(ft)
        if new_type is None:
            print(f"Warning: unknown filterType '{ft}' for field '{field_name}', defaulting to string.")
            new_type = "string"

        path = cfg.get("path", field_name)

        if path in path_to_logical:
            print(
                f"Warning: fields '{field_name}' and '{path_to_logical[path]}' share path '{path}' "
                f"— keeping '{path_to_logical[path]}'."
            )
        else:
            path_to_logical[path] = field_name

        physical[field_name] = {
            "type": new_type,
            "destinationField": path,
        }

    # ── 3. Pass 2 — build virtual fields ─────────────────────────────

    # Delegating virtuals (exists, duration)
    for field_name, cfg in delegating:
        ft = cfg.get("filterType", "")
        path = cfg.get("path", field_name)
        delegated = path_to_logical.get(path)
        if delegated is None:
            print(
                f"Warning: '{field_name}' (filterType '{ft}') points to path '{path}', "
                f"no matching physical field — skipping."
            )
            continue
        virtual[field_name] = {
            "type": DELEGATING_FILTER_TYPES[ft],
            "expansion": _leaf_expansion(delegated),
        }

    # Boolean-link virtuals (isMultipleLink)
    for field_name, cfg in link_fields:
        linked: list[dict[str, Any]] = cfg.get("fields", [])
        must_clauses: list[dict[str, Any]] = []
        should_clauses: list[list[dict[str, Any]]] = []

        for link in linked:
            clause: dict[str, Any] = {
                "field": link["linkedField"],
                "data":  "{{data}}",
            }
            if link.get("bool") == "must":
                must_clauses.append(clause)
            else:
                # "should" (or missing bool) → separate OR branch
                should_clauses.append([clause])

        # Outer list = OR / should, inner lists = AND / must
        data: list[list[dict[str, Any]]] = []
        if must_clauses:
            data.append(must_clauses)
        data.extend(should_clauses)

        virtual[field_name] = {
            "type": _infer_virtual_type(linked, fields),
            "expansion": _boolean_expansion(data),
        }

    # ── 3. Write output files ─────────────────────────────────────────
    os.makedirs(output_dir, exist_ok=True)

    mapping_doc: dict[str, Any] = {
        "$schema":    "../../mappings.schema.json",
        "primaryKey": primary_key,
        "root":       physical,
    }

    virtual_doc: dict[str, Any] = {
        "$schema": "../../virtual-mapping.schema.json",
        "root":    virtual,
    }

    mapping_path = os.path.join(output_dir, f"{material_type}.mapping.json")
    virtual_path = os.path.join(output_dir, f"{material_type}.virtual.json")

    with open(mapping_path, "w", encoding="utf-8") as fh:
        json.dump(mapping_doc, fh, indent=2)
        fh.write("\n")

    with open(virtual_path, "w", encoding="utf-8") as fh:
        json.dump(virtual_doc, fh, indent=2)
        fh.write("\n")

    n_links = len(link_fields)
    n_delegating = len([f for f, _ in delegating if f in virtual])
    print(f"✔  {mapping_path}  ({len(physical)} physical field(s))")
    print(f"✔  {virtual_path}  ({len(virtual)} virtual field(s) — {n_links} from links, {n_delegating} delegating)")


# ── CLI ────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Migrate old mapping JSON → .mapping.json + .virtual.json"
    )
    parser.add_argument(
        "input",
        help="Path to the old mapping JSON file (e.g. config/old/old-mapping.json)",
    )
    parser.add_argument(
        "-o", "--output-dir",
        default="config/mappings",
        help="Directory for output files (default: config/mappings)",
    )
    parser.add_argument(
        "-n", "--name",
        default="migrated",
        help="Material-type name stem for output files (default: migrated)",
    )
    parser.add_argument(
        "-k", "--primary-key",
        default="id",
        help="Primary-key field name (default: id)",
    )

    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f"Error: input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    migrate(args.input, args.output_dir, args.name, args.primary_key)
