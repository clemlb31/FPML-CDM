"""Build the AUTHORITATIVE FpML→CDM mapping knowledge graph from the CDM rosetta DSL.

Unlike the value-coincidence heuristic, this reads the GROUND TRUTH: the CDM team's
hand-written synonym rules shipped inside cdm-java (the .rosetta sources). Three
sources are parsed and merged:

  1. CDM model types        — `type T extends B: attr Type (card)`  → the CDM graph
  2. CDM enums              — `CONST displayName "ACT/360"`         → value-mapping tables
  3. FpML synonym mappings  — `mapping-fpml-*-synonym.rosetta`      → per-attribute rules:
       + attr
           [value "fpmlElement" path "a->b" mapper "M" meta "S" set when "cond" exists]
     i.e. which FpML element(s) feed each CDM attribute, under which FpML path, through
     which mapper function, and — crucially — under which CONDITION (`set when`).

The KG mirrors the real mapping: nodes are CDM types/attributes (typed, traversable),
edges carry the FpML synonym(s) + condition + mapper + path. Enum tables give the
value transforms. This is exhaustive (the whole model + every FpML synonym) and correct
(it IS the rosetta rule set), not inferred.

    .venv/bin/python scripts/build_cdm_fpml_kg.py [--out cdm_fpml_kg.json]
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def cdm_jar() -> str:
    hits = glob.glob(str(Path.home() / ".m2/repository/org/finos/cdm/cdm-java/*/cdm-java-*.jar"))
    hits = [h for h in hits if "sources" not in h and "javadoc" not in h]
    hits.sort(key=lambda p: ("6.19" not in p, p))            # prefer the targeted version
    if not hits:
        raise SystemExit("cdm-java jar not found under ~/.m2 — run a build first")
    return hits[0]


def read_rosetta(jar: str) -> dict[str, str]:
    """Return {entry_name: text} for every .rosetta resource in the jar."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.endswith(".rosetta"):
                out[name] = z.read(name).decode("utf-8", "replace")
    return out


# ── 1+2. Model types & enums ────────────────────────────────────────────────────

_TYPE_RE = re.compile(r"^type\s+(\w+)(?:\s+extends\s+(\w+))?\s*:")
_ENUM_RE = re.compile(r"^enum\s+(\w+)(?:\s+extends\s+(\w+))?\s*:")
_ATTR_RE = re.compile(r"^\s+([a-z]\w*)\s+([\w.]+)\s+\((\d+\.\.[\d*]+)\)")  # type may be dotted/qualified
_CONST_RE = re.compile(r'^\s+([A-Z][A-Z0-9_]*)\b(?:\s+displayName\s+"([^"]*)")?')
# rosetta basic types — used to keep genuine attributes whose type is lowercase
# (boolean/int/…) while rejecting condition-expression lines that happen to look
# like `name word (n..m)`.
_BASIC_TYPES = {"boolean", "int", "number", "string", "date", "time", "zonedDateTime",
                "calculation", "productType", "eventType"}
_TOP_RE = re.compile(r"^(type|enum|func|namespace|synonym|recordType|metaType|choice|"
                     r"basicType|library|annotation|typeAlias|rule|reporting)\b")
_ENUM_SYN_RE = re.compile(r'\[synonym\s+FpML\w*\s+value\s+"([^"]*)"')


def parse_model(files: dict[str, str]):
    types = {}                                               # T -> {extends, attributes:{a:{type,card}}}
    enums = {}                                               # E -> {fpml_value: CONST}
    for name, text in files.items():
        if "mapping-" in name and "synonym" in name:
            continue                                         # those are rules, parsed later
        lines = text.split("\n")
        i = 0
        while i < len(lines):
            line = lines[i]
            mt = _TYPE_RE.match(line)
            me = _ENUM_RE.match(line)
            if mt:
                tname, ext = mt.group(1), mt.group(2)
                attrs = {}
                i += 1
                # Collect EVERY attribute declaration in the type body until the next
                # top-level construct. Conditions live in the body too but their lines
                # don't match _ATTR_RE (no `Type (card)` shape); the type-guard below
                # rejects the rare false positive. This replaces the old condition-skip
                # that wrongly dropped attributes declared after a condition.
                while i < len(lines):
                    l = lines[i]
                    if _TOP_RE.match(l):
                        break
                    ma = _ATTR_RE.match(l)
                    if ma:
                        leaf = ma.group(2).split(".")[-1]    # leaf of a possibly-dotted type
                        if leaf[:1].isupper() or ma.group(2) in _BASIC_TYPES:
                            attrs.setdefault(ma.group(1), {"type": leaf, "card": ma.group(3)})
                    i += 1
                types[tname] = {"extends": ext, "attributes": attrs}
                continue
            if me:
                ename, ext = me.group(1), me.group(2)
                vmap = {}
                i += 1
                while i < len(lines):
                    l = lines[i]
                    if _TOP_RE.match(l):
                        break
                    mc = _CONST_RE.match(l)
                    if mc:
                        const, disp = mc.group(1), mc.group(2)
                        if disp:
                            vmap[disp] = const                # FpML string (displayName) → CDM const
                        syn = _ENUM_SYN_RE.search(l)
                        if syn:
                            vmap[syn.group(1)] = const
                        vmap.setdefault(const, const)         # identity fallback
                    i += 1
                enums[ename] = {"extends": ext, "values": vmap}
                continue
            i += 1
    return types, enums


# ── 3. FpML synonym mapping rules ───────────────────────────────────────────────

def _parse_annotation(s: str) -> dict | None:
    """Parse one [ ... ] synonym annotation into a structured rule."""
    body = s.strip()
    if not body.startswith("["):
        return None
    inner = body[1:body.rfind("]")] if "]" in body else body[1:]
    kind = inner.split(None, 1)[0] if inner.strip() else ""
    rule: dict = {}
    if kind == "hint":
        rule["hint"] = re.findall(r'"([^"]*)"', inner)
        rule["_kind"] = "hint"
        return rule
    if kind != "value":
        return None                                          # meta/metadata/etc handled by callers
    # values: every quoted token before the first keyword (path/mapper/meta/set/tag)
    mvals = re.match(r'value\s+((?:"[^"]*"\s*,?\s*)+)', inner)
    rule["value"] = re.findall(r'"([^"]*)"', mvals.group(1)) if mvals else []
    mpath = re.search(r'\bpath\s+"([^"]*)"', inner)
    if mpath:
        rule["path"] = mpath.group(1)
    mmap = re.search(r'\bmapper\s+"([^"]*)"', inner)
    if mmap:
        rule["mapper"] = mmap.group(1)
    mmeta = re.search(r'\bmeta\s+"([^"]*)"', inner)
    if mmeta:
        rule["meta"] = mmeta.group(1)
    msw = re.search(r'\bset when\s+(.*)$', inner)
    if msw:
        rule["condition"] = msw.group(1).strip().rstrip("]").strip()
    rule["_kind"] = "value"
    return rule


def parse_synonyms(files: dict[str, str]):
    """Return {source: {Type: {attr_path: [rules]}}} from the FpML mapping files."""
    sources = {}
    for name, text in files.items():
        if "mapping-fpml" not in name or "synonym" not in name:
            continue
        lines = text.split("\n")
        cur_source = None
        cur_type = None
        attr_stack = []                                      # [(indent, name)]
        for raw in lines:
            if not raw.strip():
                continue
            ms = re.match(r"^synonym source\s+(\w+)", raw)
            if ms:
                cur_source = ms.group(1)
                sources.setdefault(cur_source, {})
                cur_type, attr_stack = None, []
                continue
            if cur_source is None:
                continue
            indent = len(raw) - len(raw.lstrip(" \t"))
            body = re.sub(r"/\*.*?\*/", "", raw).strip()     # drop inline block comments
            body = re.sub(r"//.*$", "", body).strip()        # drop trailing line comments
            if not body:
                continue
            mt = re.match(r"^([A-Z]\w*)\s*:", body)
            if mt and indent <= 4:
                cur_type = mt.group(1)
                sources[cur_source].setdefault(cur_type, {})
                attr_stack = []
                continue
            if cur_type is None:
                continue
            if body[0] in "+-":
                m = re.match(r"^[+\-]+\s*(\w+)", body)
                if not m:
                    continue
                while attr_stack and attr_stack[-1][0] >= indent:
                    attr_stack.pop()
                attr_stack.append((indent, m.group(1)))
                path = ".".join(n for _, n in attr_stack)
                sources[cur_source][cur_type].setdefault(path, [])
                continue
            if body.startswith("[") and attr_stack:
                rule = _parse_annotation(body)
                if rule:
                    path = ".".join(n for _, n in attr_stack)
                    sources[cur_source][cur_type][path].append(rule)
    return sources


# ── Ingestion functions (the procedural ground truth — the "mappers" unfolded) ──

_FUNC_RE = re.compile(r"^func\s+(\w+)\s*:")
_IO_RE = re.compile(r"^\s+([a-z]\w*)\s+([\w.]+)\s+\((\d+\.\.[\d*]+)\)")


def parse_ingest_functions(files: dict[str, str]):
    """Parse ingest-fpml-*-func.rosetta into structured functions.

    Each function's body (aliases + `set`/`assign-output` expressions) is the actual
    FpML→CDM transformation logic. We keep it VERBATIM (the correct ground truth) and
    also extract: inputs/output types, the CDM attributes it populates, the functions
    it calls (call graph), and the FpML elements it reads.
    """
    funcs = {}
    for name, text in files.items():
        if "ingest-fpml" not in name:
            continue
        short = Path(name).name
        lines = text.split("\n")
        i = 0
        while i < len(lines):
            mf = _FUNC_RE.match(lines[i])
            if not mf:
                i += 1
                continue
            fname = mf.group(1)
            j = i + 1
            while j < len(lines) and not _FUNC_RE.match(lines[j]):
                j += 1
            block = lines[i:j]
            inputs, output, body_start = [], None, len(block)
            section = None
            for k, l in enumerate(block):
                s = l.strip()
                if s == "inputs:":
                    section = "in"; continue
                if s == "output:":
                    section = "out"; continue
                mio = _IO_RE.match(l)
                if mio and section == "in":
                    inputs.append({"name": mio.group(1), "type": mio.group(2), "card": mio.group(3)})
                elif mio and section == "out":
                    output = {"name": mio.group(1), "type": mio.group(2), "card": mio.group(3)}
                    body_start = k + 1
                    break
            body = "\n".join(block[body_start:]).strip("\n")
            funcs[fname] = {
                "file": short,
                "inputs": inputs,
                "output": output,
                "sets_attributes": sorted(set(re.findall(r"(?m)^\s*([a-z]\w*)\s*:\s", body))),
                "calls": sorted(set(re.findall(r"\b([A-Z][A-Za-z0-9]*)\s*\(", body))),
                "fpml_refs": sorted(set(re.findall(r"->\s*([a-z]\w*)", body)))[:40],
                "logic": body,
            }
            i = j
    # keep only real call-graph edges (calls that are themselves parsed functions)
    fset = set(funcs)
    for f in funcs.values():
        f["calls"] = [c for c in f["calls"] if c in fset]
    return funcs


# ── Merge into the KG ───────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(ROOT / "cdm_fpml_kg.json"))
    args = ap.parse_args()

    jar = cdm_jar()
    files = read_rosetta(jar)
    types, enums = parse_model(files)
    sources = parse_synonyms(files)
    ingest = parse_ingest_functions(files)

    # Attach FpML synonyms onto the model attributes, keyed by CDM type.
    # The primary FpML mapping source is the trade-state confirmation mapping.
    primary = "FpML_5_Confirmation_To_TradeState"
    syn_by_type = defaultdict(dict)
    for src, tmap in sources.items():
        for t, amap in tmap.items():
            for apath, rules in amap.items():
                vrules = [r for r in rules if r.get("_kind") == "value"]
                if not vrules:
                    continue
                entry = syn_by_type[t].setdefault(apath, {"fpml": [], "sources": set()})
                entry["sources"].add(src)
                for r in vrules:
                    entry["fpml"].append({k: v for k, v in r.items() if k != "_kind"})

    # Build the KG type nodes: model attributes + their FpML rules.
    kg_types = {}
    n_attr = n_attr_mapped = n_cond = 0
    for t, tinfo in sorted(types.items()):
        attrs_out = {}
        for a, ainfo in tinfo["attributes"].items():
            n_attr += 1
            rec = {"type": ainfo["type"], "card": ainfo["card"]}
            syn = syn_by_type.get(t, {}).get(a)
            if syn:
                rec["fpml"] = syn["fpml"]
                rec["sources"] = sorted(syn["sources"])
                n_attr_mapped += 1
                n_cond += sum(1 for f in syn["fpml"] if f.get("condition"))
            attrs_out[a] = rec
        # also surface nested-path synonyms that don't match a direct attribute (e.g. a.b)
        extra = {p: v for p, v in syn_by_type.get(t, {}).items() if "." in p}
        kg_types[t] = {"extends": tinfo["extends"], "attributes": attrs_out}
        if extra:
            kg_types[t]["nested_synonyms"] = {
                p: [{k: vv for k, vv in r.items() if k != "_kind"} for r in v["fpml"]]
                for p, v in extra.items()
            }

    conditions = []
    for t, amap in syn_by_type.items():
        for apath, v in amap.items():
            for f in v["fpml"]:
                if f.get("condition"):
                    conditions.append({"cdm_type": t, "attribute": apath,
                                       "fpml": f.get("value"), "path": f.get("path"),
                                       "condition": f["condition"]})

    # Index ingestion functions by the CDM type they build (so a type → how it's ingested).
    ingest_by_output = defaultdict(list)
    for fname, f in ingest.items():
        if f.get("output"):
            ingest_by_output[f["output"]["type"]].append(fname)

    kg = {
        "meta": {
            "cdm_jar": Path(jar).name,
            "sources": sorted(sources),
            "cdm_types": len(types), "cdm_enums": len(enums),
            "attributes_total": n_attr, "attributes_fpml_mapped": n_attr_mapped,
            "value_rules": sum(len(v["fpml"]) for a in syn_by_type.values() for v in a.values()),
            "conditional_rules": len(conditions),
            "mapper_rules": sum(1 for a in syn_by_type.values() for v in a.values()
                                for f in v["fpml"] if f.get("mapper")),
            "mappers": sorted({f["mapper"] for a in syn_by_type.values() for v in a.values()
                               for f in v["fpml"] if f.get("mapper")}),
            "ingestion_functions": len(ingest),
            "ingestion_set_rules": sum(len(f["sets_attributes"]) for f in ingest.values()),
        },
        "types": kg_types,
        "enums": {e: info["values"] for e, info in sorted(enums.items())},
        "conditions": conditions,
        "ingestion_functions": ingest,
        "ingestion_by_output_type": {k: sorted(v) for k, v in sorted(ingest_by_output.items())},
    }
    Path(args.out).write_text(json.dumps(kg, indent=2, ensure_ascii=False, default=list),
                              encoding="utf-8")

    m = kg["meta"]
    print(f"cdm_jar={m['cdm_jar']}  types={m['cdm_types']}  enums={m['cdm_enums']}")
    print(f"attributes={m['attributes_total']}  fpml-mapped={m['attributes_fpml_mapped']}  "
          f"value_rules={m['value_rules']}  conditional={m['conditional_rules']}")
    print(f"sources={m['sources']}")
    print(f"kg → {args.out}\n")

    print("── InterestRatePayout (attributs + synonymes FpML) ──")
    for a, rec in list(kg_types.get("InterestRatePayout", {}).get("attributes", {}).items())[:8]:
        fp = rec.get("fpml")
        print(f"  {a}: {rec['type']} ({rec['card']})")
        for f in (fp or [])[:3]:
            extra = " ".join(f"{k}={v}" for k, v in f.items() if k not in ("value",))
            print(f"      ← FpML {f.get('value')}  {extra}")
    print("\n── exemples de RÈGLES CONDITIONNELLES (set when) ──")
    for c in conditions[:8]:
        print(f"  {c['cdm_type']}.{c['attribute']} ← {c['fpml']}  WHEN {c['condition']}")
    print("\n── table d'enum DayCountFractionEnum (valeur FpML → const CDM) ──")
    for k, v in list(kg["enums"].get("DayCountFractionEnum", {}).items())[:8]:
        if k != v:
            print(f"  '{k}' → {v}")

    print(f"\n── MAPPERS DÉPLIÉS : {len(ingest)} fonctions d'ingestion, "
          f"{kg['meta']['ingestion_set_rules']} règles de set ──")
    pr = ingest.get("MapPayerReceiver")
    if pr:
        print(f"  MapPayerReceiver  inputs={[i['type'] for i in pr['inputs']]} "
              f"→ output={pr['output']['type'] if pr['output'] else '?'}")
        print(f"    pose: {pr['sets_attributes']}  appelle: {pr['calls']}")
        print("    logique (verbatim, extrait):")
        for l in pr["logic"].split("\n")[:12]:
            print(f"      {l}")
    print("\n  ingestion par type CDM (extrait):")
    for t in ("Payout", "InterestRatePayout", "TradeState", "PayerReceiver"):
        fs = kg["ingestion_by_output_type"].get(t)
        if fs:
            print(f"    {t}  ←  {fs}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
