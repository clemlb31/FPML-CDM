#!/usr/bin/env bash
# scripts/fetch_references.sh
#
# One-time download of static reference files.
# Run once before first use — files are committed to the repo after that.
#
# Downloads:
#   reference/cdm/rosetta/*.rosetta   — CDM 6.x.x Rosetta DSL type files (IRS-relevant)
#   reference/fpml/*.xsd              — FpML 5.13 IRS XML schemas (for reference/validation)
#
# Then run:
#   python scripts/build_cdm_hierarchy.py
#
# About FpML XSD schemas:
#   They are NOT used by the code generator (training pairs + XPath guide cover the
#   input side well enough).  They are downloaded here for two potential uses:
#   1. Validating input FpML XML before transformation.
#   2. Regenerating fpml_server's XPath guide from the actual schema.
#   Skip the fpml section if you don't need them.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CDM_DIR="$REPO_ROOT/reference/cdm/rosetta"
FPML_DIR="$REPO_ROOT/reference/fpml"

CDM_BRANCH="6.x.x"
CDM_RAW="https://raw.githubusercontent.com/finos/common-domain-model/${CDM_BRANCH}/rosetta-source/src/main/rosetta"

# IRS-relevant CDM Rosetta type files
CDM_FILES=(
    "event-common-type.rosetta"
    "product-template-type.rosetta"
    "product-asset-type.rosetta"
    "product-asset-floatingrate-type.rosetta"
    "product-common-settlement-type.rosetta"
    "base-staticdata-party-type.rosetta"
    "base-datetime-type.rosetta"
    "observable-asset-type.rosetta"
)

echo "=== Fetching CDM Rosetta files (branch ${CDM_BRANCH}) ==="
for f in "${CDM_FILES[@]}"; do
    url="${CDM_RAW}/${f}"
    dest="${CDM_DIR}/${f}"
    if [[ -f "$dest" ]]; then
        echo "  skip (exists): $f"
        continue
    fi
    echo "  fetching: $f"
    curl -fsSL "$url" -o "$dest" || { echo "  WARN: failed to fetch $f"; }
done
echo "CDM done — $(ls "$CDM_DIR"/*.rosetta 2>/dev/null | wc -l) files in reference/cdm/rosetta/"

echo ""
echo "=== Fetching FpML 5.13 IRS schemas ==="
# FpML schemas are published by ISDA at fpml.org.
# The IRS-relevant subset is fpml-ird-5-13.xsd and its dependencies.
FPML_BASE="https://www.fpml.org/spec/fpml-5-13-2-rec-1/schema"
FPML_FILES=(
    "fpml-ird-5-13.xsd"
    "fpml-asset-5-13.xsd"
    "fpml-shared-5-13.xsd"
    "fpml-enum-5-13.xsd"
    "fpml-msg-5-13.xsd"
)

fpml_ok=0
for f in "${FPML_FILES[@]}"; do
    url="${FPML_BASE}/${f}"
    dest="${FPML_DIR}/${f}"
    if [[ -f "$dest" ]]; then
        echo "  skip (exists): $f"
        fpml_ok=$((fpml_ok+1))
        continue
    fi
    echo "  fetching: $f"
    if curl -fsSL --connect-timeout 8 "$url" -o "$dest" 2>/dev/null; then
        fpml_ok=$((fpml_ok+1))
    else
        echo "  SKIP: $f not reachable (fpml.org may require authentication)"
        rm -f "$dest"
    fi
done
echo "FpML done — ${fpml_ok}/${#FPML_FILES[@]} files downloaded to reference/fpml/"

echo ""
echo "=== Next step ==="
echo "  python scripts/build_cdm_hierarchy.py"
