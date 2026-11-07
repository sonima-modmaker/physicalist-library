$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $classFile = Get-ChildItem 'build/neoForm' -Recurse -Filter 'classpath.txt' | Where-Object { $_.FullName -match 'writeMinecraftClasspathGameTestServer' } | Select-Object -First 1
    if (-not $classFile) { throw 'Run the default GameTest server first to generate its classpath.' }
    $minecraftJar = Join-Path $projectRoot '.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.219/neoforge-21.1.219.jar'
    if (-not (Test-Path -LiteralPath $minecraftJar)) { throw 'Build with default NeoForge 21.1.219 first.' }
    $output = Join-Path $PSScriptRoot 'bin'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $classpath = ((Get-Content -LiteralPath $classFile.FullName) + @($minecraftJar, (Join-Path $projectRoot 'build/classes/java/main'), $output)) -join [IO.Path]::PathSeparator
    $files = @('LibraryPhysicsTest.java', 'CollisionParityTest.java', 'SpatialIndexBenchmark.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    & javac --release 21 -cp $classpath -d $output @files
    if ($LASTEXITCODE -ne 0) { throw 'Verification compilation failed.' }
    foreach ($test in @('LibraryPhysicsTest', 'CollisionParityTest', 'SpatialIndexBenchmark')) {
        & java -cp $classpath $test
        if ($LASTEXITCODE -ne 0) { throw "Verification failed: $test" }
    }
} finally { Pop-Location }
