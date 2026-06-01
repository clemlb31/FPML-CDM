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

# ── Maven dependencies (static, curated for CDM/Rosetta) ─────────────────────

_CDM_DEPENDENCIES = """\
    <dependency>
      <groupId>com.regnosys</groupId>
      <artifactId>rosetta-common</artifactId>
      <version>${rosetta.version}</version>
    </dependency>
    <dependency>
      <groupId>org.isda.cdm</groupId>
      <artifactId>cdm-java</artifactId>
      <version>${cdm.version}</version>
    </dependency>
    <dependency>
      <groupId>com.regnosys</groupId>
      <artifactId>rosetta-translate</artifactId>
      <version>${rosetta.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.0</version>
    </dependency>
    <dependency>
      <groupId>org.dom4j</groupId>
      <artifactId>dom4j</artifactId>
      <version>2.1.4</version>
    </dependency>
    <dependency>
      <groupId>jaxen</groupId>
      <artifactId>jaxen</artifactId>
      <version>2.0.0</version>
    </dependency>
"""

_CDM_PROPERTIES = """\
    <rosetta.version>11.25.1</rosetta.version>
    <cdm.version>6.0.0-dev.67</cdm.version>
"""


@mcp.tool()
def get_maven_dependencies() -> dict:
    """
    Return the CDM/Rosetta Maven dependency blocks for pom.xml.

    Returns:
        {
          dependencies_xml: str,  # <dependency> blocks to insert in <dependencies>
          properties_xml: str,    # <properties> blocks for version management
          repositories_xml: str,  # <repository> blocks for Rosetta/CDM artifacts
        }
    """
    return {
        "dependencies_xml": _CDM_DEPENDENCIES.strip(),
        "properties_xml": _CDM_PROPERTIES.strip(),
        "repositories_xml": """\
    <repository>
      <id>regnosys-releases</id>
      <url>https://regnosys.jfrog.io/artifactory/libs-snapshot</url>
    </repository>""",
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
