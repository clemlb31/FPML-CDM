#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Start all MCP servers required by the LangGraph agent.
.DESCRIPTION
  Launches:
    1. Filesystem MCP (supergateway) — port 8080 (workspaces + knowledge_base + data/train)
    2. Examples MCP (supergateway) — port 8081 (data/train read-only)
    3. Triage server (Python) — port 8002
    4. Validator server (Python) — port 8003
    5. Mapping server (Python) — port 8004
  
  Run from the project root directory.
  Press Ctrl+C to stop all servers.
#>

$ErrorActionPreference = "Stop"
$ROOT = Split-Path -Parent $PSScriptRoot  # project root (one level up from scripts/)

# Load .env if it exists
$envFile = Join-Path $ROOT ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
            Write-Host "  env: $($Matches[1].Trim())" -ForegroundColor DarkGray
        }
    }
}

$jobs = @()

Write-Host "`n=== Starting MCP Servers ===" -ForegroundColor Cyan

# 1. Filesystem MCP (workspaces + knowledge_base + data/train)
Write-Host "[1/5] Filesystem MCP on port 8080..." -ForegroundColor Green
$jobs += Start-Job -Name "filesystem-8080" -ScriptBlock {
    Set-Location $using:ROOT
    npx -y supergateway --port 8080 --outputTransport streamableHttp `
        --stdio "npx -y @modelcontextprotocol/server-filesystem .\workspaces .\knowledge_base .\data\train"
}

# 2. Examples MCP (data/train read-only)
Write-Host "[2/5] Examples MCP on port 8081..." -ForegroundColor Green
$jobs += Start-Job -Name "examples-8081" -ScriptBlock {
    Set-Location $using:ROOT
    npx -y supergateway --port 8081 --outputTransport streamableHttp `
        --stdio "npx -y @modelcontextprotocol/server-filesystem .\data\train"
}

# 3. Triage server
Write-Host "[3/5] Triage server on port 8002..." -ForegroundColor Green
$jobs += Start-Job -Name "triage-8002" -ScriptBlock {
    Set-Location $using:ROOT
    & .\.venv\Scripts\python.exe -m mcp_servers.triage_server.server --transport streamable-http --port 8002
}

# 4. Validator server
Write-Host "[4/5] Validator server on port 8003..." -ForegroundColor Green
$jobs += Start-Job -Name "validator-8003" -ScriptBlock {
    Set-Location $using:ROOT
    & .\.venv\Scripts\python.exe -m mcp_servers.validator_server.server --transport streamable-http --port 8003
}

# 5. Mapping server
Write-Host "[5/5] Mapping server on port 8004..." -ForegroundColor Green
$jobs += Start-Job -Name "mapping-8004" -ScriptBlock {
    Set-Location $using:ROOT
    & .\.venv\Scripts\python.exe -m mcp_servers.mapping_server.server --transport streamable-http --port 8004
}

Write-Host "`n=== All servers started ===" -ForegroundColor Cyan
Write-Host "  Filesystem MCP:  http://localhost:8080/mcp" -ForegroundColor White
Write-Host "  Examples MCP:    http://localhost:8081/mcp" -ForegroundColor White
Write-Host "  Triage MCP:      http://localhost:8002/mcp" -ForegroundColor White
Write-Host "  Validator MCP:   http://localhost:8003/mcp" -ForegroundColor White
Write-Host "  Mapping MCP:     http://localhost:8004/mcp" -ForegroundColor White
Write-Host "`nPress Ctrl+C to stop all servers.`n" -ForegroundColor Yellow

try {
    # Stream output from all jobs
    while ($true) {
        foreach ($job in $jobs) {
            $output = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($output) {
                Write-Host "[$($job.Name)] $output"
            }
            if ($job.State -eq "Failed") {
                Write-Host "[$($job.Name)] FAILED!" -ForegroundColor Red
                Receive-Job -Job $job -ErrorAction SilentlyContinue | Write-Host
            }
        }
        Start-Sleep -Milliseconds 500
    }
}
finally {
    Write-Host "`nStopping all servers..." -ForegroundColor Yellow
    $jobs | Stop-Job -PassThru | Remove-Job -Force
    Write-Host "All servers stopped." -ForegroundColor Green
}
