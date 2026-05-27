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
    "term":      "string",
    "freetext":  "freetext",
    "number":    "number",
    "datetime":  "datetime",
    "boolean":   "boolean",
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

    # ── 2. Classify each field ────────────────────────────────────────
    for field_name, cfg in fields.items():
        if cfg.get("isMultipleLink"):
            # ── Virtual (link) field ──────────────────────────────────
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

            # Build the boolean QueryNode data (list-of-lists).
            # Outer list  = OR  / should
            # Inner lists = AND / must
            data: list[list[dict[str, Any]]] = []
            if must_clauses:
                data.append(must_clauses)
            data.extend(should_clauses)

            virtual[field_name] = {
                "type": _infer_virtual_type(linked, fields),
                "expansion": {
                    "field": "",        # empty → boolean node
                    "data":  data,
                },
            }
        else:
            # ── Physical field ────────────────────────────────────────
            ft = cfg.get("filterType", "term")
            new_type = FILTER_TYPE_MAP.get(ft, "string")
            physical[field_name] = {
                "type": new_type,
                "destinationField": cfg.get("path", field_name),
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

    print(f"✔  {mapping_path}  ({len(physical)} physical field(s))")
    print(f"✔  {virtual_path}  ({len(virtual)} virtual field(s))")


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
