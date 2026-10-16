#!/usr/bin/env bash
# Copies only the parts of Forge's res/ folder the game server needs.
# Usage: tools/slim-res.sh <forge-gui/res> <output dir>
# Leaves out: Adventure mode, Quest/Conquest campaigns, music/sound, skins,
# puzzles, tutorials, genetic AI decks and non-English card name translations.
set -euo pipefail
SRC="${1:?forge-gui/res}"
OUT="${2:?output dir}"
rm -rf "$OUT" && mkdir -p "$OUT"
for entry in "$SRC"/*; do
  name="$(basename "$entry")"
  case "$name" in
    adventure|music|sound|quest|conquest|puzzle|tutorial|geneticaidecks|skins) continue ;;
    languages)
      mkdir -p "$OUT/languages"
      cp "$entry"/en-US* "$OUT/languages/" ;;
    *) cp -R "$entry" "$OUT/" ;;
  esac
done
du -sh "$OUT"
