#!/usr/bin/env python3
"""
scripts/visualize_graph.py
---------------------------
Renders the LangGraph state machine as a Mermaid diagram.

Usage:
    python scripts/visualize_graph.py               # print to stdout
    python scripts/visualize_graph.py --out docs/   # save docs/graph.mmd + docs/graph.png

Requires the project venv to be active (langgraph must be installed):
    source venv/bin/activate   # or wherever you ran setup.sh

The Mermaid .mmd file can be:
  - Viewed at https://mermaid.live  (paste content)
  - Rendered in VS Code with the "Mermaid Preview" extension
  - Embedded in README.md as a ```mermaid code block (renders on GitHub)

PNG output requires Pillow + graphviz:
    pip install Pillow
    apt install graphviz   (or brew install graphviz)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    from langgraph_agent.graph import build_graph
except ImportError as e:
    sys.exit(
        f"Import error: {e}\n"
        "Activate the project venv first:  source venv/bin/activate\n"
        "Then install deps:                pip install -r langgraph_agent/requirements.txt"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Visualise the LangGraph state machine")
    parser.add_argument("--out", default=None, metavar="DIR",
                        help="Output directory for graph.mmd and graph.png (optional)")
    args = parser.parse_args()

    # Build with empty tools_by_name — only needs graph structure, not live tools
    graph    = build_graph({})
    drawable = graph.get_graph()
    mermaid  = drawable.draw_mermaid()

    if args.out:
        out_dir  = Path(args.out)
        out_dir.mkdir(parents=True, exist_ok=True)

        mmd_path = out_dir / "graph.mmd"
        mmd_path.write_text(mermaid)
        print(f"Mermaid saved → {mmd_path}")

        # Wrap in a .md file so GitHub renders it automatically
        md_path = out_dir / "graph.md"
        md_path.write_text(f"# LangGraph State Machine\n\n```mermaid\n{mermaid}\n```\n")
        print(f"Markdown saved → {md_path}")

        # Try PNG
        png_path = out_dir / "graph.png"
        try:
            png_bytes = drawable.draw_mermaid_png()
            png_path.write_bytes(png_bytes)
            print(f"PNG saved    → {png_path}")
        except Exception as e:
            print(f"PNG skipped (install Pillow+graphviz for PNG output): {e}")
    else:
        print(mermaid)


if __name__ == "__main__":
    main()
