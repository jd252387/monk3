#!/usr/bin/env python3
"""
Migrate an old-style mapping file (config/old/old-mapping.json) into the new
two-file format:

  1. <name>.mapping.json  — physical fields (type + destinationField)
  2. <name>.virtual.json  — virtual fields (expansion templates for link fields)

Old format:
{
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
        },
        "<fieldName>": {
            "isDataFromFilter": true,      // → predicate virtual field
            "filters": [                   //   (static, accepts no user data)
            { "field": "", "data": [
                { "field": "<ref>", "data": "<value>", "bool": "must" }
            ] }
            ]
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

# filterTypes that become virtual delegation fields (type → new virtual field type).
# These do NOT produce physical fields; instead they delegate to a sibling physical
# field that shares the same `path`. "duration" forwards the user's data to that
# field; "exists" expands statically to an exists query (a predicate accepting no data).
DELEGATING_FILTER_TYPES: dict[str, str] = {
    "exists":   "predicate",
    "duration": "number",
}

# Solr dynamic-field suffix marking a boolean field. A physical field whose `path`
# ends with this is forced to type "boolean" regardless of its old filterType, which
# is frequently mislabeled (e.g. "is_original" carries filterType "number" but its
# path "is_original_b" is a Solr boolean field).
BOOLEAN_PATH_SUFFIX = "_b"

# Keys that identify a real field-config object. If a field's value is a dict
# lacking all of these, it is assumed to be wrapped under an arbitrary key
# (e.g. "mapping_name") and the first nested value is unwrapped.
_CONFIG_KEYS = {"filterType", "isMultipleLink", "isDataFromFilter"}


def _unwrap_field(cfg: Any) -> Any:
    """Strip an arbitrary wrapper key if the field config is nested one level deep."""
    if isinstance(cfg, dict) and not (_CONFIG_KEYS & cfg.keys()):
        inner = next(iter(cfg.values()), None)
        if isinstance(inner, dict):
            return inner
    return cfg


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


def _exists_expansion(delegated_field: str) -> dict[str, Any]:
    """Static expansion matching documents where the delegated field has any value."""
    return {"field": delegated_field, "data": {"type": "exists"}}


def _boolean_expansion(data: list[list[dict[str, Any]]]) -> dict[str, Any]:
    return {"field": "", "data": data}


def _is_exact_leaf(node: dict[str, Any]) -> bool:
    """True if `node` is a positive leaf clause carrying an `exact` query payload.

    Such clauses are the only ones eligible for same-field value merging: boolean
    sub-nodes (empty field), negated clauses (`isNot`), and `text`/freetext
    payloads are excluded.
    """
    data = node.get("data")
    return (
        node.get("field", "") != ""
        and not node.get("isNot")
        and isinstance(data, dict)
        and data.get("type") == "exact"
    )


def _merge_should_exact_branches(branches: list[list[dict[str, Any]]]) -> list[list[dict[str, Any]]]:
    """Collapse OR branches that are a single same-field `exact` leaf.

    Multiple OR'd single-value exact matches on one field are equivalent to one
    exact query holding all the values. For each field, the first such branch is
    kept and later same-field branches have their values appended to it (in
    first-occurrence order) and are dropped. All other branches keep their place.
    """
    merged: list[list[dict[str, Any]]] = []
    first_by_field: dict[str, dict[str, Any]] = {}

    for branch in branches:
        node = branch[0] if len(branch) == 1 else None
        if node is not None and _is_exact_leaf(node):
            field = node["field"]
            existing = first_by_field.get(field)
            if existing is not None:
                existing["data"]["values"].extend(node["data"]["values"])
                continue
            first_by_field[field] = node
        merged.append(branch)

    return merged


def _value_to_payload(ref_field: str, value: Any, all_fields: dict[str, Any]) -> dict[str, Any]:
    """Wrap an old bare filter value in a typed QueryPayload based on the referenced field's type."""
    ref_cfg = all_fields.get(ref_field, {})
    new_type = FILTER_TYPE_MAP.get(ref_cfg.get("filterType", "term"), "string")
    if new_type == "freetext":
        return {"type": "text", "phrases": [value]}
    return {"type": "exact", "values": [value]}


def _convert_filter_node(node: dict[str, Any], all_fields: dict[str, Any]) -> tuple[dict[str, Any], str]:
    """Convert an old filter node to a new QueryNode dict, returning (node, bool_kind).

    The node's own `bool` is returned separately so the parent can place it; it is
    consumed here and not emitted on the new node (negation is applied by the parent).

    A leaf node carrying no `data` becomes an `exists` query (matches documents where
    the field has any value).
    """
    bool_kind = node.get("bool", "should")
    field = node.get("field", "")
    if field == "":
        new_node = {"field": "", "data": _build_bool_data(node.get("data", []), all_fields)}
    elif "data" not in node:
        new_node = {"field": field, "data": {"type": "exists"}}
    else:
        new_node = {"field": field, "data": _value_to_payload(field, node.get("data"), all_fields)}
    return new_node, bool_kind


def _build_bool_data(children: list[dict[str, Any]], all_fields: dict[str, Any]) -> list[list[dict[str, Any]]]:
    """Group old filter children into the new list-of-lists (outer OR, inner AND).

    must     → single inner AND group
    mustNot  → same AND group, negated via isNot
    should   → its own OR branch
    """
    and_clauses: list[dict[str, Any]] = []
    should_branches: list[list[dict[str, Any]]] = []

    for child in children:
        new_node, bool_kind = _convert_filter_node(child, all_fields)
        if bool_kind == "must":
            and_clauses.append(new_node)
        elif bool_kind == "mustNot":
            and_clauses.append({**new_node, "isNot": True})
        else:  # "should" (or missing bool) → separate OR branch
            should_branches.append([new_node])

    data: list[list[dict[str, Any]]] = []
    if and_clauses:
        data.append(and_clauses)
    data.extend(_merge_should_exact_branches(should_branches))
    return data


def migrate(old_path: str, output_dir: str, material_type: str, primary_key: str = "id") -> None:
    """Convert an old mapping file into .mapping.json + .virtual.json."""

    # ── 1. Load old file ──────────────────────────────────────────────
    with open(old_path, "r", encoding="utf-8") as fh:
        old = json.load(fh)

    fields: dict[str, Any] = old.get("fields", {})
    if not fields:
        print("Warning: no fields found under fields — output will be empty.")

    # Unwrap any arbitrary wrapper key (e.g. "mapping_name") around each field config.
    fields = {name: _unwrap_field(cfg) for name, cfg in fields.items()}

    physical: dict[str, Any] = {}
    virtual:  dict[str, Any] = {}

    # ── 2. Pass 1 — collect physical fields, build path → logical-name index ──
    path_to_logical: dict[str, str] = {}
    delegating: list[tuple[str, dict[str, Any]]] = []     # deferred for pass 2
    link_fields: list[tuple[str, dict[str, Any]]] = []    # deferred for pass 2
    filter_fields: list[tuple[str, dict[str, Any]]] = []  # deferred for pass 2

    for field_name, cfg in fields.items():
        if cfg.get("isDataFromFilter"):
            filter_fields.append((field_name, cfg))
            continue

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

        # A "_b"-suffixed Solr path is physically boolean even if the old
        # filterType says otherwise (e.g. "is_original" → number/is_original_b).
        if path.endswith(BOOLEAN_PATH_SUFFIX):
            new_type = "boolean"

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
        expansion = _exists_expansion(delegated) if ft == "exists" else _leaf_expansion(delegated)
        virtual[field_name] = {
            "type": DELEGATING_FILTER_TYPES[ft],
            "expansion": expansion,
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

    # Predicate virtuals (isDataFromFilter) — static, accept no user data
    for field_name, cfg in filter_fields:
        filters: list[dict[str, Any]] = cfg.get("filters", [])
        if not filters:
            print(f"Warning: '{field_name}' (isDataFromFilter) has no filters — skipping.")
            continue

        if len(filters) == 1:
            expansion, _ = _convert_filter_node(filters[0], fields)
        else:
            # Multiple top-level filters → OR them under a boolean root,
            # merging same-field exact `should` filters into one query.
            branches = [[_convert_filter_node(f, fields)[0]] for f in filters]
            expansion = _boolean_expansion(_merge_should_exact_branches(branches))

        virtual[field_name] = {
            "type": "predicate",
            "expansion": expansion,
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
    n_predicates = len([f for f, _ in filter_fields if f in virtual])
    print(f"✔  {mapping_path}  ({len(physical)} physical field(s))")
    print(
        f"✔  {virtual_path}  ({len(virtual)} virtual field(s) — "
        f"{n_links} from links, {n_delegating} delegating, {n_predicates} predicate)"
    )


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
