#!/usr/bin/env bash
# Compiles the Deckamaster server against a built Forge jar.
# Usage: server/build.sh <path-to-forge-jar-with-dependencies.jar>
set -euo pipefail
FORGE_JAR="${1:?path to Forge jar-with-dependencies}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/target"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
javac --release 17 -encoding UTF-8 -Xlint:-options -cp "$FORGE_JAR" -d "$OUT/classes" \
  $(find "$HERE/src/main/java" -name '*.java')
jar --create --file "$OUT/deckamaster-server.jar" -C "$OUT/classes" .
echo "Built $OUT/deckamaster-server.jar"
