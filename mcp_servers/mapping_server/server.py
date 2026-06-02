"""
Mapping Server — MCP tool for human-in-the-loop interaction
============================================================
Provides tools for the agent to ask questions to the human operator
when disambiguation or domain knowledge is needed.

Tools exposed:
  ask_human          — pose a question to the operator, wait for response
  get_maven_dependencies — return CDM/Rosetta Maven dependency blocks for pom.xml
"""

import json
import os
from pathlib import Path

from mcp.server.fastmcp import FastMCP

mcp = FastMCP(
    "mapping",
    instructions=(
        "Mapping server: provides human-in-the-loop interaction and "
        "domain-specific utilities like Maven dependency resolution for CDM projects."
    ),
)

# ── Maven dependencies (static, curated for CDM 6.x via Maven Central) ────────
# Versions match what the `main` branch uses successfully (cdm 6.19.0, jackson 2.17.2).
# org.finos.cdm IS on Maven Central — no custom repository needed.

_CDM_DEPENDENCIES = """\
<dependency>
  <groupId>org.finos.cdm</groupId>
  <artifactId>cdm-java</artifactId>
  <version>${cdm.version}</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.datatype</groupId>
  <artifactId>jackson-datatype-jsr310</artifactId>
  <version>${jackson.version}</version>
</dependency>
<dependency>
  <groupId>com.google.inject</groupId>
  <artifactId>guice</artifactId>
  <version>6.0.0</version>
</dependency>
"""

_CDM_PROPERTIES = """\
<cdm.version>6.19.0</cdm.version>
<jackson.version>2.17.2</jackson.version>
"""

# Empty by design: org.finos.cdm is on Maven Central, no custom repo required.
_CDM_REPOSITORIES = ""


@mcp.tool()
def get_maven_dependencies() -> dict:
    """
    Return the Maven dependency blocks for the CDM Java project.

    Returns:
        {
          dependencies_xml: str,  # <dependency> blocks to insert in <dependencies>
          properties_xml: str,    # <properties> entries for version management
          repositories_xml: str,  # <repository> entries (empty: Central only)
        }
    """
    return {
        "dependencies_xml": _CDM_DEPENDENCIES.strip(),
        "properties_xml":   _CDM_PROPERTIES.strip(),
        "repositories_xml": _CDM_REPOSITORIES.strip(),
    }


# ── Human interaction ─────────────────────────────────────────────────────────

_pending_questions: dict[str, str] = {}


@mcp.tool()
def ask_human(question: str, context: str = "") -> dict:
    """
    Ask a question to the human operator. This blocks until the operator responds.
    Use this when the agent needs disambiguation or domain clarification.

    Args:
        question: The question to ask
        context:  Optional additional context to help the operator answer

    Returns:
        { answer: str }
    """
    print(f"\n{'='*60}")
    print(f"🤖 AGENT QUESTION:")
    print(f"  {question}")
    if context:
        print(f"\n  Context: {context}")
    print(f"{'='*60}")

    answer = input("👤 Your answer: ").strip()
    return {"answer": answer}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", default="stdio", choices=["stdio", "streamable-http", "streamablehttp"])
    parser.add_argument("--port", type=int, default=8004)
    args = parser.parse_args()
    transport = args.transport.replace("streamablehttp", "streamable-http")
    if transport == "streamable-http":
        mcp.settings.port = args.port
        mcp.settings.host = "0.0.0.0"
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
