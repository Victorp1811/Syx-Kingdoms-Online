[CmdletBinding()]
param(
    [string]$GameJar = $env:SONGS_OF_SYX_JAR,
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$build = Join-Path $root 'build'

if ([string]::IsNullOrWhiteSpace($GameJar)) {
    $GameJar = 'C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx\SongsOfSyx.jar'
}
if (-not (Test-Path -LiteralPath $GameJar -PathType Leaf)) {
    throw 'SongsOfSyx.jar was not found. Set SONGS_OF_SYX_JAR or pass -GameJar.'
}
$GameJar = (Resolve-Path -LiteralPath $GameJar).Path

$javac = (Get-Command javac -ErrorAction Stop).Source
$jarCandidate = Join-Path (Split-Path -Parent $javac) 'jar.exe'
$jar = if (Test-Path -LiteralPath $jarCandidate) { $jarCandidate } else { (Get-Command jar -ErrorAction Stop).Source }

if (Test-Path -LiteralPath $build) {
    $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
    if (-not $resolvedBuild.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected build path: $resolvedBuild"
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}

$serverClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\server') -Force
$modClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\mod') -Force
$testClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\test') -Force
$modTestClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\mod-test') -Force
$launcherClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\launcher') -Force
$launcherPatchClasses = New-Item -ItemType Directory -Path (Join-Path $build 'classes\launcher-patch') -Force

$commonSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\common\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$serverSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\server\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$modSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\mod\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$modTestSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\mod-test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$launcherSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\launcher\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$launcherPatchSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src\launcher-patch\java') -Recurse -Filter '*.java' | ForEach-Object FullName)

& $javac --release 21 -encoding UTF-8 -d $serverClasses.FullName @commonSources @serverSources
if ($LASTEXITCODE -ne 0) { throw "Server compilation failed with exit code $LASTEXITCODE." }

& $javac --release 21 -encoding UTF-8 -cp $GameJar -d $modClasses.FullName @commonSources @modSources
if ($LASTEXITCODE -ne 0) { throw "Mod compilation failed with exit code $LASTEXITCODE." }

& $javac --release 21 -encoding UTF-8 -d $testClasses.FullName @commonSources @serverSources @testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed with exit code $LASTEXITCODE." }

& $javac --release 21 -encoding UTF-8 -cp $GameJar -d $modTestClasses.FullName @commonSources @modSources @modTestSources
if ($LASTEXITCODE -ne 0) { throw "Mod smoke-test compilation failed with exit code $LASTEXITCODE." }

& $javac --release 21 -encoding UTF-8 -d $launcherClasses.FullName @launcherSources
if ($LASTEXITCODE -ne 0) { throw "Online launcher compilation failed with exit code $LASTEXITCODE." }

$launcherTestClasspath = $launcherClasses.FullName + [IO.Path]::PathSeparator + $GameJar
& java -cp $launcherTestClasspath 'syxkingdoms.launcher.AgentSmoke' $GameJar
if ($LASTEXITCODE -ne 0) { throw 'Online engine-agent smoke test failed.' }

& $javac --release 21 -encoding UTF-8 -cp $GameJar -d $launcherPatchClasses.FullName @launcherPatchSources
if ($LASTEXITCODE -ne 0) { throw "Online menu patch compilation failed with exit code $LASTEXITCODE." }

$canonicalHeightmap = Join-Path $root 'mod\V71\assets\sprite\world\generatorMaps\Syx_Kingdoms_Online.png'
if (-not (Test-Path -LiteralPath $canonicalHeightmap -PathType Leaf)) {
    throw "Canonical online heightmap is missing: $canonicalHeightmap"
}
foreach ($classRoot in @($serverClasses, $modClasses, $testClasses, $modTestClasses)) {
    $heightmapResources = New-Item -ItemType Directory `
        -Path (Join-Path $classRoot.FullName 'syxkingdoms\world') -Force
    Copy-Item -LiteralPath $canonicalHeightmap `
        -Destination (Join-Path $heightmapResources.FullName 'Syx_Kingdoms_Online.png') -Force
}

$testClasspath = $testClasses.FullName
foreach ($test in @(
    'syxkingdoms.protocol.ProtocolTest',
    'syxkingdoms.server.WorldPersistenceTest',
    'syxkingdoms.server.StrategicSystemsTest',
    'syxkingdoms.server.WorldSoakTest',
    'syxkingdoms.server.ServerIntegrationTest'
)) {
    & java -cp $testClasspath $test
    if ($LASTEXITCODE -ne 0) { throw "$test failed with exit code $LASTEXITCODE." }
}
& java -cp $testClasspath 'syxkingdoms.server.ServerConfigTest' (Join-Path $root 'server\server.properties')
if ($LASTEXITCODE -ne 0) { throw 'ServerConfigTest failed.' }

$modTestClasspath = $modTestClasses.FullName + [IO.Path]::PathSeparator + $GameJar
& java -cp $modTestClasspath 'syxkingdoms.mod.ModLoadSmokeTest'
if ($LASTEXITCODE -ne 0) { throw 'ModLoadSmokeTest failed.' }
& java -cp $modTestClasspath 'syxkingdoms.mod.TradeLedgerTest'
if ($LASTEXITCODE -ne 0) { throw 'TradeLedgerTest failed.' }
& java -cp $modTestClasspath 'syxkingdoms.mod.CrestEditorModelTest'
if ($LASTEXITCODE -ne 0) { throw 'CrestEditorModelTest failed.' }

$wrongVersions = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath (Join-Path $build 'classes') -Recurse -Filter '*.class' | ForEach-Object {
    $stream = [IO.File]::OpenRead($_.FullName)
    try {
        $header = New-Object byte[] 8
        if ($stream.Read($header, 0, 8) -ne 8) { throw "Invalid class file: $($_.FullName)" }
        $major = ([int]$header[6] -shl 8) -bor [int]$header[7]
        if ($major -ne 65) { $wrongVersions.Add("$($_.FullName) -> $major") }
    } finally {
        $stream.Dispose()
    }
}
if ($wrongVersions.Count -gt 0) {
    throw "Classes were not compiled for Java 21:`n$($wrongVersions -join [Environment]::NewLine)"
}

if ($SkipPackage) {
    Write-Host 'Compilation and tests passed. Packaging skipped.'
    return
}

$modScript = New-Item -ItemType Directory -Path (Join-Path $build 'dist\Syx Kingdoms Online\V71\script') -Force
$serverDist = New-Item -ItemType Directory -Path (Join-Path $build 'dist\server') -Force
$launcherDist = New-Item -ItemType Directory -Path (Join-Path $build 'dist\launcher') -Force
& $jar --create --file (Join-Path $modScript.FullName 'syx-kingdoms-online.jar') -C $modClasses.FullName .
if ($LASTEXITCODE -ne 0) { throw 'Mod JAR creation failed.' }
& $jar --create --file (Join-Path $serverDist.FullName 'syx-kingdoms-server.jar') --main-class syxkingdoms.server.ServerMain -C $serverClasses.FullName .
if ($LASTEXITCODE -ne 0) { throw 'Server JAR creation failed.' }
& $jar --create --file (Join-Path $launcherDist.FullName 'syx-kingdoms-online-launcher.jar') `
    --manifest (Join-Path $root 'launcher\MANIFEST.MF') `
    -C $launcherClasses.FullName . -C $launcherPatchClasses.FullName .
if ($LASTEXITCODE -ne 0) { throw 'Online launcher JAR creation failed.' }
Copy-Item -LiteralPath (Join-Path $root 'launcher\launch-online.ps1') -Destination $launcherDist.FullName
$worldTemplate = Join-Path $root 'world\syx-kingdoms-native-world-1000-v1.save'
if (Test-Path -LiteralPath $worldTemplate -PathType Leaf) {
    $launcherWorld = New-Item -ItemType Directory -Path (Join-Path $launcherDist.FullName 'world') -Force
    Copy-Item -LiteralPath $worldTemplate -Destination $launcherWorld.FullName -Force
}

$modRoot = Split-Path -Parent (Split-Path -Parent $modScript.FullName)
Copy-Item -LiteralPath (Join-Path $root 'mod\_Info.txt') -Destination $modRoot
Copy-Item -LiteralPath (Join-Path $root 'mod\config.txt') -Destination $modRoot
$modAssets = Join-Path $root 'mod\V71\assets'
if (Test-Path -LiteralPath $modAssets -PathType Container) {
    Copy-Item -LiteralPath $modAssets -Destination (Join-Path $modRoot 'V71') -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $root 'server\server.properties') -Destination $serverDist.FullName

$modJar = Join-Path $modScript.FullName 'syx-kingdoms-online.jar'
$serverJar = Join-Path $serverDist.FullName 'syx-kingdoms-server.jar'
$launcherJar = Join-Path $launcherDist.FullName 'syx-kingdoms-online-launcher.jar'
Write-Host "Built mod: $modJar"
Write-Host "Mod SHA256: $((Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash)"
Write-Host "Built server: $serverJar"
Write-Host "Server SHA256: $((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash)"
Write-Host "Built online launcher: $launcherJar"
Write-Host "Launcher SHA256: $((Get-FileHash -LiteralPath $launcherJar -Algorithm SHA256).Hash)"
