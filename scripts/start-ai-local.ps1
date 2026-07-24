param(
    [switch]$ValidateOnly,
    [int]$Port = 0
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$serviceDir = Join-Path $projectRoot 'ai-service'
$pythonExe = Join-Path $serviceDir '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy .env.example to .env and configure it first."
}
if (-not (Test-Path -LiteralPath $pythonExe)) {
    throw "Missing Python environment. Run: cd ai-service; python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -e `".[dev,embeddings]`""
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
    AI_INTERNAL_TOKEN = 'AI_INTERNAL_TOKEN'
    AI_MODEL_BASE_URL = 'MODEL_BASE_URL'
    AI_MODEL_API_KEY = 'MODEL_API_KEY'
    AI_MODEL_NAME = 'MODEL_NAME'
    AI_MODEL_MAX_OUTPUT_TOKENS = 'MODEL_MAX_OUTPUT_TOKENS'
    AI_MODEL_THINKING = 'MODEL_THINKING'
    AI_EMBEDDING_PROVIDER = 'AI_EMBEDDING_PROVIDER'
    AI_EMBEDDING_MODEL = 'AI_EMBEDDING_MODEL'
    AI_EMBEDDING_DEVICE = 'AI_EMBEDDING_DEVICE'
    AI_ALLOW_HASH_FALLBACK = 'AI_ALLOW_HASH_FALLBACK'
    AI_QDRANT_MODE = 'AI_QDRANT_MODE'
    AI_QDRANT_URL = 'AI_QDRANT_URL'
}
foreach ($target in $mapping.Keys) {
    $source = $mapping[$target]
    if ($config.ContainsKey($source)) {
        Set-Item -Path ("Env:{0}" -f $target) -Value $config[$source]
    }
}

if ($Port -eq 0 -and $config.ContainsKey('AI_SERVICE_BASE_URL') -and $config['AI_SERVICE_BASE_URL']) {
    try {
        $configuredUri = [Uri]$config['AI_SERVICE_BASE_URL']
        if ($configuredUri.Port -gt 0) {
            $Port = $configuredUri.Port
        }
    } catch {
        throw "AI_SERVICE_BASE_URL is not a valid URL: $($config['AI_SERVICE_BASE_URL'])"
    }
}
if ($Port -eq 0) {
    $Port = 8090
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw 'Port must be between 1 and 65535.'
}
$env:AI_SERVICE_PORT = $Port.ToString()

$missing = @('AI_INTERNAL_TOKEN', 'MODEL_BASE_URL', 'MODEL_API_KEY', 'MODEL_NAME') |
    Where-Object { -not $config.ContainsKey($_) -or -not $config[$_] }
if ($missing.Count -gt 0) {
    throw "Python AI configuration is incomplete: $($missing -join ', ')"
}

if ($ValidateOnly) {
    Write-Host 'Local Python AI configuration is valid.'
    Write-Host ("Python: {0}" -f $pythonExe)
    Write-Host ("URL: http://127.0.0.1:{0}" -f $Port)
    exit 0
}

Write-Host ("Starting Python AI service at http://127.0.0.1:{0}" -f $Port)
Push-Location $serviceDir
try {
    & $pythonExe -m uvicorn app.main:app --host 127.0.0.1 --port $Port
} finally {
    Pop-Location
}
