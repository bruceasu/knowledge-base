$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
$tempRoot = $null
try {
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE." }
    $jars = @(Get-ChildItem (Join-Path $repoRoot "target") -File -Filter "knowledge-base-*.jar" |
        Where-Object { $_.Name -notlike "original-*" })
    if ($jars.Count -ne 1) { throw "Expected one application JAR under target/, found $($jars.Count)." }

    $jdkBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" } else { $null }
    $jdepsCommand = if ($jdkBin -and (Test-Path (Join-Path $jdkBin "jdeps.exe"))) { Join-Path $jdkBin "jdeps.exe" } else { (Get-Command jdeps -ErrorAction Stop).Source }
    $jlinkCommand = if ($jdkBin -and (Test-Path (Join-Path $jdkBin "jlink.exe"))) { Join-Path $jdkBin "jlink.exe" } else { (Get-Command jlink -ErrorAction Stop).Source }
    $moduleOutput = & $jdepsCommand --ignore-missing-deps --multi-release 17 --print-module-deps $jars[0].FullName
    if ($LASTEXITCODE -ne 0 -or -not $moduleOutput) { throw "jdeps could not determine the runtime modules." }
    $modules = @($moduleOutput.Trim() -split "," | Where-Object { $_ })
    if ($modules -notcontains "jdk.crypto.ec") { $modules += "jdk.crypto.ec" }

    $artifact = [IO.Path]::GetFileNameWithoutExtension($jars[0].Name)
    $platform = "$($env:PROCESSOR_ARCHITECTURE.ToLowerInvariant())-windows"
    $packageName = "${artifact}-with-jre-${platform}"
    $outputDir = Join-Path $repoRoot "target\distributions"
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("knowledge-base-package-" + [guid]::NewGuid().ToString("N"))
    $packageDir = Join-Path $tempRoot $packageName
    New-Item -ItemType Directory -Path (Join-Path $packageDir "scripts"), (Join-Path $packageDir "docs\handoffs"), (Join-Path $packageDir "deploy") -Force | Out-Null
    Copy-Item $jars[0].FullName (Join-Path $packageDir "knowledge-base.jar")
    Copy-Item README.md, README.zh.md, config-example.toml, env.example $packageDir
    Copy-Item docs/user-guide.zh.md, docs/single-workspace-service.md, docs/article-editing.md, docs/introduction-faq.zh.md, docs/personal-knowledge.md (Join-Path $packageDir "docs")
    Copy-Item docs/handoffs/personal-secretary-knowledge-handoff.md (Join-Path $packageDir "docs\handoffs")
    Copy-Item deploy/personal (Join-Path $packageDir "deploy") -Recurse
    Copy-Item scripts/start-jar.sh, scripts/start-jar.ps1 (Join-Path $packageDir "scripts")

    $runtimePath = Join-Path $packageDir "jre"
    & $jlinkCommand --add-modules ($modules -join ",") --bind-services --strip-debug --no-man-pages --no-header-files --compress=2 --output $runtimePath
    if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE." }
    $archive = Join-Path $outputDir "$packageName.zip"
    if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
    Compress-Archive -Path $packageDir -DestinationPath $archive -CompressionLevel Optimal
    Write-Host "Created $archive"
}
finally {
    if ($tempRoot -and (Test-Path -LiteralPath $tempRoot)) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
    Pop-Location
}
