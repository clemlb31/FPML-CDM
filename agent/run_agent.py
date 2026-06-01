#!/usr/bin/env python3
"""Agent runner: generate Java implementation via LLM and run tests using MCP tools.

Flow:
  1. Create Maven project structure (pom + tests + placeholder).
  2. Collect training examples.
  3. Ask LLM to generate Calculator.java (or use --no-llm fallback).
  4. Write the file via MCP write_file tool.
  5. Run tests via MCP validate_code tool.
"""
import argparse
import asyncio
import json
import subprocess
import sys
import os
import re
from pathlib import Path
from contextlib import AsyncExitStack

from dotenv import load_dotenv
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

# Ensure project root on sys.path
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

load_dotenv()

from agent.llm_interface.LLM_interface import LLM as LLMClass


def collect_train_cases(data_dir: Path):
    cases = []
    for task_dir in data_dir.iterdir():
        if not task_dir.is_dir():
            continue
        for json_file in task_dir.glob('*.json'):
            try:
                data = json.loads(json_file.read_text())
            except Exception:
                continue
            cases.append(data)
    return cases


PROMPT_TEMPLATE = '''You are given unit tests and must produce the Java source file for Calculator.java
in package com.example that implements the tested methods.

VERY IMPORTANT: Output a single JSON object and nothing else. The object must include these keys:
{{
    "language": "java",
    "code": "<full source file as a single string>"
}}

Requirements:
- Use package com.example;
- Implement public class Calculator with static methods: add(int,int), multiply(int,int), power(int,int).
- Keep code simple and well-formatted.
- For power, implement integer exponent (non-negative) using a loop.

Unit tests / examples:
{examples}

Now output the JSON object only.
'''


def build_examples_text(cases):
    lines = []
    for c in cases:
        task = c.get('task')
        inp = c.get('input', [])
        expected = c.get('expected')
        if task == 'addition':
            lines.append(f'Calculator.add({inp[0]}, {inp[1]}) -> {expected}')
        elif task == 'multiplication':
            lines.append(f'Calculator.multiply({inp[0]}, {inp[1]}) -> {expected}')
        elif task == 'power':
            lines.append(f'Calculator.power({inp[0]}, {inp[1]}) -> {expected}')
    return '\n'.join(lines)


def run_create_project():
    # call existing script to generate pom/tests
    cmd = [sys.executable, str(ROOT / 'scripts' / 'create_maven_project.py')]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(proc.stdout)
    return proc.returncode


def ask_llm_for_code(prompt: str, model_name: str, lmstudio_base_url: str):
    resolved_model = model_name if model_name else os.getenv('MODEL_NAME')
    resolved_lmstudio = lmstudio_base_url if lmstudio_base_url else os.getenv('LMSTUDIO_BASE_URL')

    kwargs = {}
    if resolved_model:
        kwargs['model_name'] = resolved_model
    if resolved_lmstudio:
        kwargs['lmstudio_base_url'] = resolved_lmstudio

    with LLMClass(**kwargs) as llm:
        return llm.generate(prompt)


def write_calculator(java_text: str, project_root: Path):
    calc_path = project_root / 'src' / 'main' / 'java' / 'com' / 'example' / 'Calculator.java'
    calc_path.write_text(java_text)
    print(f'Wrote Calculator.java -> {calc_path}')


def run_tests():
    cmd = [sys.executable, str(ROOT / 'scripts' / 'compile_and_run_tests.py')]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(proc.stdout)
    return proc.returncode


def parse_llm_code(code: str) -> str:
    """Extract and clean Java source from raw LLM response."""
    # 1) try to parse JSON envelope
    try:
        obj = json.loads(code.strip())
        if isinstance(obj, dict) and 'code' in obj:
            code = obj['code']
    except Exception:
        pass

    # 2) strip markdown/code fences if present
    if '```' in code:
        parts = code.split('```')
        for i in range(1, len(parts), 2):
            block = parts[i]
            if block.strip().startswith('java'):
                block = re.sub(r'^\s*java\s*\n', '', block, flags=re.IGNORECASE)
            parsed = block.strip()
            if parsed:
                code = parsed
                break

    # 3) remove a stray leading language token on its own line
    code = re.sub(r'^\s*java\s*\n', '', code, flags=re.IGNORECASE)

    # 4) strip text that appears before the package declaration
    pkg_idx = code.find('package com.example;')
    if pkg_idx != -1:
        code = code[pkg_idx:]

    # 5) ensure package declaration exists
    if 'package com.example;' not in code:
        code = 'package com.example;\n\n' + code.lstrip()

    return code.strip() + '\n'


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default=None, help='Model name for LLM_interface (env default if omitted)')
    parser.add_argument('--lmstudio', default=None, help='LMStudio base url (env default if omitted)')
    parser.add_argument('--no-llm', action='store_true', help='Do not call LLM; use local simple implementation')
    args = parser.parse_args()

    project_root = ROOT / 'workspaces' / 'current_project' / 'java_project'
    data_root = ROOT / 'data' / 'train'
    calc_path = str(project_root / 'src' / 'main' / 'java' / 'com' / 'example' / 'Calculator.java')

    # 1) create project (pom + tests + placeholder Calculator)
    run_create_project()

    # 2) collect examples
    cases = collect_train_cases(data_root)
    examples = build_examples_text(cases)

    # 3) produce Java source
    if args.no_llm:
        java_text = (
            'package com.example;\n\n'
            'public class Calculator {\n'
            '    public static int add(int a, int b) { return a + b; }\n'
            '    public static int multiply(int a, int b) { return a * b; }\n'
            '    public static int power(int a, int b) {\n'
            '        if (b < 0) throw new IllegalArgumentException("Negative exponent not supported");\n'
            '        int r = 1; for (int i = 0; i < b; i++) r *= a; return r;\n'
            '    }\n'
            '}\n'
        )
    else:
        prompt = PROMPT_TEMPLATE.format(examples=examples)
        try:
            raw = ask_llm_for_code(prompt, args.model, args.lmstudio)
        except Exception as e:
            print('[error] LLM failed:', e)
            return 2
        java_text = parse_llm_code(raw)

    # 4) connect to MCP server and use its tools
    server_script = ROOT / 'mcp_servers' / 'mcp_server.py'
    server_params = StdioServerParameters(
        command=sys.executable,
        args=[str(server_script), '--mcp'],
    )

    async with AsyncExitStack() as stack:
        read, write = await stack.enter_async_context(stdio_client(server_params))
        session = await stack.enter_async_context(ClientSession(read, write))
        await session.initialize()

        # write Calculator.java via MCP
        print(f'[mcp] write_file -> {calc_path}')
        write_result = await session.call_tool('write_file', {'path': calc_path, 'content': java_text})
        print(write_result.content[0].text)

        # run tests via MCP
        print('[mcp] validate_code')
        test_result = await session.call_tool('validate_code', {})
        output = test_result.content[0].text
        print(output)

        rc = 1 if ('BUILD FAILURE' in output or 'FAILURE (code' in output) else 0

    return rc


if __name__ == '__main__':
    raise SystemExit(asyncio.run(main()))
