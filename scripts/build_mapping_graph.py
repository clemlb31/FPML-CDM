"""Build an FpML→CDM field mapping graph from the paired training data.

graphify-style approach: in each (FpML, CDM) example the two documents describe
the SAME trade, so a value that appears at an FpML leaf path AND at a CDM leaf
path is evidence those two fields correspond. We link them, weight the link by
value specificity (1/(n_fpml_paths * n_cdm_paths) for that value in that example),
and aggregate over all 565 examples. Robust correspondences emerge as
high-support, high-confidence edges; the rest are the ambiguities we report.

Outputs a JSON graph (nodes = field paths, edges = mappings with support /
confidence / evidence) and prints the ambiguities found.

    .venv/bin/python scripts/build_mapping_graph.py [--out fpml_cdm_mapping_graph.json]
                                                    [--min-support 2]
"""
from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRAIN = ROOT / "data" / "train"

# CDM keys that are synthetic / bookkeeping, not real mapped fields (cf. SemanticDiff).
_CDM_DROP_KEYS = {"globalKey", "globalReference", "externalKey", "externalReference",
                  "assetType", "securityType", "priceSubType"}
# Within one example, a value appearing at more than this many distinct paths on a
# side is too generic (e.g. "DOCUMENT", "EUR", "NONE") — skip it as a linker.
_MAX_PATHS_PER_VALUE = 8


# ── Leaf extraction ────────────────────────────────────────────────────────────

def _strip_ns(tag: str) -> str:
    return tag.split("}", 1)[-1]


def fpml_leaves(path_xml: Path) -> list[tuple[str, str]]:
    """Return [(field_path, value)] for an FpML doc: element texts + attributes.

    Paths are namespace-stripped tag chains with NO positional index, so repeated
    siblings collapse to one field path (we want fields, not instances).
    """
    out: list[tuple[str, str]] = []
    try:
        root = ET.parse(path_xml).getroot()
    except ET.ParseError:
        return out

    def walk(el, path):
        tag = _strip_ns(el.tag)
        here = f"{path}/{tag}" if path else tag
        for k, v in el.attrib.items():
            v = (v or "").strip()
            if v:
                out.append((f"{here}@{_strip_ns(k)}", v))
        text = (el.text or "").strip()
        if text:
            out.append((here, text))
        for child in el:
            walk(child, here)

    walk(root, "")
    return out


def cdm_leaves(path_json: Path) -> list[tuple[str, str]]:
    """Return [(field_path, value)] for a CDM doc: scalar leaves, array indices
    dropped, bookkeeping keys (globalKey…) removed."""
    out: list[tuple[str, str]] = []
    try:
        obj = json.loads(path_json.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return out

    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                if k in _CDM_DROP_KEYS:
                    continue
                walk(v, f"{path}.{k}" if path else k)
        elif isinstance(node, list):
            for item in node:
                walk(item, path)                       # drop the index
        elif node is not None:
            out.append((path, str(node)))
    walk(obj, "")
    return out


# ── Value normalisation (for matching) ─────────────────────────────────────────

def _num(v: str):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


_BOOL = {"true", "false", "0", "1", "y", "n", "yes", "no"}


def _is_generic(key: str, raw: str) -> bool:
    """A value too unspecific to be a reliable field-linker (creates spurious edges).

    Skipped: booleans, integers with |n|<1000 (sequence numbers, multipliers, small
    counts), and single-character codes. KEPT: dates, big/decimal numbers (notionals,
    rates), party refs, scheme URIs, multi-char enums/currencies — these carry signal.
    """
    if key.startswith("#num:"):
        try:
            n = float(key[5:])
        except ValueError:
            return False
        return n == int(n) and abs(n) < 1000           # small integers are generic
    r = (raw or "").strip()
    return len(r) < 2 or r.lower() in _BOOL


def exact_key(v: str) -> str:
    n = _num(v)
    return f"#num:{n}" if n is not None else v.strip()


def loose_key(v: str) -> str:
    n = _num(v)
    if n is not None:
        return f"#num:{n}"
    return re.sub(r"[^a-z0-9]", "", v.strip().lower())


# ── Graph accumulation ─────────────────────────────────────────────────────────

def _index(leaves: list[tuple[str, str]], keyfn):
    d: dict[str, set[str]] = defaultdict(set)
    for p, v in leaves:
        d[keyfn(v)].add(p)
    return d


def build_graph(min_support: int):
    pairs = []
    for fam in sorted(TRAIN.iterdir()):
        fdir, cdir = fam / "fpml", fam / "cdm"
        if not (fdir.is_dir() and cdir.is_dir()):
            continue
        for fx in sorted(fdir.glob("*.xml")):
            cj = cdir / f"{fx.stem}.json"
            if cj.exists():
                pairs.append((fx, cj))

    # edge[(fp, cp)] = {"w": weight, "support": n_examples, "exact": n, "values": Counter-ish}
    edge_w: dict[tuple, float] = defaultdict(float)
    edge_sup: dict[tuple, set] = defaultdict(set)            # set of example ids
    edge_exact: dict[tuple, int] = defaultdict(int)
    edge_vals: dict[tuple, dict] = defaultdict(lambda: defaultdict(int))
    fpml_paths_seen: dict[str, int] = defaultdict(int)
    cdm_paths_seen: dict[str, int] = defaultdict(int)

    for ex_id, (fx, cj) in enumerate(pairs):
        fl = fpml_leaves(fx)
        cl = cdm_leaves(cj)
        for p, _ in fl:
            fpml_paths_seen[p] += 1
        for p, _ in cl:
            cdm_paths_seen[p] += 1

        f_exact, c_exact = _index(fl, exact_key), _index(cl, exact_key)
        f_loose, c_loose = _index(fl, loose_key), _index(cl, loose_key)
        # representative raw FpML value per key (for human-readable edge evidence)
        f_rawkey_exact = {exact_key(v): v for _, v in fl}
        f_rawkey_loose = {loose_key(v): v for _, v in fl}

        def add_edges(fmap, cmap, exact: bool, rawkey):
            for key, fps in fmap.items():
                if _is_generic(key, rawkey.get(key, "")):
                    continue                                 # bool / small-int / single-char → noise
                cps = cmap.get(key)
                if not cps:
                    continue
                if len(fps) > _MAX_PATHS_PER_VALUE or len(cps) > _MAX_PATHS_PER_VALUE:
                    continue                                 # too generic a value
                w = (1.0 if exact else 0.5) / (len(fps) * len(cps))
                raw = rawkey.get(key, "")
                for fp in fps:
                    for cp in cps:
                        e = (fp, cp)
                        edge_w[e] += w
                        edge_sup[e].add(ex_id)
                        if exact:
                            edge_exact[e] += 1
                        if raw and len(edge_vals[e]) < 8:
                            edge_vals[e][raw] += 1

        add_edges(f_exact, c_exact, exact=True, rawkey=f_rawkey_exact)
        # loose matches add the transformed (post-normalisation) evidence; exact pairs
        # already counted above just get extra weight, which is fine.
        add_edges(f_loose, c_loose, exact=False, rawkey=f_rawkey_loose)

    # Materialise edges with support filter
    edges = []
    out_w: dict[str, float] = defaultdict(float)             # per fpml_path total weight
    in_w: dict[str, float] = defaultdict(float)              # per cdm_path total weight
    for e, w in edge_w.items():
        if len(edge_sup[e]) >= min_support:
            out_w[e[0]] += w
            in_w[e[1]] += w
    for e, w in edge_w.items():
        sup = len(edge_sup[e])
        if sup < min_support:
            continue
        fp, cp = e
        edges.append({
            "fpml": fp, "cdm": cp,
            "support": sup, "weight": round(w, 3),
            "conf_fwd": round(w / out_w[fp], 3) if out_w[fp] else 0.0,
            "conf_bwd": round(w / in_w[cp], 3) if in_w[cp] else 0.0,
            "exact": edge_exact.get(e, 0),
            "transformed": edge_exact.get(e, 0) == 0,
            "values": sorted(edge_vals.get(e, {}), key=lambda x: -edge_vals[e][x])[:3],
        })
    edges.sort(key=lambda x: (-x["support"], -x["weight"]))
    return pairs, edges, fpml_paths_seen, cdm_paths_seen


def best_targets(edges):
    by_fp = defaultdict(list)
    for e in edges:
        by_fp[e["fpml"]].append(e)
    for fp in by_fp:
        by_fp[fp].sort(key=lambda x: -x["weight"])
    return by_fp


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(ROOT / "fpml_cdm_mapping_graph.json"))
    ap.add_argument("--min-support", type=int, default=2)
    ap.add_argument("--amb-conf", type=float, default=0.65,
                    help="forward-confidence below which a mapping is flagged ambiguous")
    args = ap.parse_args()

    pairs, edges, fp_seen, cp_seen = build_graph(args.min_support)
    by_fp = best_targets(edges)

    # ── Per-FpML status + ambiguity classification ─────────────────────────────
    def status_of(es):
        top = es[0]
        second = es[1] if len(es) > 1 else None
        comparable = second and second["weight"] >= 0.4 * top["weight"]
        if not comparable and (top["conf_fwd"] >= 0.7 or top["support"] >= 10):
            return "confident"
        if comparable:
            return "ambiguous"
        return "weak"

    mappings, context_dependent, structural_1toN = [], [], []
    for fp, es in sorted(by_fp.items(), key=lambda kv: -kv[1][0]["support"]):
        st = status_of(es)
        top = es[0]
        mappings.append({
            "fpml": fp, "cdm": top["cdm"], "support": top["support"],
            "confidence": top["conf_fwd"], "status": st,
            "transformed": top["transformed"],
            "alt_targets": [(e["cdm"], e["conf_fwd"], e["support"]) for e in es[1:3]],
            "values": top["values"],
        })
        if st == "ambiguous":
            second = es[1]
            entry = {"fpml": fp, "targets": [(e["cdm"], e["support"], e["conf_fwd"]) for e in es[:4]]}
            (context_dependent if second["support"] >= 8 else structural_1toN).append(entry)

    # N→1 : one CDM field fed by several distinct FpML paths
    by_cp = defaultdict(list)
    for e in edges:
        by_cp[e["cdm"]].append(e)
    many_to_one = []
    for cp, es in by_cp.items():
        es.sort(key=lambda x: -x["weight"])
        strong = [e for e in es if e["conf_bwd"] >= 0.2 and e["support"] >= 5]
        if len(strong) > 1:
            many_to_one.append({"cdm": cp, "sources": [(e["fpml"], e["support"], e["conf_bwd"]) for e in strong[:4]]})

    # Transformed (value matched only after normalisation: case, party-ref, format)
    transformed = sorted(
        ({"fpml": e["fpml"], "cdm": e["cdm"], "support": e["support"], "values": e["values"]}
         for e in edges if e["transformed"] and e["support"] >= max(args.min_support, 3)),
        key=lambda x: -x["support"])

    mapped_fpml = set(by_fp)
    unmapped_fpml = sorted(p for p, n in fp_seen.items() if p not in mapped_fpml and n >= 3)
    mapped_cdm = {e["cdm"] for e in edges}
    unmapped_cdm = sorted(p for p, n in cp_seen.items() if p not in mapped_cdm and n >= 3)
    n_conf = sum(1 for mm in mappings if mm["status"] == "confident")

    graph = {
        "meta": {
            "pairs": len(pairs), "min_support": args.min_support,
            "fpml_paths": len(fp_seen), "cdm_paths": len(cp_seen), "edges": len(edges),
            "mapped_fpml": len(by_fp), "confident": n_conf,
        },
        "mappings": mappings,
        "edges": edges,
        "ambiguities": {
            "context_dependent": sorted(context_dependent, key=lambda x: -x["targets"][1][1])[:80],
            "structural_one_to_many": structural_1toN[:80],
            "many_to_one": sorted(many_to_one, key=lambda x: -len(x["sources"]))[:80],
            "transformed": transformed[:100],
            "unmapped_fpml": unmapped_fpml[:120],
            "unmapped_cdm": unmapped_cdm[:120],
        },
    }
    Path(args.out).write_text(json.dumps(graph, indent=2, ensure_ascii=False), encoding="utf-8")

    m = graph["meta"]
    a = graph["ambiguities"]
    print(f"pairs={m['pairs']}  fpml_paths={m['fpml_paths']}  cdm_paths={m['cdm_paths']}  "
          f"edges={m['edges']}  mapped_fpml={m['mapped_fpml']}  confident={n_conf}")
    print(f"graph → {args.out}\n")
    print(f"AMBIGUÏTÉS : context-dependent={len(context_dependent)}  1→N structurel={len(structural_1toN)}  "
          f"N→1={len(many_to_one)}  transformées={len(transformed)}  "
          f"fpml_non_mappés={len(unmapped_fpml)}  cdm_non_mappés={len(unmapped_cdm)}\n")

    print("── CONTEXT-DEPENDENT (même champ FpML → plusieurs cibles CDM réelles, support fort) ──")
    for x in a["context_dependent"][:10]:
        print(f"  {x['fpml']}")
        for cp, s, c in x["targets"]:
            print(f"        s={s:<4} conf={c:<5} {cp}")
    print("\n── TRANSFORMÉES (match seulement après normalisation) ──")
    for x in a["transformed"][:10]:
        print(f"  s={x['support']:<4} {x['fpml']}  →  {x['cdm']}   ex={x['values']}")
    print("\n── N→1 (plusieurs champs FpML → même champ CDM) ──")
    for x in a["many_to_one"][:8]:
        print(f"  {x['cdm']}")
        for fp, s, c in x["sources"]:
            print(f"        s={s:<4} conf={c:<5} {fp}")
    print("\n── FpML fréquents SANS cible CDM (droppés / dérivés / non-alignables par valeur) ──")
    for p in a["unmapped_fpml"][:14]:
        print(f"  {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
