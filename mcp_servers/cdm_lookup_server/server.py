"""cdm_lookup MCP server — queryable CDM 6.19 API introspection.

The #1 cause of compile errors when generating an FpML→CDM transformer is the model
INVENTING CDM builder/enum names. This server lets it QUERY the real API instead:
give a type name, get back the exact builder `set*/add*` signatures (or, for an enum,
its real constants) straight from the cdm-java jar via `javap`.

It is facts-on-demand — like the compiler — so the model derives correct API usage
itself rather than copying a hand-written answer. No CDM source is ever handed over.

How it works:
  1. locate the cdm-java jar under ~/.m2 (prefers 6.19.0).
  2. resolve a simple name (e.g. 'TradeLot') → fully-qualified name by scanning the
     jar's class listing (`unzip -l`).
  3. `javap` the `<Type>$<Type>Builder` inner interface and keep its set*/add* methods,
     or — if there is no builder — `javap` the type and list its enum constants.

Stateless, read-only, no network. Depends on a JDK (`javap`) and `unzip` on PATH.
"""
from __future__ import annotations

import argparse
import glob
import re
import subprocess
from pathlib import Path

from mcp.server.fastmcp import FastMCP


mcp = FastMCP("cdm_lookup")

# Resolved once and cached for the server's lifetime.
_JAR_CACHE: str | None = None


def _cdm_jar() -> str | None:
    """Locate the cdm-java jar under ~/.m2, preferring the 6.19.0 we target."""
    global _JAR_CACHE
    if _JAR_CACHE is None:
        hits = glob.glob(str(Path.home() / ".m2/repository/org/finos/cdm/cdm-java/*/cdm-java-*.jar"))
        hits.sort(key=lambda p: ("6.19.0" not in p, p))
        _JAR_CACHE = hits[0] if hits else ""
    return _JAR_CACHE or None


def _simplify_types(sig: str) -> str:
    """Strip package prefixes from a param list so signatures stay readable."""
    return re.sub(r"[\w.]+\.(\w+)", r"\1", sig)


def _lookup(name: str) -> str:
    jar = _cdm_jar()
    if not jar:
        return "<error>cdm jar not found under ~/.m2 — is cdm-java installed?</error>"
    q = (name or "").strip().replace(".class", "")
    if not q:
        return "<error>cdm_lookup: give a class or enum name, e.g. 'TradeLot' or 'DayCountFractionEnum'</error>"
    simple = q.split(".")[-1].split("$")[0]

    try:
        listing = subprocess.run(["unzip", "-l", jar], capture_output=True, text=True, timeout=20).stdout
    except Exception as exc:                                    # noqa: BLE001
        return f"<error>cdm_lookup: {type(exc).__name__}: {exc}</error>"

    exact, fuzzy = [], []
    for line in listing.splitlines():
        m = re.search(r"(\S+\.class)$", line.strip())
        if not m or "$" in m.group(1):                          # skip inner classes here
            continue
        fqn = m.group(1)[:-6].replace("/", ".")
        leaf = fqn.split(".")[-1]
        if leaf.lower() == simple.lower() or (q.count(".") and fqn.lower() == q.lower()):
            exact.append(fqn)
        elif simple.lower() in leaf.lower():
            fuzzy.append(fqn)

    matches = exact or fuzzy[:8]
    if not matches:
        return f"(no CDM class matching '{name}'. Try a simple type name like 'InterestRatePayout'.)"
    if not exact and fuzzy:
        return "Did you mean one of these? (call cdm_lookup again with the exact name)\n  " + "\n  ".join(matches)

    blocks = []
    for fqn in matches[:3]:
        leaf = fqn.split(".")[-1]
        bjp = subprocess.run(["javap", "-cp", jar, f"{fqn}${leaf}Builder"],
                             capture_output=True, text=True, timeout=20)
        if bjp.returncode == 0 and bjp.stdout:
            methods = set()
            for l in bjp.stdout.splitlines():
                mm = re.search(r"\b((?:set|add)\w+)\(([^)]*)\)", l)
                if mm and mm.group(2):                          # skip no-arg overloads
                    methods.add(f"{mm.group(1)}({_simplify_types(mm.group(2))})")
            body = "\n  ".join(sorted(methods)[:60]) or "(no set*/add* methods)"
            blocks.append(f"{fqn} — builder methods:\n  {body}")
            continue
        # No builder → likely an enum: list its constants.
        cjp = subprocess.run(["javap", "-cp", jar, fqn], capture_output=True, text=True, timeout=20)
        consts = [mm.group(1) for l in cjp.stdout.splitlines()
                  if (mm := re.search(r"public static final \S+ (\w+);", l.strip()))]
        if consts:
            blocks.append(f"{fqn} — enum constants:\n  " + ", ".join(consts))
        else:
            sigs = [_simplify_types(l.strip()) for l in cjp.stdout.splitlines() if l.strip().startswith("public")][:30]
            blocks.append(f"{fqn}:\n  " + "\n  ".join(sigs))
    return "\n\n".join(blocks)


@mcp.tool()
def cdm_lookup(name: str) -> str:
    """Look up the REAL CDM 6.19 API for a type, from the jar (via javap).

    Use this to VERIFY a builder method or enum constant before writing it, or to
    fix a "cannot find symbol" compile error — instead of guessing.

    Args:
        name: A CDM type name. Simple ('TradeLot', 'DayCountFractionEnum') or
              fully-qualified ('cdm.product.template.TradeLot').

    Returns (plain text):
        - for a type:  its builder set*/add* method signatures;
        - for an enum: its real constants;
        - or a "did you mean…" list / a not-found message.
    """
    return _lookup(name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="cdm_lookup MCP server (CDM API introspection)")
    parser.add_argument("--transport", default="stdio",
                        choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8006)
    args = parser.parse_args()

    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
