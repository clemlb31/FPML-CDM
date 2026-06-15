"""Visualise the FpML→CDM mapping KG.

Two outputs:
  1. A focused Mermaid diagram (renders in Markdown / the IDE) for a chosen root —
     an ingestion-function call tree, or a CDM type's attribute→FpML graph.
  2. A Cytoscape-style {nodes, edges} JSON of the whole graph, loadable in real graph
     tools (Cytoscape / Gephi / yEd / D3).

    .venv/bin/python scripts/visualize_kg.py --root MapSwapPayout --depth 3 --out swap.mmd
    .venv/bin/python scripts/visualize_kg.py --type InterestRatePayout --out irp.mmd
    .venv/bin/python scripts/visualize_kg.py --graph-json cdm_fpml_graph.cyto.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _safe(s):
    return s.replace('"', "'")


def mermaid_func_tree(kg, root, depth):
    funcs = kg["ingestion_functions"]
    if root not in funcs:
        raise SystemExit(f"function {root!r} not in KG (try one of {list(funcs)[:5]}…)")
    lines = ["flowchart TD"]
    seen, edges = set(), set()

    def walk(name, d):
        if d > depth or name in seen:
            return
        seen.add(name)
        f = funcs.get(name)
        if not f:
            return
        out = f["output"]["type"] if f.get("output") else "?"
        lines.append(f'  {name}["{name}<br/>→ {out}"]')
        for c in f.get("calls", []):
            if (name, c) not in edges:
                edges.add((name, c))
                lines.append(f"  {name} --> {c}")
            walk(c, d + 1)

    walk(root, 0)
    return "\n".join(lines)


def mermaid_type(kg, tname):
    t = kg["types"].get(tname)
    if not t:
        raise SystemExit(f"type {tname!r} not in KG")
    lines = ["flowchart LR", f'  T["{tname}"]:::cdm']
    for a, rec in t["attributes"].items():
        if "fpml" not in rec:
            continue
        aid = f"a_{a}"
        lines.append(f'  {aid}["{a}<br/>:{rec["type"]}"]:::attr')
        lines.append(f"  T --> {aid}")
        for i, f in enumerate(rec["fpml"][:4]):
            fid = f"{aid}_f{i}"
            label = "/".join(f.get("value") or [])
            if f.get("path"):
                label += f"<br/>@{f['path']}"
            cond = f"<br/>WHEN {f['condition']}" if f.get("condition") else ""
            lines.append(f'  {fid}["{_safe(label)}{_safe(cond)}"]:::fpml')
            lines.append(f"  {aid} --> {fid}")
    lines += ["  classDef cdm fill:#bbf7d0,stroke:#15803d;",
              "  classDef attr fill:#fff,stroke:#374151;",
              "  classDef fpml fill:#fed7aa,stroke:#ea580c;"]
    return "\n".join(lines)


def cytoscape_graph(kg):
    nodes, edges = {}, []

    def node(nid, label, kind):
        nodes.setdefault(nid, {"data": {"id": nid, "label": label, "kind": kind}})

    # CDM types + attribute edges
    for t, info in kg["types"].items():
        node(f"type:{t}", t, "cdm_type")
        for a, rec in info["attributes"].items():
            if rec.get("type") in kg["types"]:
                edges.append({"data": {"source": f"type:{t}", "target": f"type:{rec['type']}",
                                       "label": a, "kind": "has_attr"}})
            for f in rec.get("fpml", []):
                for v in (f.get("value") or []):
                    node(f"fpml:{v}", v, "fpml")
                    edges.append({"data": {"source": f"fpml:{v}", "target": f"type:{t}",
                                           "label": a, "kind": "maps_to",
                                           "condition": f.get("condition")}})
    # ingestion funcs + call graph + output
    for fn, f in kg["ingestion_functions"].items():
        node(f"func:{fn}", fn, "ingest_func")
        if f.get("output"):
            node(f"type:{f['output']['type']}", f["output"]["type"], "cdm_type")
            edges.append({"data": {"source": f"func:{fn}", "target": f"type:{f['output']['type']}",
                                   "label": "builds", "kind": "builds"}})
        for c in f.get("calls", []):
            edges.append({"data": {"source": f"func:{fn}", "target": f"func:{c}",
                                   "label": "calls", "kind": "calls"}})
    return {"nodes": list(nodes.values()), "edges": edges}


def neighborhood(graph, root_id, depth):
    """BFS subgraph around root_id over the directed edges, up to `depth` hops."""
    adj = {}
    for e in graph["edges"]:
        adj.setdefault(e["data"]["source"], []).append(e["data"]["target"])
    keep, frontier = {root_id}, {root_id}
    for _ in range(depth):
        nxt = set()
        for n in frontier:
            for t in adj.get(n, []):
                if t not in keep:
                    nxt.add(t)
        keep |= nxt
        frontier = nxt
    nodes = [n for n in graph["nodes"] if n["data"]["id"] in keep]
    edges = [e for e in graph["edges"]
             if e["data"]["source"] in keep and e["data"]["target"] in keep]
    return {"nodes": nodes, "edges": edges}


_HTML = """<!doctype html><html><head><meta charset="utf-8">
<title>FpML→CDM KG — {title}</title>
<script src="https://unpkg.com/cytoscape@3.30.2/dist/cytoscape.min.js"></script>
<style>html,body{{margin:0;height:100%}}#cy{{width:100vw;height:100vh}}
#hdr{{position:fixed;z-index:9;background:#111;color:#eee;padding:6px 12px;font:13px system-ui}}</style>
</head><body>
<div id="hdr">{title} &nbsp;— {n} nodes, {m} edges &nbsp; (glisser = pan, molette = zoom, clic = focus)</div>
<div id="cy"></div>
<script>
const ELEMENTS = {elements};
const color = {{cdm_type:'#15803d', fpml:'#ea580c', ingest_func:'#1e40af'}};
cytoscape({{
  container: document.getElementById('cy'),
  elements: ELEMENTS,
  style: [
    {{selector:'node', style:{{
      'background-color': ele => color[ele.data('kind')]||'#666',
      'label':'data(label)','font-size':9,'color':'#111','text-wrap':'wrap','text-max-width':120,
      'width':16,'height':16}}}},
    {{selector:'node[kind="ingest_func"]', style:{{'shape':'round-rectangle','width':'label','padding':4}}}},
    {{selector:'edge', style:{{
      'width':1,'line-color':'#bbb','target-arrow-color':'#bbb','target-arrow-shape':'triangle',
      'curve-style':'bezier','label':'data(label)','font-size':7,'color':'#999'}}}},
    {{selector:'edge[kind="maps_to"]', style:{{'line-color':'#ea580c','target-arrow-color':'#ea580c'}}}},
    {{selector:'edge[kind="calls"]', style:{{'line-color':'#1e40af','target-arrow-color':'#1e40af'}}}},
  ],
  layout: {{name:'breadthfirst', directed:true, spacingFactor:1.3, padding:30}},
}});
</script></body></html>"""


def write_html(sub, path, title):
    html = _HTML.format(title=title, n=len(sub["nodes"]), m=len(sub["edges"]),
                        elements=json.dumps(sub["nodes"] + sub["edges"]))
    Path(path).write_text(html, encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--kg", default=str(ROOT / "cdm_fpml_kg.json"))
    ap.add_argument("--root", help="ingestion function to render as a call tree")
    ap.add_argument("--type", dest="type_", help="CDM type to render as attribute→FpML graph")
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--out", default=None, help="write Mermaid here (.mmd)")
    ap.add_argument("--graph-json", default=None, help="write full Cytoscape graph JSON here")
    ap.add_argument("--html", default=None, help="write an interactive HTML (focused on --root/--type)")
    args = ap.parse_args()

    kg = load(args.kg)

    if args.graph_json:
        g = cytoscape_graph(kg)
        Path(args.graph_json).write_text(json.dumps(g, indent=2), encoding="utf-8")
        print(f"graph: {len(g['nodes'])} nodes, {len(g['edges'])} edges → {args.graph_json}")

    if args.html:
        g = cytoscape_graph(kg)
        if args.root:
            root_id, title = f"func:{args.root}", f"ingestion tree: {args.root}"
        elif args.type_:
            root_id, title = f"type:{args.type_}", f"type mapping: {args.type_}"
        else:
            raise SystemExit("--html needs --root <func> or --type <CdmType>")
        sub = neighborhood(g, root_id, args.depth)
        write_html(sub, args.html, title)
        print(f"html: {len(sub['nodes'])} nodes, {len(sub['edges'])} edges → {args.html}")

    mmd = None
    if args.root:
        mmd = mermaid_func_tree(kg, args.root, args.depth)
    elif args.type_:
        mmd = mermaid_type(kg, args.type_)

    if mmd:
        if args.out:
            Path(args.out).write_text(mmd, encoding="utf-8")
            print(f"mermaid → {args.out}  ({mmd.count(chr(10))+1} lines)")
        print("\n```mermaid")
        print(mmd)
        print("```")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
