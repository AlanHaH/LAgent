param(
    [switch]$ValidateOnly,
    [int]$Port = 0
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$serviceDir = Join-Path $projectRoot 'weread-mcp'
$pythonExe = Join-Path $serviceDir '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy .env.example to .env and configure it first."
}
if (-not (Test-Path -LiteralPath $pythonExe)) {
    throw "Missing Python environment. Run: cd weread-mcp; python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -e `".[dev]`""
}

$config = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $config[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
        }
    }
}

$mapping = @{
    WEREAD_MCP_HOST = 'WEREAD_MCP_HOST'
    WEREAD_MCP_PORT = 'WEREAD_MCP_PORT'
    WEREAD_MCP_FAKE = 'WEREAD_MCP_FAKE'
    WEREAD_MCP_CREDENTIALS_PATH = 'WEREAD_MCP_CREDENTIALS_PATH'
    WEREAD_MCP_REQUEST_TIMEOUT_SECONDS = 'WEREAD_MCP_REQUEST_TIMEOUT_SECONDS'
}
foreach ($target in $mapping.Keys) {
    $source = $mapping[$target]
    if ($config.ContainsKey($source)) {
        Set-Item -Path ("Env:{0}" -f $target) -Value $config[$source]
    }
}

if ($Port -eq 0) {
    $Port = 8091
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw 'Port must be between 1 and 65535.'
}
$env:WEREAD_MCP_PORT = $Port.ToString()

if ($ValidateOnly) {
    Write-Host 'Local weread-mcp configuration is valid.'
    Write-Host ("Python: {0}" -f $pythonExe)
    Write-Host ("URL: http://127.0.0.1:{0}/mcp" -f $Port)
    exit 0
}

Write-Host ("Starting weread-mcp at http://127.0.0.1:{0}/mcp" -f $Port)
Push-Location $serviceDir
try {
    & $pythonExe -m weread_mcp
} finally {
    Pop-Location
}
