param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')][string]$Server,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z_][a-z0-9_-]*$')][string]$User,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-f0-9]{40}$')][string]$Commit,
    [Parameter(Mandatory = $true)][string]$KnownHosts,
    [ValidatePattern('^/[a-zA-Z0-9_./-]+$')][string]$RemoteDirectory = '/opt/moneyflow',
    [ValidateRange(1, 65535)][int]$Port = 22,
    [switch]$FirstInstall,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
if ($FirstInstall -and $CheckOnly) { throw 'Choose FirstInstall or CheckOnly.' }
if ($RemoteDirectory.Split('/') -contains '..') { throw 'RemoteDirectory must not contain .. segments.' }
$knownHostsPath = (Resolve-Path -LiteralPath $KnownHosts).Path
if (-not (Test-Path -LiteralPath $knownHostsPath -PathType Leaf)) { throw 'KnownHosts must be a verified host-key file.' }
Get-Command ssh -ErrorAction Stop | Out-Null
$mode = if ($FirstInstall) { ' --first-install' } elseif ($CheckOnly) { ' --check' } else { '' }
# Every interpolated remote argument has a restrictive allowlist. No credentials are copied.
$remoteCommand = "cd '$RemoteDirectory' && bash scripts/production/deploy.sh '$Commit'$mode"
& ssh -p $Port -o 'StrictHostKeyChecking=yes' -o "UserKnownHostsFile=$knownHostsPath" -o 'BatchMode=yes' "$User@$Server" $remoteCommand
if ($LASTEXITCODE -ne 0) { throw "Remote deployment failed (SSH exit $LASTEXITCODE). The database was not automatically rolled back." }
