#!/usr/bin/env bash
# Copy FallGuard watch recordings to this computer.
# Usage: tools/pull_recordings.sh [device-serial] [destination-dir]
# Device serial is optional when only one device is connected (see `adb devices`).
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${1:-}"
DEST="${2:-recordings}"
# APP: the watch app's ID. Set APP=com.example.activitycollector to pull from the
# older build that used the original app's ID.
APP="${APP:-com.shreyshrivastava.fallguard}"
SRC="/sdcard/Android/data/$APP/files/recordings"
args=()
[ -n "$SERIAL" ] && args=(-s "$SERIAL")
mkdir -p "$DEST"
files=$("$ADB" "${args[@]}" shell ls "$SRC" 2>/dev/null | tr -d '\r' | grep '\.csv$' || true)
if [ -z "$files" ]; then
  echo "No recordings found on the watch."
  exit 0
fi
for f in $files; do
  if [ -f "$DEST/$f" ]; then
    echo "skip   $f (already here)"
  else
    "$ADB" "${args[@]}" pull "$SRC/$f" "$DEST/$f" >/dev/null && echo "pulled $f"
  fi
done
