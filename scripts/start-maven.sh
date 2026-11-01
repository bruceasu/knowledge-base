#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

command -v mvn >/dev/null 2>&1 || { echo "Maven 3.9+ is required on PATH." >&2; exit 127; }
exec mvn compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=me.asu.kb.http.SingleWorkspaceHttpServerMain
