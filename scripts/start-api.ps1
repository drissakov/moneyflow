param([string]$EnvFile = '.env')

$ErrorActionPreference = 'Stop'
$taskRepoRoot = Split-Path -Parent $PSScriptRoot
$taskEnvPath = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $taskRepoRoot $EnvFile }
if (-not (Test-Path -LiteralPath $taskEnvPath)) { throw 'Copy .env.example to .env and configure DATABASE_URL first.' }
foreach ($line in Get-Content -LiteralPath $taskEnvPath) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
    }
}
Push-Location (Join-Path $taskRepoRoot 'backend')
try {
    go run ./cmd/migrate
    if ($LASTEXITCODE -ne 0) { throw 'Database migrations failed' }
    go run ./cmd/api
    if ($LASTEXITCODE -ne 0) { throw 'API stopped with an error' }
} finally { Pop-Location }
