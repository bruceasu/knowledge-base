$ErrorActionPreference = "Stop"
$packageRoot = Split-Path -Parent $PSScriptRoot

if ($env:KB_JAR) {
    $jarPath = $env:KB_JAR
} elseif (Test-Path (Join-Path $packageRoot "knowledge-base.jar")) {
    $jarPath = Join-Path $packageRoot "knowledge-base.jar"
} else {
    $jars = @(Get-ChildItem (Join-Path $packageRoot "target") -File -Filter "knowledge-base-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" })
    if ($jars.Count -ne 1) { throw "Expected one application JAR. Set KB_JAR to choose a JAR explicitly." }
    $jarPath = $jars[0].FullName
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Application JAR not found: $jarPath" }

$bundledJava = Join-Path $packageRoot "jre\bin\java.exe"
if (Test-Path -LiteralPath $bundledJava -PathType Leaf) {
    $java = $bundledJava
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $java = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCommand) { throw "Java 17+ is required. Install a JRE or set JAVA_HOME." }
    $java = $javaCommand.Source
}
& $java -jar $jarPath @args
exit $LASTEXITCODE
