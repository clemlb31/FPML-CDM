"""Build an exhaustive FpML→CDM mapping KNOWLEDGE GRAPH with CONDITIONAL edges.

graphify core (value co-occurrence across the 565 paired examples) plus two upgrades
that make the conditional structure explicit:

  1. CDM array elements are discriminated BY CONTENT instead of dropping the index,
     so a fixed-leg InterestRatePayout and a floating-leg InterestRatePayout become
     DISTINCT target nodes (`…payout.InterestRatePayout{floating}` vs `{fixed}`).

  2. Each leaf occurrence carries a context — (product, leg, side) — read from its
     surroundings. An edge's value-coincidences are weighted UP when the FpML and CDM
     contexts agree and DOWN when they conflict, which dissolves the cross-leg /
     payer↔receiver spurious links. From the surviving context distribution we
     SYNTHESISE the `condition` under which the edge holds (the if/else gate), e.g.
     FpML `swapStream/…/dayCountFraction` → `{floating}` payout  IF leg==floating.

Output: a JSON KG (nodes implicit in edges; edges carry support / confidence /
transform / condition / evidence). Conditions are the if/else routing the user asked
for, learned from data — not invented.

    .venv/bin/python scripts/build_mapping_kg.py [--family rates-5-12] [--min-support 2]
"""
from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRAIN = ROOT / "data" / "train"

_CDM_DROP_KEYS = {"globalKey", "globalReference", "externalKey", "externalReference",
                  "assetType", "securityType", "priceSubType"}
_MAX_PATHS_PER_VALUE = 10
_BOOL = {"true", "false", "0", "1", "y", "n", "yes", "no"}

# FpML product elements (children of <trade>) — the top-level product discriminator.
_PRODUCT_TAGS = {
    "swap", "swaption", "fra", "capFloor", "creditDefaultSwap", "bondOption",
    "equityOption", "equityForward", "varianceSwap", "varianceOption", "volatilitySwap",
    "correlationSwap", "dividendSwap", "returnSwap", "equitySwapTransactionSupplement",
    "commoditySwap", "commodityForward", "commodityOption", "commoditySwaption",
    "fxSingleLeg", "fxSwap", "fxOption", "termDeposit", "repo", "bulletPayment",
    "genericProduct", "strategy", "nonSchemaProduct",
}
# Elements whose subtree defines a rate leg (fixed vs floating).
_LEG_TAGS = re.compile(r"(Stream|Leg)$")
# FpML side elements are camelCase prefixes: payerPartyReference, receiverPartyReference,
# buyerPartyReference, sellerPartyReference — match the prefix (no trailing word boundary).
_SIDE_RE = re.compile(r"^(payer|receiver|buyer|seller)", re.I)


# ── Context helpers ────────────────────────────────────────────────────────────

def _fpml_leg(el) -> str:
    tags = {t.tag.split('}')[-1] for t in el.iter()}
    if "floatingRateCalculation" in tags:
        return "floating"
    if "fixedRateSchedule" in tags or "fixedRate" in tags:
        return "fixed"
    return ""


def _side_of(name: str) -> str:
    m = _SIDE_RE.search(name)
    return m.group(1).lower() if m else ""


def _cdm_leg(node) -> str:
    """floating/fixed for an InterestRatePayout element; '' otherwise."""
    if isinstance(node, dict) and "InterestRatePayout" in node:
        rs = node["InterestRatePayout"].get("rateSpecification", {}) or {}
        if "FloatingRateSpecification" in rs:
            return "floating"
        if "FixedRateSpecification" in rs:
            return "fixed"
    return ""


# ── Leaf extraction with context ───────────────────────────────────────────────

def fpml_leaves(path_xml: Path):
    """Yield (field_path, value, ctx) where ctx = (product, leg, side). Path is the
    namespace-stripped tag chain with NO index and NO leg qualifier (generic node)."""
    out = []
    try:
        root = ET.parse(path_xml).getroot()
    except ET.ParseError:
        return out

    def sn(t):
        return t.split('}')[-1]

    def walk(el, path, ctx):
        tag = sn(el.tag)
        here = f"{path}/{tag}" if path else tag
        product, leg, side = ctx
        if tag in _PRODUCT_TAGS and not product:
            product = tag
        if _LEG_TAGS.search(tag):
            lg = _fpml_leg(el)
            if lg:
                leg = lg
        s = _side_of(tag)
        if s:
            side = s
        cctx = (product, leg, side)
        for k, v in el.attrib.items():
            v = (v or "").strip()
            if v:
                # an @href/@id under payerPartyReference inherits side from the tag
                out.append((f"{here}@{sn(k)}", v, cctx))
        text = (el.text or "").strip()
        if text:
            out.append((here, text, cctx))
        for child in el:
            walk(child, here, cctx)

    walk(root, "", ("", "", ""))
    return out


def cdm_leaves(path_json: Path):
    """Yield (field_path, value, ctx) with ctx = (product, leg, side). CDM payout
    array elements get a `{floating}`/`{fixed}` path qualifier so legs are distinct
    target nodes."""
    out = []
    try:
        obj = json.loads(path_json.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return out

    def walk(node, path, ctx):
        product, leg, side = ctx
        if isinstance(node, dict):
            lg = _cdm_leg(node)
            if lg and lg != leg:
                leg = lg
                path = f"{path}{{{lg}}}"          # qualify the payout node by leg
                ctx = (product, leg, side)
            for k, v in node.items():
                if k in _CDM_DROP_KEYS:
                    continue
                s = side
                if k in ("payer", "receiver", "buyer", "seller"):
                    s = k
                walk(v, f"{path}.{k}" if path else k, (product, leg, s))
        elif isinstance(node, list):
            for item in node:
                walk(item, path, ctx)            # element discriminated inside dict branch
        elif node is not None:
            out.append((path, str(node), ctx))

    walk(obj, "", ("", "", ""))
    return out


# ── Value normalisation ────────────────────────────────────────────────────────

def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _is_generic(key, raw):
    if key.startswith("#num:"):
        try:
            n = float(key[5:])
        except ValueError:
            return False
        return n == int(n) and abs(n) < 1000
    r = (raw or "").strip()
    return len(r) < 2 or r.lower() in _BOOL


def exact_key(v):
    n = _num(v)
    return f"#num:{n}" if n is not None else v.strip()


def loose_key(v):
    n = _num(v)
    return f"#num:{n}" if n is not None else re.sub(r"[^a-z0-9]", "", v.strip().lower())


# ── Build ──────────────────────────────────────────────────────────────────────

def _index(leaves, keyfn):
    d = defaultdict(list)                         # key -> [(path, ctx)]
    raw = {}
    for p, v, c in leaves:
        d[keyfn(v)].append((p, c))
        raw.setdefault(keyfn(v), v)
    return d, raw


def _ctx_factor(fctx, cctx):
    """Up-weight context-consistent links, down-weight conflicts. Returns (factor)."""
    f = 1.0
    fl, cl = fctx[1], cctx[1]                      # leg
    if fl and cl:
        f *= 2.0 if fl == cl else 0.15
    fs, cs = fctx[2], cctx[2]                      # side
    if fs and cs:
        f *= 2.0 if fs == cs else 0.15
    return f


def build(min_support, family=None):
    pairs = []
    fams = [TRAIN / family] if family else sorted(TRAIN.iterdir())
    for fam in fams:
        fdir, cdir = fam / "fpml", fam / "cdm"
        if not (fdir.is_dir() and cdir.is_dir()):
            continue
        for fx in sorted(fdir.glob("*.xml")):
            cj = cdir / f"{fx.stem}.json"
            if cj.exists():
                pairs.append((fx, cj))

    edge_w = defaultdict(float)
    edge_sup = defaultdict(set)
    edge_exact = defaultdict(int)
    edge_vals = defaultdict(lambda: defaultdict(int))
    # per-edge context evidence: counters over fpml leg/side/product where it fired
    edge_fleg = defaultdict(Counter)
    edge_fside = defaultdict(Counter)
    edge_fprod = defaultdict(Counter)
    edge_cleg = defaultdict(Counter)
    edge_cside = defaultdict(Counter)
    fp_seen, cp_seen = defaultdict(int), defaultdict(int)

    for ex_id, (fx, cj) in enumerate(pairs):
        fl, cl = fpml_leaves(fx), cdm_leaves(cj)
        for p, _, _ in fl:
            fp_seen[p] += 1
        for p, _, _ in cl:
            cp_seen[p] += 1
        for exact in (True, False):
            kf = exact_key if exact else loose_key
            fmap, fraw = _index(fl, kf)
            cmap, _ = _index(cl, kf)
            for key, fitems in fmap.items():
                if _is_generic(key, fraw.get(key, "")):
                    continue
                citems = cmap.get(key)
                if not citems:
                    continue
                if len(fitems) > _MAX_PATHS_PER_VALUE or len(citems) > _MAX_PATHS_PER_VALUE:
                    continue
                base = (1.0 if exact else 0.5) / (len(fitems) * len(citems))
                raw = fraw.get(key, "")
                for fp, fctx in fitems:
                    for cp, cctx in citems:
                        e = (fp, cp)
                        fac = _ctx_factor(fctx, cctx)
                        edge_w[e] += base * fac
                        edge_sup[e].add(ex_id)
                        if exact:
                            edge_exact[e] += 1
                        if raw and len(edge_vals[e]) < 10:
                            edge_vals[e][raw] += 1
                        # context counters weighted by agreement: a conflicting (cross-leg)
                        # coincidence barely counts, so the dominant context = the real routing.
                        if fctx[0]:
                            edge_fprod[e][fctx[0]] += fac
                        if fctx[1]:
                            edge_fleg[e][fctx[1]] += fac
                        if fctx[2]:
                            edge_fside[e][fctx[2]] += fac
                        if cctx[1]:
                            edge_cleg[e][cctx[1]] += fac
                        if cctx[2]:
                            edge_cside[e][cctx[2]] += fac

    # materialise
    out_w = defaultdict(float)
    for e, w in edge_w.items():
        if len(edge_sup[e]) >= min_support:
            out_w[e[0]] += w
    edges = []
    for e, w in edge_w.items():
        sup = len(edge_sup[e])
        if sup < min_support:
            continue
        fp, cp = e
        edges.append({
            "fpml": fp, "cdm": cp, "support": sup, "weight": round(w, 3),
            "conf_fwd": round(w / out_w[fp], 3) if out_w[fp] else 0.0,
            "transformed": edge_exact.get(e, 0) == 0,
            "scope_product": _dominant(edge_fprod[e], frac=0.6),
            "values": sorted(edge_vals.get(e, {}), key=lambda x: -edge_vals[e][x])[:3],
        })
        cond, needs = _synth_condition(e, edge_fleg, edge_fside, edge_cleg, edge_cside)
        edges[-1]["condition"] = cond
        edges[-1]["needs_resolution"] = needs or None
    edges.sort(key=lambda x: (-x["support"], -x["weight"]))
    return pairs, edges, fp_seen, cp_seen


def _dominant(counter, frac=0.8):
    if not counter:
        return None
    tot = sum(counter.values())
    k, v = counter.most_common(1)[0]
    return k if v / tot >= frac else None


def _synth_condition(e, fleg, fside, cleg, cside):
    """Derive the if/else gate of an edge.

    A `condition` is a predicate CHECKABLE on the FpML side: it is asserted only when
    the FpML occurrences actually carry the discriminator (leg/side) and it agrees
    with the CDM target. When the CDM target is leg/side-specific but the FpML field
    has no local discriminator (e.g. a tradeHeader partyReference that becomes the
    floating-leg payer), we cannot gate it locally → `needs_resolution` instead.
    """
    cond, needs = {}, []
    cl = _dominant(cleg[e]) or (e[1].split("{")[1].split("}")[0] if "{" in e[1] else None)
    fl = _dominant(fleg[e])
    if cl in ("fixed", "floating"):
        if fl == cl:
            cond["leg"] = cl
        elif fl is None:
            needs.append(f"target_leg={cl}")
    cs = _dominant(cside[e])
    fs = _dominant(fside[e])
    if cs in ("payer", "receiver"):
        if fs == cs:
            cond["side"] = cs
        elif fs is None:
            needs.append(f"target_side={cs}")
    return (cond or None), needs


def _load_rosetta_fpml(path):
    """FpML element names that the authoritative rosetta KG references (for provenance)."""
    p = Path(path)
    if not p.exists():
        return set()
    import re as _re
    g = json.loads(p.read_text(encoding="utf-8"))
    refs = set()
    for t in g.get("types", {}).values():
        for a in t.get("attributes", {}).values():
            for f in a.get("fpml", []):
                refs.update(f.get("value") or [])
    for f in g.get("ingestion_functions", {}).values():
        refs.update(f.get("fpml_refs", []))
        refs.update(_re.findall(r"->\s*([a-zA-Z]\w*)", f.get("logic", "")))
    return refs


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(ROOT / "fpml_cdm_mapping_kg.json"))
    ap.add_argument("--family", default=None, help="restrict to one data/train/<family> (for testing)")
    ap.add_argument("--min-support", type=int, default=2)
    ap.add_argument("--cross-ref", default=str(ROOT / "cdm_fpml_kg.json"),
                    help="authoritative rosetta KG, to tag each edge's provenance")
    args = ap.parse_args()

    rosetta_fpml = _load_rosetta_fpml(args.cross_ref)
    pairs, edges, fp_seen, cp_seen = build(args.min_support, args.family)
    for e in edges:                                          # provenance: confirmed by rosetta rules?
        leaf = e["fpml"].split("@")[0].rsplit("/", 1)[-1]
        e["rosetta"] = leaf in rosetta_fpml
    by_fp = defaultdict(list)
    for e in edges:
        by_fp[e["fpml"]].append(e)
    for fp in by_fp:
        by_fp[fp].sort(key=lambda x: -x["weight"])

    n_cond = sum(1 for e in edges if e["condition"])
    n_leg = sum(1 for e in edges if e["condition"] and "leg" in e["condition"])
    n_side = sum(1 for e in edges if e["condition"] and "side" in e["condition"])
    n_needs = sum(1 for e in edges if e["needs_resolution"])
    mapped = set(by_fp)
    unmapped_fpml = sorted(p for p, n in fp_seen.items() if p not in mapped and n >= 3)
    unmapped_cdm = sorted(p for p, n in cp_seen.items() if p not in {e["cdm"] for e in edges} and n >= 3)

    # ── Sufficiency for the train set ────────────────────────────────────────────
    # A recurring FpML leaf path is COVERED if it has an observed CDM target (value
    # coincidence in the pairs) OR its element has an authoritative rosetta rule
    # (value-coincidence is blind to mangled/transformed values, so the rule fills
    # those). The rest are genuinely unmapped — CDM doesn't model them.
    def _leaf(p):
        return p.split("@")[0].rsplit("/", 1)[-1]

    train_fpml = {p for p, n in fp_seen.items() if n >= args.min_support}
    mapped_paths = train_fpml & mapped                         # observed (value coincidence)
    no_obs = train_fpml - mapped
    rule_only = sorted(p for p in no_obs if _leaf(p) in rosetta_fpml)   # rosetta rule exists
    truly_unmapped = sorted(p for p in no_obs if _leaf(p) not in rosetta_fpml)
    covered = len(mapped_paths) + len(rule_only)
    sufficiency = round(100 * covered / len(train_fpml), 1) if train_fpml else 0.0
    rosetta_confirmed = sum(1 for p in mapped_paths if by_fp[p][0].get("rosetta"))
    train_only = len(mapped_paths) - rosetta_confirmed
    dropped_fpml = truly_unmapped                              # the genuine gaps

    kg = {
        "meta": {"pairs": len(pairs), "min_support": args.min_support,
                 "fpml_paths": len(fp_seen), "cdm_paths": len(cp_seen),
                 "edges": len(edges), "conditional_edges": n_cond,
                 "leg_conditioned": n_leg, "side_conditioned": n_side,
                 "needs_resolution": n_needs,
                 "train_fpml_fields": len(train_fpml),
                 "mapped_observed": len(mapped_paths), "rule_only": len(rule_only),
                 "covered": covered, "truly_unmapped": len(truly_unmapped),
                 "sufficiency_pct": sufficiency,
                 "provenance": {"rosetta_confirmed": rosetta_confirmed, "train_inferred": train_only}},
        "condition_vocab": {
            "leg=floating": "the FpML rate stream/leg contains <floatingRateCalculation> (floating leg) → CDM FloatingRateSpecification payout",
            "leg=fixed": "the FpML rate stream/leg contains <fixedRateSchedule>/<fixedRate> (fixed leg) → CDM FixedRateSpecification payout",
            "side=payer": "the FpML reference sits under a payer* element → CDM payerReceiver.payer",
            "side=receiver": "the FpML reference sits under a receiver* element → CDM payerReceiver.receiver",
            "product=[…]": "the edge only holds when the FpML trade product is one of the listed elements",
        },
        "edges": edges,
        "dropped_fpml": dropped_fpml,
        "unmapped_cdm": unmapped_cdm[:150],
    }
    Path(args.out).write_text(json.dumps(kg, indent=2, ensure_ascii=False), encoding="utf-8")

    m = kg["meta"]
    print(f"pairs={m['pairs']} fpml_paths={m['fpml_paths']} cdm_paths={m['cdm_paths']} "
          f"edges={m['edges']} conditional={n_cond} (leg={n_leg} side={n_side}) "
          f"needs_resolution={n_needs}")
    print(f"kg → {args.out}\n")
    print(f"SUFFICIENCY (train, support≥{args.min_support}) sur {m['train_fpml_fields']} champs FpML :")
    print(f"  COUVERTS = {m['covered']} = {m['sufficiency_pct']}%  "
          f"(observés par coïncidence={m['mapped_observed']} + couverts par règle rosetta seule={m['rule_only']})")
    print(f"  vraiment NON-mappés (ni coïncidence ni règle) = {m['truly_unmapped']} "
          f"= {round(100*m['truly_unmapped']/m['train_fpml_fields'],1)}%")
    print(f"  provenance des observés : rosetta-confirmé={rosetta_confirmed}  train-inféré={train_only}")
    print(f"  échantillon non-mappés (CDM ne les modélise pas): {dropped_fpml[:8]}\n")
    print("── ÉCHANTILLON D'ARÊTES CONDITIONNÉES (leg) ──")
    shown = 0
    for e in edges:
        if e["condition"] and "leg" in e["condition"] and "swapStream" in e["fpml"]:
            print(f"  IF {e['condition']}  s={e['support']}")
            print(f"    {e['fpml']}")
            print(f"    → {e['cdm']}  ex={e['values'][:2]}")
            shown += 1
            if shown >= 8:
                break
    print("\n── ÉCHANTILLON side (payer/receiver) — condition VÉRIFIABLE côté FpML ──")
    shown = 0
    for e in edges:
        if e["condition"] and "side" in e["condition"]:
            print(f"  IF {e['condition']}  s={e['support']}  {e['fpml']}  →  {e['cdm']}")
            shown += 1
            if shown >= 6:
                break
    print("\n── needs_resolution (cible leg/side-spécifique SANS discriminant FpML local) ──")
    shown = 0
    for e in edges:
        if e["needs_resolution"]:
            print(f"  NEEDS {e['needs_resolution']}  s={e['support']}  {e['fpml']}  →  {e['cdm']}")
            shown += 1
            if shown >= 6:
                break
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
