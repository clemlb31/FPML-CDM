#!/usr/bin/env python3
import os
import shutil
import subprocess
from pathlib import Path


IMAGE = 'maven:3.9-eclipse-temurin-21'


def find_runtime():
    # Only support podman as requested
    return 'podman' if shutil.which('podman') else None


def run_tests(project_dir: Path):
    runtime = find_runtime()
    if runtime is None:
        print('podman not found in PATH. Install podman to run tests in container.')
        return 2

    abs_path = str(project_dir.resolve())
    m2_cache = str(Path.home() / '.m2')
    cmd = [
        'podman',
        'run',
        '--rm',
        '-v',
        f'{abs_path}:/workspace',
        '-v',
        f'{m2_cache}:/root/.m2',
        '-w',
        '/workspace',
        IMAGE,
        'mvn',
        '-B',
        'test'
    ]

    print('Running tests in podman (summary only)...')
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out = proc.stdout or ""

    # Try to extract concise summary: Tests run / Failures / Errors and BUILD status
    lines = out.splitlines()
    summary_lines = []

    # collect lines like 'Tests run: 6, Failures: 0, Errors: 6, Skipped: 0'
    import re
    for line in lines:
        m = re.search(r"Tests run:.*", line)
        if m:
            summary_lines.append(m.group(0))

    # collect ERROR lines that mention failing tests
    for line in lines:
        if line.strip().startswith('[ERROR]') and '::' not in line:
            # keep concise ERROR messages (test failures listed earlier)
            summary_lines.append(line.strip())

    # BUILD SUCCESS / FAILURE
    for line in reversed(lines[-50:]):
        if 'BUILD SUCCESS' in line or 'BUILD FAILURE' in line:
            summary_lines.append(line.strip())
            break

    if summary_lines:
        print('\n'.join(summary_lines))
    else:
        # fallback: print last 40 lines
        print('\n'.join(lines[-40:]))

    return proc.returncode


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--project-dir', default='workspaces/current_project/java_project')
    args = parser.parse_args()
    project_dir = Path(args.project_dir)
    if not project_dir.exists():
        print('Project directory does not exist. Run create_maven_project.py first.')
        return 1
    return_code = run_tests(project_dir)
    if return_code == 0:
        print('Tests ran successfully.')
    else:
        print(f'Tests finished with code {return_code}')


if __name__ == '__main__':
    raise SystemExit(main())
