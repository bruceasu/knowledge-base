$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw "Maven 3.9+ is required on PATH."
    }
    & mvn compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
        "-Dexec.mainClass=me.asu.kb.http.SingleWorkspaceHttpServerMain"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
