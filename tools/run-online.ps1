[CmdletBinding()]
param(
    [string]$GameDirectory = 'C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx',
    [string]$ExportWorldDirectory = '',
    [string]$MaxHeap = '12g'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$agent = Join-Path $root 'build\dist\launcher\syx-kingdoms-online-launcher.jar'
if (-not (Test-Path -LiteralPath $agent -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}

$game = Join-Path $GameDirectory 'SyxWithout.exe'
if (-not (Test-Path -LiteralPath $game -PathType Leaf)) {
    throw "Songs of Syx was not found at $GameDirectory"
}

$escapedAgent = $agent.Replace('"', '\"')
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previousLegacyJavaOptions = $env:_JAVA_OPTIONS
try {
    $options = "-Dsyxkingdoms.online=true -javaagent:`"$escapedAgent`""
    if (-not [string]::IsNullOrWhiteSpace($ExportWorldDirectory)) {
        $resolvedExport = [IO.Path]::GetFullPath($ExportWorldDirectory)
        $options += " -Dsyxkingdoms.exportWorldDirectory=`"$($resolvedExport.Replace('"', '\"'))`""
    }
    $env:JAVA_TOOL_OPTIONS = $options
    $env:_JAVA_OPTIONS = (($previousLegacyJavaOptions + " -Xmx$MaxHeap").Trim())
    Start-Process -FilePath $game -WorkingDirectory $GameDirectory
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    $env:_JAVA_OPTIONS = $previousLegacyJavaOptions
}

Write-Host 'Songs of Syx started with the dedicated Syx Kingdoms Online menu entry.'
