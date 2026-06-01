"""
ReAct Agent with Sub-Agents — LangGraph + vLLM + MCP
=====================================================

Architecture (matches the project diagram):
  - Main Agent: ReAct loop using vLLM (ChatOpenAI-compatible endpoint)
    - Has access to all MCP tools (filesystem, triage, validator, mapping, tavily)
    - Can spawn sub-agents via `run_subagent` tool with a step budget
  - Sub-Agent: Same ReAct loop but with a max_steps limit set by the main agent
    - Inherits all MCP tools from the main agent
    - Cannot spawn further sub-agents (no recursion)

Usage:
  python -m agent.react_graph
  python -m agent.react_graph --task "Generate a FpML to CDM converter for IRS"
  python -m agent.react_graph --task-file task.md
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import textwrap
from pathlib import Path
from typing import Annotated, Any, Literal, Optional, TypedDict

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.tools import tool as langchain_tool
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from langchain_mcp_adapters.client import MultiServerMCPClient

from agent.helpers import get_servers

# ── Load .env ─────────────────────────────────────────────────────────────────
_env_file = Path(__file__).resolve().parents[1] / ".env"
if _env_file.exists():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _, _v = _line.partition("=")
            os.environ.setdefault(_k.strip(), _v.strip())


# ── vLLM as ChatOpenAI ───────────────────────────────────────────────────────

def get_llm(temperature: float = 0.2, max_tokens: int = 4096) -> ChatOpenAI:
    """Create a ChatOpenAI instance pointing to the vLLM server."""
    base_url = os.getenv("VLLM_BASE_URL", "http://10.27.40.184:8000/v1")
    model = os.getenv("VLLM_MODEL", "/models/qwen3.6-27b-fp8")
    return ChatOpenAI(
        base_url=base_url,
        api_key="not-needed",
        model=model,
        temperature=temperature,
        max_tokens=max_tokens,
        extra_body={"chat_template_kwargs": {"enable_thinking": False}},
    )


# ── State ─────────────────────────────────────────────────────────────────────

class AgentState(TypedDict):
    """State for the main agent and sub-agents."""
    messages: Annotated[list[BaseMessage], add_messages]
    steps_taken: int
    max_steps: int          # 0 = unlimited (main agent), >0 = sub-agent budget
    is_subagent: bool


# ── Sub-agent execution ──────────────────────────────────────────────────────

# These are set at graph build time — the sub-agent tool closes over them.
_mcp_tools: list = []
_llm: ChatOpenAI | None = None


def _build_subagent_graph(mcp_tools: list, llm: ChatOpenAI):
    """Build a sub-agent graph (no run_subagent tool — prevents recursion)."""

    tool_node = ToolNode(mcp_tools)
    llm_with_tools = llm.bind_tools(mcp_tools) if mcp_tools else llm

    async def agent_node(state: AgentState) -> dict:
        steps = state.get("steps_taken", 0)
        max_steps = state.get("max_steps", 10)

        if steps >= max_steps:
            return {
                "messages": [AIMessage(content=f"[Sub-agent] Step budget exhausted ({max_steps} steps). Returning what I have so far.")],
                "steps_taken": steps,
            }

        response = await llm_with_tools.ainvoke(state["messages"])
        return {
            "messages": [response],
            "steps_taken": steps + 1,
        }

    def should_continue(state: AgentState) -> Literal["tools", "end"]:
        steps = state.get("steps_taken", 0)
        max_steps = state.get("max_steps", 10)

        if steps >= max_steps:
            return "end"

        last = state["messages"][-1]
        if isinstance(last, AIMessage) and last.tool_calls:
            return "tools"
        return "end"

    g = StateGraph(AgentState)
    g.add_node("agent", agent_node)
    g.add_node("tools", tool_node)
    g.add_edge(START, "agent")
    g.add_conditional_edges("agent", should_continue, {"tools": "tools", "end": END})
    g.add_edge("tools", "agent")
    return g.compile()


async def _run_subagent_impl(task: str, max_steps: int, context: str = "") -> str:
    """Execute a sub-agent with a step budget. Called by the run_subagent tool."""
    global _mcp_tools, _llm

    if not _llm:
        return "Error: LLM not initialized"

    subgraph = _build_subagent_graph(_mcp_tools, _llm)

    system_prompt = textwrap.dedent(f"""\
        You are a focused sub-agent. Complete the assigned task efficiently.
        You have a budget of {max_steps} tool-calling steps — use them wisely.
        Be concise in your responses. Focus on the task.
        When done, provide a clear summary of what you accomplished.
    """).strip()

    messages: list[BaseMessage] = [SystemMessage(content=system_prompt)]
    if context:
        messages.append(HumanMessage(content=f"Context from the main agent:\n{context}"))
    messages.append(HumanMessage(content=task))

    initial_state: AgentState = {
        "messages": messages,
        "steps_taken": 0,
        "max_steps": max_steps,
        "is_subagent": True,
    }

    print(f"  [subagent] Starting with budget={max_steps} steps")
    result = await subgraph.ainvoke(initial_state)
    print(f"  [subagent] Done after {result['steps_taken']} steps")

    # Extract the last AI message as the sub-agent's response
    for msg in reversed(result["messages"]):
        if isinstance(msg, AIMessage) and msg.content:
            return msg.content

    return "(sub-agent produced no output)"


# ── Main agent graph ─────────────────────────────────────────────────────────

def build_react_graph(
    mcp_tools: list,
    llm: ChatOpenAI,
    system_prompt: str = "",
    max_steps: int = 0,
):
    """
    Build the main ReAct agent graph.

    Args:
        mcp_tools:     List of MCP tools from MultiServerMCPClient
        llm:           ChatOpenAI instance pointing to vLLM
        system_prompt: System prompt for the main agent
        max_steps:     Max steps (0 = unlimited for main agent)
    """
    global _mcp_tools, _llm
    _mcp_tools = mcp_tools
    _llm = llm

    # ── Create the run_subagent tool ──────────────────────────────────────────
    @langchain_tool
    async def run_subagent(
        task: str,
        max_steps: int = 10,
        context: str = "",
    ) -> str:
        """Spawn a sub-agent to handle a focused task autonomously.

        The sub-agent has access to all MCP tools (filesystem, validator, triage, etc.)
        but cannot spawn further sub-agents. It runs for at most max_steps tool-calling
        steps, then returns its result.

        Use this to delegate complex multi-step work like:
        - Writing and compiling Java code (give 15-20 steps)
        - Researching CDM structure from knowledge base (give 5-10 steps)
        - Running and fixing compile errors iteratively (give 10-15 steps)

        Args:
            task:      Clear description of what the sub-agent should accomplish
            max_steps: Maximum number of tool-calling steps the sub-agent can take (1-30)
            context:   Optional context/data from the main conversation to pass along
        """
        max_steps = max(1, min(30, max_steps))  # Clamp to 1-30
        return await _run_subagent_impl(task, max_steps, context)

    # ── All tools = MCP tools + run_subagent ──────────────────────────────────
    all_tools = list(mcp_tools) + [run_subagent]
    tool_node = ToolNode(all_tools)
    llm_with_tools = llm.bind_tools(all_tools) if all_tools else llm

    # ── Default system prompt ─────────────────────────────────────────────────
    if not system_prompt:
        system_prompt = textwrap.dedent("""\
            You are an expert Java developer specializing in FpML to CDM (Common Domain Model)
            transformations. You generate, compile, test, and fix Java code using MCP tools.

            Your available MCP servers:
            - **Filesystem** (read_file, write_file, list_directory): Read/write Java source files
              in the workspaces/ directory and read knowledge from knowledge_base/
            - **Validator** (compile_project, run_test, run_test_all, extract_method_source):
              Compile Maven projects and run tests in a Docker container
            - **Triage** (triage_compile_error, triage_test_diff): Analyse compilation errors
              and test diffs to get precise fix directives
            - **Mapping** (get_maven_dependencies, ask_human): Get CDM Maven dependencies
              and ask the human operator for clarification
            - **Tavily** (tavily_search): Search the internet for CDM/FpML documentation

            You can also use **run_subagent** to delegate focused tasks to a sub-agent.
            Give each sub-agent a clear task and an appropriate step budget:
            - Research/reading tasks: 5-10 steps
            - Code generation + compilation: 15-20 steps
            - Iterative fix loops: 10-15 steps

            Workflow for generating a new transformer:
            1. Read training examples from data/train/ to understand the FpML→CDM mapping
            2. Read knowledge_base/ for CDM class hierarchy, enum mappings, date handling
            3. Get Maven dependencies via get_maven_dependencies
            4. Write pom.xml, Java source files via filesystem tools
            5. Compile with compile_project, fix errors using triage + patch loop
            6. Run tests with run_test, fix diffs using triage + patch loop
            7. Iterate until all tests pass

            Be systematic. Use sub-agents for parallelizable or repetitive work.
        """).strip()

    # ── Agent node ────────────────────────────────────────────────────────────
    async def agent_node(state: AgentState) -> dict:
        steps = state.get("steps_taken", 0)
        agent_max = state.get("max_steps", 0)

        # Enforce step limit if set (>0)
        if agent_max > 0 and steps >= agent_max:
            return {
                "messages": [AIMessage(content=f"Step budget exhausted ({agent_max} steps).")],
                "steps_taken": steps,
            }

        # Ensure system prompt is first message
        messages = list(state["messages"])
        if not messages or not isinstance(messages[0], SystemMessage):
            messages.insert(0, SystemMessage(content=system_prompt))

        response = await llm_with_tools.ainvoke(messages)
        return {
            "messages": [response],
            "steps_taken": steps + 1,
        }

    def should_continue(state: AgentState) -> Literal["tools", "end"]:
        agent_max = state.get("max_steps", 0)
        steps = state.get("steps_taken", 0)

        if agent_max > 0 and steps >= agent_max:
            return "end"

        last = state["messages"][-1]
        if isinstance(last, AIMessage) and last.tool_calls:
            return "tools"
        return "end"

    # ── Build graph ───────────────────────────────────────────────────────────
    g = StateGraph(AgentState)
    g.add_node("agent", agent_node)
    g.add_node("tools", tool_node)
    g.add_edge(START, "agent")
    g.add_conditional_edges("agent", should_continue, {"tools": "tools", "end": END})
    g.add_edge("tools", "agent")

    return g.compile(checkpointer=MemorySaver())


# ── CLI ───────────────────────────────────────────────────────────────────────

_DEFAULT_TASK = """\
Generate a Java Maven project that converts FpML 5.x IRS (Interest Rate Swap) XML
documents into CDM 6.x JSON format.

Steps:
1. Read 2-3 training examples from data/train/ to understand the mapping
2. Read the CDM class hierarchy and enum mappings from knowledge_base/
3. Use run_subagent to delegate the code writing task (budget: 20 steps)
4. Compile and test the generated code
5. Fix any issues using the triage tools
"""


async def main():
    parser = argparse.ArgumentParser(description="ReAct Agent with Sub-Agents (vLLM + MCP)")
    parser.add_argument("--task", type=str, default=None, help="Task description for the agent")
    parser.add_argument("--task-file", type=str, default=None, help="File containing the task description")
    parser.add_argument("--max-steps", type=int, default=0, help="Max steps for main agent (0=unlimited)")
    parser.add_argument("--system-prompt", type=str, default=None, help="Custom system prompt")
    parser.add_argument("--system-prompt-file", type=str, default=None, help="File containing system prompt")
    parser.add_argument("--interactive", action="store_true", help="Interactive chat mode")
    args = parser.parse_args()

    # Load task
    if args.task_file:
        task = Path(args.task_file).read_text(encoding="utf-8").strip()
    elif args.task:
        task = args.task
    elif not args.interactive:
        task = _DEFAULT_TASK
    else:
        task = None

    # Load system prompt
    system_prompt = ""
    if args.system_prompt_file:
        system_prompt = Path(args.system_prompt_file).read_text(encoding="utf-8").strip()
    elif args.system_prompt:
        system_prompt = args.system_prompt

    # ── Connect to MCP servers ────────────────────────────────────────────────
    servers = get_servers()
    print(f"Connecting to MCP servers: {list(servers.keys())}")

    client = MultiServerMCPClient(servers)
    mcp_tools = await client.get_tools()
    tool_names = [t.name for t in mcp_tools]
    print(f"Loaded {len(mcp_tools)} MCP tools: {sorted(tool_names)}")

    llm = get_llm()
    print(f"LLM: {os.getenv('VLLM_MODEL', '?')} @ {os.getenv('VLLM_BASE_URL', '?')}")

    graph = build_react_graph(
        mcp_tools=mcp_tools,
        llm=llm,
        system_prompt=system_prompt,
        max_steps=args.max_steps,
    )

    config = {"configurable": {"thread_id": "main"}}

    if args.interactive:
        # ── Interactive mode ──────────────────────────────────────────────
        print("\n=== Interactive mode (type 'quit' to exit) ===\n")
        state: AgentState = {
            "messages": [],
            "steps_taken": 0,
            "max_steps": args.max_steps,
            "is_subagent": False,
        }
        while True:
            try:
                user_input = input("You: ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if user_input.lower() in ("quit", "exit", "q"):
                break
            if not user_input:
                continue

            state["messages"].append(HumanMessage(content=user_input))
            result = await graph.ainvoke(state, config=config)
            state = result

            # Print last AI message
            for msg in reversed(result["messages"]):
                if isinstance(msg, AIMessage) and msg.content:
                    print(f"\nAgent: {msg.content}\n")
                    break
    else:
        # ── Single task mode ──────────────────────────────────────────────
        print(f"\nTask: {task[:200]}{'...' if len(task) > 200 else ''}\n")

        initial_state: AgentState = {
            "messages": [HumanMessage(content=task)],
            "steps_taken": 0,
            "max_steps": args.max_steps,
            "is_subagent": False,
        }

        result = await graph.ainvoke(initial_state, config=config)

        # Print final response
        print("\n" + "=" * 60)
        for msg in reversed(result["messages"]):
            if isinstance(msg, AIMessage) and msg.content:
                print(f"\nFinal response:\n{msg.content}")
                break
        print(f"\nTotal steps: {result['steps_taken']}")


if __name__ == "__main__":
    asyncio.run(main())
