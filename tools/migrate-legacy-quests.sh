#!/usr/bin/env bash
# Migrate FTB Quests data authored by the OLD FMTT2-embedded quest tools to GabrieleQuests.
#
# It rewrites the display-multiblock / display-mannequin icon prefixes in the quest files:
#   fmtt2-multiblock:  ->  gabrielequests-multiblock:
#   fmtt2-mannequin:   ->  gabrielequests-mannequin:
# (Display items and quest shapes are native FTB data and are left untouched.)
#
# GabrieleQuests also reads the old prefixes at runtime and self-migrates on the next save,
# so this script is OPTIONAL - use it to convert a whole pack at once without opening every
# chapter in-game. Every changed file is backed up as <file>.bak.
#
# Usage:
#   ./migrate-legacy-quests.sh [path-to-ftbquests-config]
# Default path: ./config/ftbquests  (run it from the pack/instance root, or pass the path).
# Examples:
#   ./migrate-legacy-quests.sh
#   ./migrate-legacy-quests.sh "C:/.../instance/config/ftbquests"

set -euo pipefail

DIR="${1:-config/ftbquests}"
if [ ! -d "$DIR" ]; then
  echo "ERROR: directory not found: $DIR" >&2
  echo "Pass the path to your ftbquests config folder, e.g.:" >&2
  echo "  $0 \"C:/Users/you/.../config/ftbquests\"" >&2
  exit 1
fi

changed=0
scanned=0
# .snbt is the FTB Quests data format; also handle .nbt just in case.
while IFS= read -r -d '' f; do
  scanned=$((scanned + 1))
  if grep -q -e 'fmtt2-multiblock:' -e 'fmtt2-mannequin:' "$f"; then
    cp -f "$f" "$f.bak"
    sed -i \
      -e 's/fmtt2-multiblock:/gabrielequests-multiblock:/g' \
      -e 's/fmtt2-mannequin:/gabrielequests-mannequin:/g' \
      "$f"
    n=$(grep -c -e 'gabrielequests-multiblock:' -e 'gabrielequests-mannequin:' "$f" || true)
    echo "  migrated: $f  (backup: $f.bak)"
    changed=$((changed + 1))
  fi
done < <(find "$DIR" -type f \( -name '*.snbt' -o -name '*.nbt' \) -print0)

echo ""
echo "Scanned $scanned file(s); migrated $changed file(s) under: $DIR"
if [ "$changed" -eq 0 ]; then
  echo "Nothing to migrate (no legacy fmtt2- display icons found)."
fi
