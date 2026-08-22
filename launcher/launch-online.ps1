[CmdletBinding()]
param(
    [string]$GameDirectory = 'C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx',
    [string]$MaxHeap = '12g'
)

$ErrorActionPreference = 'Stop'
$agent = Join-Path $PSScriptRoot 'syx-kingdoms-online-launcher.jar'
$game = Join-Path $GameDirectory 'SyxWithout.exe'
if (-not (Test-Path -LiteralPath $agent -PathType Leaf)) {
    throw "The Syx Kingdoms Online launcher agent is missing: $agent"
}
if (-not (Test-Path -LiteralPath $game -PathType Leaf)) {
    throw "Songs of Syx was not found at $GameDirectory"
}

$escapedAgent = $agent.Replace('"', '\"')
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previousLegacyJavaOptions = $env:_JAVA_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "-Dsyxkingdoms.online=true -javaagent:`"$escapedAgent`""
    $env:_JAVA_OPTIONS = (($previousLegacyJavaOptions + " -Xmx$MaxHeap").Trim())
    Start-Process -FilePath $game -WorkingDirectory $GameDirectory
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    $env:_JAVA_OPTIONS = $previousLegacyJavaOptions
}
