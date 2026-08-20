[CmdletBinding()]
param(
    [string]$WorldFile = (Join-Path $PSScriptRoot '..\server-data\syx-kingdoms-world-v1.properties'),
    [string]$DestinationDirectory = (Join-Path $PSScriptRoot '..\backups')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$serverConfig = Join-Path $root 'server\server.properties'
$serverJar = Join-Path $root 'build\dist\server\syx-kingdoms-server.jar'
$moderationFile = Join-Path (Split-Path -Parent $WorldFile) 'moderation.properties'

if (-not (Test-Path -LiteralPath $WorldFile -PathType Leaf)) {
    throw "Persistent world file was not found: $WorldFile"
}
if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
$resolvedDestination = (Resolve-Path -LiteralPath $DestinationDirectory).Path
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$archive = Join-Path $resolvedDestination "syx-kingdoms-$timestamp.zip"
$backupItems = @($WorldFile, $serverConfig, $serverJar)
if (Test-Path -LiteralPath $moderationFile -PathType Leaf) {
    $backupItems += $moderationFile
}
Compress-Archive -LiteralPath $backupItems -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
Set-Content -LiteralPath "$archive.sha256" -Value "$hash  $([IO.Path]::GetFileName($archive))" -Encoding ASCII

Write-Host "Backup: $archive"
Write-Host "SHA256: $hash"
