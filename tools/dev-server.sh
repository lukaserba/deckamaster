#!/usr/bin/env bash
# Rebuilds the server and (re)starts it in the background for local development.
# Usage: tools/dev-server.sh <forge.jar> <forge res dir> [port]
set -euo pipefail
FORGE_JAR="${1:?forge jar}"
RES_DIR="${2:?forge-gui/res directory}"
PORT="${3:-8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/.run"
"$ROOT/server/build.sh" "$FORGE_JAR"
mkdir -p "$RUN" && ln -sfn "$RES_DIR" "$RUN/res"
[ -f "$RUN/server.pid" ] && kill "$(cat "$RUN/server.pid")" 2>/dev/null && sleep 1 || true
cd "$RUN"
nohup java -Xmx3g -cp "$FORGE_JAR:$ROOT/server/target/deckamaster-server.jar" \
  cc.deckamaster.server.Main "$PORT" "$ROOT/web" "$ROOT/decks" > server.log 2>&1 &
echo $! > server.pid
for _ in $(seq 1 60); do grep -q "listening" server.log 2>/dev/null && break; sleep 1; done
grep -E "ready|Decks|listening" server.log
