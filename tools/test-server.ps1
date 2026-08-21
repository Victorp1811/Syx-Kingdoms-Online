[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$classes = Join-Path $root 'build\ci-server-tests'

if (Test-Path -LiteralPath $classes) {
    $resolved = (Resolve-Path -LiteralPath $classes).Path
    if (-not $resolved.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected CI test path: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$sources = @(
    Get-ChildItem -LiteralPath (Join-Path $root 'src\common\java') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $root 'src\server\java') -Recurse -Filter '*.java'
    Get-ChildItem -LiteralPath (Join-Path $root 'src\test\java') -Recurse -Filter '*.java'
) | ForEach-Object FullName

$javac = (Get-Command javac -ErrorAction Stop).Source
& $javac --release 21 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Server test compilation failed with exit code $LASTEXITCODE." }

foreach ($test in @(
    'syxkingdoms.protocol.ProtocolTest',
    'syxkingdoms.server.WorldPersistenceTest',
    'syxkingdoms.server.StrategicSystemsTest',
    'syxkingdoms.server.WorldSoakTest',
    'syxkingdoms.server.ServerIntegrationTest'
)) {
    & java -cp $classes $test
    if ($LASTEXITCODE -ne 0) { throw "$test failed with exit code $LASTEXITCODE." }
}
& java -cp $classes 'syxkingdoms.server.ServerConfigTest' (Join-Path $root 'server\server.properties')
if ($LASTEXITCODE -ne 0) { throw 'ServerConfigTest failed.' }

Write-Host 'Server CI suite: PASS'
