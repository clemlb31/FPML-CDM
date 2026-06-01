#!/usr/bin/env python3
"""
scripts/build_cdm_hierarchy.py
-------------------------------
Reads CDM 6.x Rosetta DSL files from reference/cdm/rosetta/
and writes a human-readable type hierarchy to reference/cdm/hierarchy.txt.

Run once after fetch_references.sh:
    python scripts/build_cdm_hierarchy.py

The output is committed to the repo and read by cdm_server at runtime.
Nothing is fetched from the internet at runtime.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT  = Path(__file__).parent.parent
ROSETTA_DIR = REPO_ROOT / "reference" / "cdm" / "rosetta"
OUT_FILE    = REPO_ROOT / "reference" / "cdm" / "hierarchy.txt"

# Files to process (order matters — processed top-to-bottom)
ROSETTA_FILES = [
    "event-common-type.rosetta",
    "product-template-type.rosetta",
    "product-asset-type.rosetta",
    "product-asset-floatingrate-type.rosetta",
    "product-common-settlement-type.rosetta",
    "base-staticdata-party-type.rosetta",
    "base-datetime-type.rosetta",
    "observable-asset-type.rosetta",
]

# Match top-level type definitions: type Name [extends Parent] [: <"doc">]
_TYPE_RE  = re.compile(
    r'(?:^|\n)\s*type\s+(\w+)'
    r'(?:\s+extends\s+(\w+))?'
    r'\s*(?::\s*<"([^"]*)")?',
    re.MULTILINE,
)
# Match field definitions inside a type block (4+ spaces indent)
_FIELD_RE = re.compile(
    r'^\s{4,}(\w+)\s+([\w<>]+(?:\.\w+)?)\s+\(([^)]+)\)',
    re.MULTILINE,
)


def parse_rosetta(source: str) -> list[dict]:
    types = []
    for m in _TYPE_RE.finditer(source):
        name, parent, doc = m.group(1), m.group(2), m.group(3)
        block_start = m.end()
        next_m = _TYPE_RE.search(source, block_start)
        block  = source[block_start: next_m.start() if next_m else len(source)]

        fields = [
            {"field": fm.group(1), "type": fm.group(2), "cardinality": fm.group(3)}
            for fm in _FIELD_RE.finditer(block)
        ]
        types.append({
            "name":   name,
            "parent": parent,
            "doc":    (doc or "").strip()[:120],
            "fields": fields[:25],
        })
    return types


def build_text(all_types: list[dict], file_count: int) -> str:
    lines = [
        "# CDM 6.x Type Hierarchy",
        "# Source: FINOS CDM GitHub (branch 6.x.x), parsed from Rosetta DSL",
        "# Built by: python scripts/build_cdm_hierarchy.py",
        "#",
        "# How to read:",
        "#   type TypeName extends Parent   → Java class TypeName extends Parent",
        "#   field: FieldType (1..1)        → required; (0..1) optional; (0..*) list",
        "#   Java builder: TypeName.builder().setField(v).build()",
        "#   Java list:    TypeName.builder().addField(item).build()  (for 0..*)",
        f"#",
        f"# {len(all_types)} types from {file_count} Rosetta files",
        "",
    ]

    for t in all_types:
        parent_str = f" extends {t['parent']}" if t["parent"] else ""
        doc_str    = f"  # {t['doc']}" if t["doc"] else ""
        lines.append(f"type {t['name']}{parent_str}{doc_str}")
        for f in t["fields"]:
            card = f["cardinality"]
            if card in ("1..1", "1"):
                tag = "required"
            elif "0..1" in card:
                tag = "optional"
            elif "0..*" in card or "1..*" in card:
                tag = "list"
            else:
                tag = card
            lines.append(f"    {f['field']}: {f['type']} ({tag})")
        lines.append("")

    return "\n".join(lines)


def main() -> None:
    if not ROSETTA_DIR.exists():
        sys.exit(f"ERROR: {ROSETTA_DIR} does not exist — run scripts/fetch_references.sh first")

    all_types: list[dict] = []
    loaded: list[str]     = []
    missing: list[str]    = []

    for filename in ROSETTA_FILES:
        path = ROSETTA_DIR / filename
        if not path.exists():
            print(f"  MISSING (skip): {filename}")
            missing.append(filename)
            continue
        raw    = path.read_text(encoding="utf-8")
        types  = parse_rosetta(raw)
        all_types.extend(types)
        loaded.append(filename)
        print(f"  parsed {len(types):3d} types ← {filename}")

    if not all_types:
        sys.exit("ERROR: no types parsed — check that reference/cdm/rosetta/ is populated")

    text = build_text(all_types, len(loaded))
    OUT_FILE.write_text(text, encoding="utf-8")

    print(f"\nWrote {len(all_types)} types ({len(text)} chars) → {OUT_FILE.relative_to(REPO_ROOT)}")
    if missing:
        print(f"Skipped {len(missing)} missing files: {', '.join(missing)}")
        print("Run scripts/fetch_references.sh to download them.")


if __name__ == "__main__":
    main()
