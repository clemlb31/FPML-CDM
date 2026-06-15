#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Start the 5 local MCP servers used by the autonomous agent (Windows).
.DESCRIPTION
  Windows equivalent of mcp_servers/start_all.sh. Every MCP is its own Python
  server under mcp_servers/ (FastMCP streamable-http) — no Node/npx needed:
    1. filesystem      — port 8080 (read knowledge_base/+data/, read-write workspaces/)
    2. validator       — port 8003 (compile_project / run_test, Docker Maven)
    3. grep            — port 8005 (ripgrep content search)
    4. cdm_lookup      — port 8006 (CDM 6.19 API introspection via javap)
    5. internet_search — port 8007 (web search, Tavily wrapper; needs TAVILY_KEY)

  Run from the project root. Press Ctrl+C to stop all servers.
#>

$ErrorActionPreference = "Stop"
$ROOT = Split-Path -Parent $PSScriptRoot  # project root (one level up from scripts/)
$PY = Join-Path $ROOT ".venv\Scripts\python.exe"

# Load .env so child servers inherit TAVILY_KEY etc.
$envFile = Join-Path $ROOT ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), "Process")
        }
    }
}

$servers = @(
    @{ Name = "filesystem-8080";      Module = "mcp_servers.filesystem_server.server";      Port = 8080 },
    @{ Name = "validator-8003";       Module = "mcp_servers.validator_server.server";       Port = 8003 },
    @{ Name = "grep-8005";            Module = "mcp_servers.grep_server.server";            Port = 8005 },
    @{ Name = "cdm_lookup-8006";      Module = "mcp_servers.cdm_lookup_server.server";      Port = 8006 },
    @{ Name = "internet_search-8007"; Module = "mcp_servers.internet_search_server.server"; Port = 8007 }
)

$jobs = @()
Write-Host "`n=== Starting MCP servers ===" -ForegroundColor Cyan
foreach ($s in $servers) {
    Write-Host "[start] $($s.Name)" -ForegroundColor Green
    $jobs += Start-Job -Name $s.Name -ScriptBlock {
        Set-Location $using:ROOT
        & $using:PY -m $using:s.Module --transport streamable-http --port $using:s.Port
    }
}

if (-not (docker info 2>$null)) {
    Write-Host "  WARN: Docker appears DOWN — validator's compile/run_test will fail." -ForegroundColor Yellow
}

Write-Host "`nURLs:" -ForegroundColor Cyan
Write-Host "  filesystem        http://localhost:8080/mcp"
Write-Host "  validator         http://localhost:8003/mcp  (requires Docker daemon)"
Write-Host "  grep              http://localhost:8005/mcp"
Write-Host "  cdm_lookup        http://localhost:8006/mcp"
Write-Host "  internet_search   http://localhost:8007/mcp  (needs TAVILY_KEY in .env)"
Write-Host "`nPress Ctrl+C to stop all servers.`n" -ForegroundColor Yellow

try {
    while ($true) {
        foreach ($job in $jobs) {
            $output = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($output) { Write-Host "[$($job.Name)] $output" }
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
