[CmdletBinding()]
param(
    [string]$Config
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$Config = if ([string]::IsNullOrWhiteSpace($Config)) {
    Join-Path $root 'server\server.properties'
} else {
    $Config
}
$serverJar = Join-Path $root 'build\dist\server\syx-kingdoms-server.jar'

if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$java = (Get-Command java -ErrorAction Stop).Source
& $java -jar $serverJar --config $Config
exit $LASTEXITCODE
