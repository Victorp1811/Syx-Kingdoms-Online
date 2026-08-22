[CmdletBinding()]
param(
    [string]$ModsDirectory = (Join-Path $env:APPDATA 'songsofsyx\mods'),
    [switch]$ResetConfig
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $root 'build\dist\Syx Kingdoms Online'
if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $ModsDirectory -Force | Out-Null
}
$resolvedMods = (Resolve-Path -LiteralPath $ModsDirectory).Path.TrimEnd('\')
$target = Join-Path $resolvedMods 'Syx Kingdoms Online'
$targetParent = Split-Path -Parent $target
if (-not $targetParent.Equals($resolvedMods, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unexpected mod target: $target"
}

$targetScript = Join-Path $target 'V71\script'
New-Item -ItemType Directory -Path $targetScript -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $source '_Info.txt') -Destination (Join-Path $target '_Info.txt') -Force
Copy-Item -LiteralPath (Join-Path $source 'V71\script\syx-kingdoms-online.jar') `
    -Destination (Join-Path $targetScript 'syx-kingdoms-online.jar') -Force
$sourceAssets = Join-Path $source 'V71\assets'
if (Test-Path -LiteralPath $sourceAssets -PathType Container) {
    $targetVersion = Join-Path $target 'V71'
    New-Item -ItemType Directory -Path $targetVersion -Force | Out-Null
    Copy-Item -LiteralPath $sourceAssets -Destination $targetVersion -Recurse -Force
}
$targetConfig = Join-Path $target 'config.txt'
$preservedConfig = -not $ResetConfig -and (Test-Path -LiteralPath $targetConfig)
if (-not $preservedConfig) {
    Copy-Item -LiteralPath (Join-Path $source 'config.txt') -Destination $targetConfig -Force
} else {
    Write-Host 'Existing client config will remain byte-for-byte unchanged.'
}

$launcherSource = Join-Path $root 'build\dist\launcher'
if (Test-Path -LiteralPath $launcherSource -PathType Container) {
    $appRoot = Split-Path -Parent $resolvedMods
    $launcherTarget = Join-Path $appRoot 'syx-kingdoms\launcher'
    New-Item -ItemType Directory -Path $launcherTarget -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $launcherSource 'syx-kingdoms-online-launcher.jar') `
        -Destination $launcherTarget -Force
    Copy-Item -LiteralPath (Join-Path $launcherSource 'launch-online.ps1') `
        -Destination $launcherTarget -Force
    $launcherWorldSource = Join-Path $launcherSource 'world'
    if (Test-Path -LiteralPath $launcherWorldSource -PathType Container) {
        Copy-Item -LiteralPath $launcherWorldSource -Destination $launcherTarget -Recurse -Force
    }
    Write-Host "Installed online launcher: $launcherTarget"
}

Write-Host "Installed: $target"
Write-Host 'Enable Syx Kingdoms Online in the Songs of Syx launcher before starting the game.'
if ($preservedConfig) {
    Write-Host 'Existing client config was preserved.'
}
