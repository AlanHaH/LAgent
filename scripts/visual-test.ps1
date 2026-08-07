param(
    [ValidateSet('ui', 'headed', 'headless', 'report')]
    [string]$Mode = 'ui',
    [string]$Scenario = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot 'frontend'

function Test-Service([string]$Name, [string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
            Write-Host "[OK] $Name" -ForegroundColor Green
            return
        }
    } catch {
        Write-Host "[ERROR] $Name is unavailable: $Url" -ForegroundColor Red
        throw "Start the project before running visual tests."
    }
}

Set-Location $frontendRoot

if ($Mode -ne 'report') {
    Test-Service 'Vue frontend' 'http://127.0.0.1:5300'
    Test-Service 'Spring Boot API' 'http://127.0.0.1:8080/actuator/health'
}

if (-not (Test-Path (Join-Path $frontendRoot 'node_modules\@playwright\test'))) {
    throw 'Playwright is not installed. Run npm install in the frontend directory.'
}

$grepArgs = @()
if (-not [string]::IsNullOrWhiteSpace($Scenario)) {
    $grepArgs = @('--grep', $Scenario)
}

switch ($Mode) {
    'ui' {
        Write-Host 'Opening the visual test console...' -ForegroundColor Cyan
        & npm.cmd run test:visual -- @grepArgs
    }
    'headed' {
        Write-Host 'Running tests in a visible browser...' -ForegroundColor Cyan
        & npm.cmd run test:e2e:headed -- @grepArgs
    }
    'headless' {
        Write-Host 'Running automated tests...' -ForegroundColor Cyan
        & npm.cmd run test:e2e -- @grepArgs
    }
    'report' {
        & npm.cmd run test:report
    }
}

if ($LASTEXITCODE -ne 0) {
    throw "The test command failed with exit code $LASTEXITCODE."
}
