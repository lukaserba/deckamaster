#!/usr/bin/env bash
# Deckamaster launcher for macOS (double-click) and Linux (./run.command).
cd "$(dirname "$0")"
PORT="${PORT:-8080}"

if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'; then
  echo "Deckamaster needs Java 17 or newer."
  echo "Download it from https://adoptium.net (Temurin, macOS .pkg), install it, then run this again."
  read -r -p "Press Enter to close." _
  exit 1
fi

echo "Starting Deckamaster on http://localhost:$PORT (loading ~34,000 cards takes a few seconds)…"
java -Xmx2g -cp "forge.jar:deckamaster-server.jar" cc.deckamaster.server.Main "$PORT" web decks &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT INT TERM

for _ in $(seq 1 120); do
  if curl -s -o /dev/null "http://localhost:$PORT/"; then break; fi
  sleep 1
done
if command -v open >/dev/null 2>&1; then open "http://localhost:$PORT/"; elif command -v xdg-open >/dev/null 2>&1; then xdg-open "http://localhost:$PORT/"; fi
echo "Deckamaster is running. Close this window (or press Ctrl+C) to stop it."
wait $SERVER
