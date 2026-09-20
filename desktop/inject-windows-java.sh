#!/usr/bin/env bash
# Copy java.exe / javaw.exe from a matching Temurin 21 Windows JRE into a
# jpackage app image that Compose/jlink built with --strip-native-commands.
set -euo pipefail

usage() {
  echo "Usage: $0 <windows-app-image-dir>"
  echo "  windows-app-image-dir must contain runtime/bin (jpackage layout)."
  exit 1
}

APP_DIR="${1:-}"
[[ -n "$APP_DIR" && -d "$APP_DIR/runtime/bin" ]] || usage

DEST_BIN="$APP_DIR/runtime/bin"
if [[ -f "$DEST_BIN/java.exe" && -f "$DEST_BIN/javaw.exe" ]]; then
  echo "Already present: $DEST_BIN/java.exe and javaw.exe"
  exit 0
fi

CACHE="${TEMURIN_WINDOWS_JRE_ZIP:-${TMPDIR:-/tmp}/temurin-win/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip}"
URL="${TEMURIN_WINDOWS_JRE_URL:-https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip}"
mkdir -p "$(dirname "$CACHE")"
if [[ ! -f "$CACHE" ]]; then
  echo "==> Downloading Temurin 21.0.12.1 Windows x64 JRE"
  curl -fL --retry 4 --retry-delay 4 -o "$CACHE" "$URL"
fi

EXTRACT="${CACHE%.zip}-extract"
mkdir -p "$EXTRACT"
if [[ ! -f "$EXTRACT/java.exe" ]]; then
  unzip -o -j "$CACHE" "*/bin/java.exe" "*/bin/javaw.exe" -d "$EXTRACT"
fi
[[ -f "$EXTRACT/java.exe" && -f "$EXTRACT/javaw.exe" ]] || {
  echo "Temurin zip did not contain java.exe / javaw.exe"
  exit 1
}

cp -f "$EXTRACT/java.exe" "$DEST_BIN/java.exe"
cp -f "$EXTRACT/javaw.exe" "$DEST_BIN/javaw.exe"
echo "Installed java.exe and javaw.exe into $DEST_BIN"
ls -l "$DEST_BIN/java.exe" "$DEST_BIN/javaw.exe"
