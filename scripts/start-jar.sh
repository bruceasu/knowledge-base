#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_root="$(cd "$script_dir/.." && pwd)"

if [[ -n "${KB_JAR:-}" ]]; then
  jar_path="$KB_JAR"
elif [[ -f "$package_root/knowledge-base.jar" ]]; then
  jar_path="$package_root/knowledge-base.jar"
else
  jar_path=""
  for candidate in "$package_root"/target/knowledge-base-*.jar; do
    [[ -f "$candidate" ]] || continue
    [[ "$(basename "$candidate")" != original-* ]] || continue
    if [[ -n "$jar_path" ]]; then
      echo "More than one application JAR was found under target/. Set KB_JAR to choose one." >&2
      exit 2
    fi
    jar_path="$candidate"
  done
fi
[[ -f "$jar_path" ]] || { echo "Application JAR not found. Set KB_JAR or place knowledge-base.jar beside this script's parent directory." >&2; exit 2; }

if [[ -x "$package_root/jre/bin/java" ]]; then
  java_path="$package_root/jre/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_path="$JAVA_HOME/bin/java"
else
  java_path="$(command -v java || true)"
fi
[[ -n "$java_path" ]] || { echo "Java 17+ is required. Install a JRE or set JAVA_HOME." >&2; exit 127; }

exec "$java_path" -jar "$jar_path" "$@"
