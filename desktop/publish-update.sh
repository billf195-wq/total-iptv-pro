#!/usr/bin/env bash
# Publish a desktop update shelf: createDistributable → tar.gz + version.json,
# optionally serve on :8767 for kitchen Ubuntu / LAN clients.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-21}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 not found at $JAVA_HOME"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

SHELF_DIR="${SHELF_DIR:-$HOME/TotalIptvPro-Desktop-shelf}"
PORT="${SHELF_PORT:-8767}"
VERSION_NAME="${VERSION_NAME:-}"
VERSION_CODE="${VERSION_CODE:-}"
TARBALL_NAME="TotalIptvPro-linux.tar.gz"
START_SERVER="${START_SERVER:-1}"

cd "$ROOT"

# Prefer AppVersion.kt / build.gradle.kts when not overridden
if [[ -z "$VERSION_NAME" ]]; then
  VERSION_NAME="$(grep -E 'VERSION_NAME' src/main/kotlin/com/totaliptv/pro/desktop/AppVersion.kt \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
if [[ -z "$VERSION_CODE" ]]; then
  VERSION_CODE="$(grep -E 'VERSION_CODE' src/main/kotlin/com/totaliptv/pro/desktop/AppVersion.kt \
    | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')"
fi
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "Could not detect VERSION_NAME / VERSION_CODE from AppVersion.kt"
  exit 1
fi

echo "==> Building createDistributable (version $VERSION_NAME / code $VERSION_CODE)"
./gradlew --no-daemon createDistributable

APP_DIR=""
for candidate in \
  "$ROOT/build/compose/binaries/main/app/TotalIptvPro" \
  "$ROOT/build/compose/binaries/main/app/total-iptv-pro" \
  "$ROOT/build/compose/binaries/main/main/app/TotalIptvPro"
do
  if [[ -d "$candidate" ]]; then
    APP_DIR="$candidate"
    break
  fi
done
if [[ -z "$APP_DIR" ]]; then
  APP_DIR="$(find "$ROOT/build/compose/binaries" -type d -name 'TotalIptvPro' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$APP_DIR" || ! -d "$APP_DIR" ]]; then
  echo "Could not find createDistributable app dir under build/compose/binaries"
  find "$ROOT/build/compose" -maxdepth 5 -type d 2>/dev/null | head -40 || true
  exit 1
fi

RUNTIME_BIN=""
if [[ -d "$APP_DIR/lib/runtime" ]]; then
  RUNTIME_BIN="$APP_DIR/lib/runtime/bin"
elif [[ -d "$APP_DIR/runtime" ]]; then
  RUNTIME_BIN="$APP_DIR/runtime/bin"
fi
if [[ -z "$RUNTIME_BIN" || ! -x "$RUNTIME_BIN/java" ]]; then
  echo "Bundled runtime is missing $RUNTIME_BIN/java (jlink stripped native commands)."
  echo "desktop/build.gradle.kts should keep java in the runtime; rebuild createDistributable."
  find "$APP_DIR" -maxdepth 4 -type d 2>/dev/null | head -40 || true
  exit 1
fi

echo "==> Packaging $APP_DIR → $TARBALL_NAME"
mkdir -p "$SHELF_DIR"
TMP_TAR="$SHELF_DIR/$TARBALL_NAME"
rm -f "$TMP_TAR"
# Tar the parent folder name (TotalIptvPro/) so extract keeps layout
PARENT="$(dirname "$APP_DIR")"
BASE="$(basename "$APP_DIR")"
tar -C "$PARENT" -czf "$TMP_TAR" "$BASE"

# Keep Linux `file` as primary. Optional fileWindows for Windows clients (place zip on shelf separately).
WINDOWS_ZIP_NAME="TotalIptvPro-windows.zip"
WINDOWS_FIELD=""
if [[ -f "$SHELF_DIR/$WINDOWS_ZIP_NAME" ]]; then
  WINDOWS_FIELD=",\"fileWindows\":\"${WINDOWS_ZIP_NAME}\""
fi
cat > "$SHELF_DIR/version.json" << JSON
{"versionCode":${VERSION_CODE},"versionName":"${VERSION_NAME}","file":"${TARBALL_NAME}"${WINDOWS_FIELD}}
JSON

cat > "$SHELF_DIR/index.html" << HTML
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Total IPTV Pro Desktop shelf</title></head>
<body>
<h1>Total IPTV Pro Desktop</h1>
<p>version.json + ${TARBALL_NAME}</p>
<ul>
  <li><a href="version.json">version.json</a></li>
  <li><a href="${TARBALL_NAME}">${TARBALL_NAME}</a></li>
</ul>
</body></html>
HTML

echo "==> Shelf ready at $SHELF_DIR"
echo "    $(cat "$SHELF_DIR/version.json")"
ls -lh "$SHELF_DIR/$TARBALL_NAME"

if [[ "$START_SERVER" == "1" ]]; then
  if ss -ltn 2>/dev/null | grep -q ":${PORT} "; then
    echo "==> Port $PORT already in use — not starting a new server"
    echo "    Existing listener:"
    ss -ltnp 2>/dev/null | grep ":${PORT} " || true
  else
    echo "==> Starting python3 http.server on $PORT (cwd=$SHELF_DIR)"
    cd "$SHELF_DIR"
    nohup python3 -m http.server "$PORT" >"$SHELF_DIR/http-server.log" 2>&1 &
    echo $! >"$SHELF_DIR/http-server.pid"
    echo "    PID $(cat "$SHELF_DIR/http-server.pid")"
    sleep 0.5
  fi
  echo "==> Smoke: curl http://127.0.0.1:${PORT}/version.json"
  curl -fsS "http://127.0.0.1:${PORT}/version.json" || true
  echo
fi

LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo
echo "Done."
echo "  Default shelf URL for kitchen PC: http://${LAN_IP:-192.168.4.39}:${PORT}/"
echo "  Local smoke: http://127.0.0.1:${PORT}/version.json"
echo "  After shipping code changes: bump AppVersion + build.gradle.kts, then re-run ./publish-update.sh"
