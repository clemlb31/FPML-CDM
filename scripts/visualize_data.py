#!/usr/bin/env python3
"""
scripts/visualize_data.py
--------------------------
Pretty-prints a FpML/CDM training pair side by side.

Usage:
    python scripts/visualize_data.py                               # default pair
    python scripts/visualize_data.py ird-ex02-stub-amort-swap     # by stem
    python scripts/visualize_data.py --list                        # list all available stems

Output:
  ── FpML XML tree (key IRS fields highlighted) ──────────────────
  ── CDM JSON tree (compact with depth limit)  ──────────────────

No extra dependencies — uses stdlib xml.etree.ElementTree + json.
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# ── paths ──────────────────────────────────────────────────────────────────────
ROOT       = Path(__file__).parent.parent
_SUITE     = "interest-rate-derivatives-5-13"   # any dir under data/test/
FPML_DIR   = ROOT / "data" / "test" / _SUITE / "fpml"
CDM_DIR    = ROOT / "data" / "test" / _SUITE / "cdm"
DEFAULT    = "ird-ex01a-vanilla-swap"

# Fields we want to highlight in the FpML tree (case-insensitive suffix match)
_HIGHLIGHT = {
    "effectivedate", "terminationdate", "notionalamount", "fixedrate",
    "floatingrateindex", "daycountfraction", "paymentfrequency",
    "currency", "partyreference", "buysell", "payerreceiver",
}

# ANSI colours (disabled automatically when stdout is not a tty)
_GREEN  = "\033[32m"
_YELLOW = "\033[33m"
_CYAN   = "\033[36m"
_RESET  = "\033[0m"

_USE_COLOUR = sys.stdout.isatty()

def _c(code: str, text: str) -> str:
    return f"{code}{text}{_RESET}" if _USE_COLOUR else text


# ── FpML XML renderer ──────────────────────────────────────────────────────────

def _strip_ns(tag: str) -> str:
    """Remove XML namespace URI from tag."""
    if "}" in tag:
        return tag.split("}", 1)[1]
    return tag


def _render_xml(elem: ET.Element, indent: int = 0, max_depth: int = 12) -> None:
    if indent > max_depth:
        print("  " * indent + _c(_YELLOW, "..."))
        return

    tag      = _strip_ns(elem.tag)
    text     = (elem.text or "").strip()
    attribs  = {k.split("}", 1)[-1]: v for k, v in elem.attrib.items()}
    children = list(elem)

    # Highlight interesting fields
    highlighted = tag.lower() in _HIGHLIGHT

    prefix = "  " * indent + "├─ "
    if attribs:
        attr_str = " ".join(f'{k}="{v}"' for k, v in attribs.items())
        label = f"<{tag} {attr_str}>"
    else:
        label = f"<{tag}>"

    if highlighted:
        label = _c(_GREEN, label)
    elif indent == 0:
        label = _c(_CYAN, label)

    if text and not children:
        val = _c(_YELLOW, text) if highlighted else text
        print(f"{prefix}{label} {val}")
    else:
        print(f"{prefix}{label}")

    for child in children:
        _render_xml(child, indent + 1, max_depth)


# ── CDM JSON renderer ──────────────────────────────────────────────────────────

def _render_json(obj: object, indent: int = 0, max_depth: int = 8, key: str = "") -> None:
    if indent > max_depth:
        print("  " * indent + _c(_YELLOW, "..."))
        return

    prefix = "  " * indent

    if isinstance(obj, dict):
        label = _c(_CYAN, f"{key}: {{") if key else _c(_CYAN, "{")
        print(f"{prefix}{label}")
        for k, v in obj.items():
            _render_json(v, indent + 1, max_depth, key=k)
        print(f"{prefix}}}")
    elif isinstance(obj, list):
        label = _c(_CYAN, f"{key}: [") if key else _c(_CYAN, "[")
        print(f"{prefix}{label}  # {len(obj)} item(s)")
        for i, item in enumerate(obj[:5]):   # cap at first 5 list items
            _render_json(item, indent + 1, max_depth, key=f"[{i}]")
        if len(obj) > 5:
            print("  " * (indent + 1) + _c(_YELLOW, f"... {len(obj) - 5} more"))
        print(f"{prefix}]")
    else:
        val = str(obj)
        if key:
            print(f"{prefix}{_c(_GREEN, key)}: {val}")
        else:
            print(f"{prefix}{val}")


# ── main ───────────────────────────────────────────────────────────────────────

def _list_stems() -> list[str]:
    return sorted(p.stem for p in FPML_DIR.glob("*.xml"))


def _check_dirs() -> None:
    if not FPML_DIR.exists() or not CDM_DIR.exists():
        sys.exit(
            f"Training data not found.\n"
            f"Expected:\n  {FPML_DIR}\n  {CDM_DIR}"
        )


def main() -> None:
    _check_dirs()

    parser = argparse.ArgumentParser(description="Visualise a FpML/CDM training pair")
    parser.add_argument("stem", nargs="?", default=DEFAULT,
                        help=f"File stem, e.g. ird-ex01-vanilla-swap (default: {DEFAULT})")
    parser.add_argument("--list", action="store_true", help="List all available stems and exit")
    args = parser.parse_args()

    if args.list:
        stems = _list_stems()
        print(f"{len(stems)} training pairs:\n")
        for s in stems:
            print(f"  {s}")
        return

    stem     = args.stem
    fpml_path = FPML_DIR / f"{stem}.xml"
    cdm_path  = CDM_DIR  / f"{stem}.json"

    for path in (fpml_path, cdm_path):
        if not path.exists():
            sys.exit(f"File not found: {path}\n  Run with --list to see available stems.")

    # ── FpML ──
    sep = "─" * 70
    print(f"\n{_c(_CYAN, sep)}")
    print(f"{_c(_CYAN, 'FpML XML')}  →  {fpml_path.name}")
    print(f"{_c(_CYAN, sep)}\n")

    tree = ET.parse(fpml_path)
    _render_xml(tree.getroot())

    # ── CDM ──
    print(f"\n{_c(_CYAN, sep)}")
    print(f"{_c(_CYAN, 'CDM JSON')}  →  {cdm_path.name}")
    print(f"{_c(_CYAN, sep)}\n")

    with open(cdm_path) as f:
        cdm = json.load(f)
    _render_json(cdm)

    print()


if __name__ == "__main__":
    main()
