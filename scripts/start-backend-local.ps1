param(
    [switch]$ValidateOnly,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [string]$CorsAllowedOrigins = 'http://localhost:5300,http://127.0.0.1:5300'
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$backendDir = Join-Path $projectRoot 'backend'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing $envFile. Copy .env.example to .env and configure it first."
}

$config = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $name = $line.Substring(0, $separator).Trim()
            $value = $line.Substring($separator + 1).Trim()
            $config[$name] = $value
        }
    }
}

$mapping = @{
    DB_USERNAME = 'MYSQL_USER'
    DB_PASSWORD = 'MYSQL_PASSWORD'
    REDIS_HOST = 'REDIS_HOST'
    REDIS_PORT = 'REDIS_PORT'
    REDIS_ENABLED = 'REDIS_ENABLED'
    JWT_SECRET = 'JWT_SECRET'
    VERIFICATION_CODE_PEPPER = 'VERIFICATION_CODE_PEPPER'
    APP_ADMIN_USERNAME = 'APP_ADMIN_USERNAME'
    APP_ADMIN_EMAIL = 'APP_ADMIN_EMAIL'
    APP_ADMIN_PASSWORD = 'APP_ADMIN_PASSWORD'
    MAIL_HOST = 'MAIL_HOST'
    MAIL_PORT = 'MAIL_PORT'
    MAIL_USERNAME = 'MAIL_USERNAME'
    MAIL_PASSWORD = 'MAIL_PASSWORD'
    MAIL_FROM = 'MAIL_FROM'
    MAIL_AUTH_ENABLED = 'MAIL_AUTH_ENABLED'
    MAIL_STARTTLS_ENABLED = 'MAIL_STARTTLS_ENABLED'
    MAIL_SSL_ENABLED = 'MAIL_SSL_ENABLED'
    MODEL_BASE_URL = 'MODEL_BASE_URL'
    MODEL_API_KEY = 'MODEL_API_KEY'
    MODEL_SECRET_ENCRYPTION_KEY = 'MODEL_SECRET_ENCRYPTION_KEY'
    MODEL_NAME = 'MODEL_NAME'
    AI_SERVICE_ENABLED = 'AI_SERVICE_ENABLED'
    AI_SERVICE_BASE_URL = 'AI_SERVICE_BASE_URL'
    AI_INTERNAL_TOKEN = 'AI_INTERNAL_TOKEN'
    AI_SERVICE_TIMEOUT = 'AI_SERVICE_TIMEOUT'
    TASK_CHAT_HISTORY_MAX_MESSAGES = 'TASK_CHAT_HISTORY_MAX_MESSAGES'
    TASK_CHAT_HISTORY_MAX_CHARACTERS = 'TASK_CHAT_HISTORY_MAX_CHARACTERS'
}

foreach ($target in $mapping.Keys) {
    $source = $mapping[$target]
    if ($config.ContainsKey($source)) {
        Set-Item -Path ("Env:{0}" -f $target) -Value $config[$source]
    }
}

$database = 'adaptive_learning'
if ($config.ContainsKey('MYSQL_DATABASE') -and $config['MYSQL_DATABASE']) {
    $database = $config['MYSQL_DATABASE']
}

$env:DB_URL = "jdbc:mysql://localhost:3306/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:SERVER_PORT = $Port.ToString()
$env:CORS_ALLOWED_ORIGINS = $CorsAllowedOrigins

$mavenExe = $null
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if ($mavenCommand) {
    $mavenExe = $mavenCommand.Source
}

$mavenInstalls = Get-ChildItem 'H:\maven\apache-maven-*\bin\mvn.cmd' -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Directory.Parent.Name -replace '^apache-maven-', '') } -Descending
if ($mavenInstalls) {
    $mavenExe = $mavenInstalls[0].FullName
}

# H:\maven 可能损坏（缺 plexus-classworlds），先用 mvn -v 探活，失败则回退 IntelliJ 自带 maven
function Test-MavenExe([string]$exe) {
    if (-not $exe) { return $false }
    try {
        & $exe -v *> $null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

$ideaMavenHome = 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3'
$useIdeaMaven = $false
if (-not (Test-MavenExe $mavenExe)) {
    if (Test-Path (Join-Path $ideaMavenHome 'bin\m2.conf')) {
        Write-Warning "Maven at '$mavenExe' is broken, falling back to IntelliJ bundled Maven."
        $useIdeaMaven = $true
    } elseif (-not $mavenExe) {
        throw 'Maven 3.6.3 or newer was not found.'
    } else {
        throw "Maven at '$mavenExe' is broken (mvn -v failed) and no IntelliJ bundled Maven was found."
    }
}

if ($ValidateOnly) {
    Write-Host 'Local backend configuration is valid.'
    if ($useIdeaMaven) {
        Write-Host ("Maven: IntelliJ bundled ({0})" -f $ideaMavenHome)
    } else {
        Write-Host ("Maven: {0}" -f $mavenExe)
    }
    $missingMail = @('MAIL_HOST', 'MAIL_USERNAME', 'MAIL_PASSWORD') |
        Where-Object { -not $config.ContainsKey($_) -or -not $config[$_] }
    if ($missingMail.Count -gt 0) {
        Write-Warning ("SMTP is not fully configured. Registration and password recovery will be unavailable. Missing: {0}" -f ($missingMail -join ', '))
    }
    exit 0
}

Write-Host ("Starting backend at http://localhost:{0}" -f $Port)
Push-Location $backendDir
try {
    if ($useIdeaMaven) {
        $m2Conf = Join-Path $ideaMavenHome 'bin\m2.conf'
        $classworldsJar = Join-Path $ideaMavenHome 'boot\plexus-classworlds-2.9.0.jar'
        & java "-Dmaven.home=$ideaMavenHome" "-Dmaven.multiModuleProjectDirectory=$backendDir" "-Dclassworlds.conf=$m2Conf" -classpath $classworldsJar org.codehaus.plexus.classworlds.launcher.Launcher -s maven-settings.xml spring-boot:run
    } else {
        & $mavenExe -s maven-settings.xml spring-boot:run
    }
} finally {
    Pop-Location
}
