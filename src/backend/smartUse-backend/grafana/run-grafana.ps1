# Local admin Grafana (REQ-ADM-03), no Docker, no install: downloads the
# portable Grafana OSS zip into grafana/.bin on first run and starts it
# against this folder's provisioning + dashboards. Talks to Aurora directly
# over Postgres as the read-only grafana_ro role; Aurora's security group
# only lets in the IPs allowlisted by hand in its console inbound rules.
#
#   1. bash grafana/setup-db-access.sh   (once; writes grafana/.env)
#   2. powershell -ExecutionPolicy Bypass -File grafana/run-grafana.ps1
#   3. open http://localhost:3000        (Ctrl+C to stop)
$ErrorActionPreference = 'Stop'

$Version = '11.6.0'
$Here = $PSScriptRoot
$Bin = Join-Path $Here '.bin'
$Home_ = Join-Path $Bin "grafana-v$Version"

$EnvFile = Join-Path $Here '.env'
if (-not (Test-Path $EnvFile)) {
    throw "Missing grafana/.env -- run 'bash grafana/setup-db-access.sh' first."
}
foreach ($line in Get-Content $EnvFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
    }
}

if (-not (Test-Path (Join-Path $Home_ 'bin\grafana.exe'))) {
    New-Item -ItemType Directory -Force $Bin | Out-Null
    $Zip = Join-Path $Bin "grafana-$Version.zip"
    Write-Host "Downloading Grafana $Version (one-time)..."
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest "https://dl.grafana.com/oss/release/grafana-$Version.windows-amd64.zip" -OutFile $Zip
    Expand-Archive $Zip -DestinationPath $Bin -Force
    Remove-Item $Zip
}

$Data = Join-Path $Here '.data'
$env:SMARTUSE_DASHBOARDS_DIR = (Join-Path $Here 'dashboards') -replace '\\', '/'
$env:GF_PATHS_PROVISIONING = Join-Path $Here 'provisioning'
$env:GF_PATHS_DATA = $Data
$env:GF_PATHS_LOGS = Join-Path $Data 'log'
$env:GF_PATHS_PLUGINS = Join-Path $Data 'plugins'
# Loopback only: the datasource holds DB credentials, so this Grafana should
# never be reachable from the LAN. That's also why anonymous read-only access
# is fine here -- no login screen, just the dashboard.
$env:GF_SERVER_HTTP_ADDR = '127.0.0.1'
$env:GF_SERVER_HTTP_PORT = '3000'
$env:GF_AUTH_ANONYMOUS_ENABLED = 'true'
$env:GF_AUTH_ANONYMOUS_ORG_ROLE = 'Viewer'
$env:GF_ANALYTICS_REPORTING_ENABLED = 'false'
$env:GF_ANALYTICS_CHECK_FOR_UPDATES = 'false'
$env:GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH = Join-Path $Here 'dashboards\sync-status.json'

Write-Host "Grafana on http://localhost:3000  (Ctrl+C to stop)"
& (Join-Path $Home_ 'bin\grafana.exe') server --homepath $Home_
