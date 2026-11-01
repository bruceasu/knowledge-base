#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
command -v mvn >/dev/null 2>&1 || { echo "Maven 3.9+ is required on PATH." >&2; exit 127; }
mvn package

jar_path=""
for candidate in target/knowledge-base-*.jar; do
  [[ -f "$candidate" ]] || continue
  [[ "$(basename "$candidate")" != original-* ]] || continue
  if [[ -n "$jar_path" ]]; then echo "More than one application JAR was found under target/." >&2; exit 2; fi
  jar_path="$candidate"
done
[[ -n "$jar_path" ]] || { echo "Application JAR was not produced by Maven." >&2; exit 2; }
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  jar_tool="$JAVA_HOME/bin/jar"
else
  jar_tool="$(command -v jar || true)"
fi
[[ -n "$jar_tool" ]] || { echo "A JDK 17+ with the jar tool is required." >&2; exit 127; }

artifact="$(basename "$jar_path" .jar)"
package_name="$artifact-without-jre"
output_dir="$repo_root/target/distributions"
mkdir -p "$output_dir"
temp_root="$(mktemp -d "${TMPDIR:-/tmp}/knowledge-base-package.XXXXXX")"
trap 'rm -rf "$temp_root"' EXIT
package_dir="$temp_root/$package_name"
mkdir -p "$package_dir/scripts" "$package_dir/docs/handoffs" "$package_dir/deploy"
cp "$jar_path" "$package_dir/knowledge-base.jar"
cp README.md README.zh.md config-example.toml env.example "$package_dir/"
cp docs/user-guide.zh.md docs/single-workspace-service.md docs/article-editing.md docs/introduction-faq.zh.md docs/personal-knowledge.md "$package_dir/docs/"
cp docs/handoffs/personal-secretary-knowledge-handoff.md "$package_dir/docs/handoffs/"
cp -R deploy/personal "$package_dir/deploy/"
cp scripts/start-jar.sh scripts/start-jar.ps1 "$package_dir/scripts/"

archive="$output_dir/$package_name.zip"
rm -f "$archive"
"$jar_tool" --create --file "$archive" -C "$temp_root" "$package_name"
printf 'Created %s\n' "$archive"
