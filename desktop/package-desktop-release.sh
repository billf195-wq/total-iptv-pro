#!/usr/bin/env bash
# Build GitHub-release desktop archives from the current tree:
#   TotalIptvPro-Desktop-<ver>-linux.tar.gz
#   TotalIptvPro-Desktop-<ver>-windows.zip
# Linux uses createDistributable + copied JDK java launcher.
# Windows reuses a prior jpackage app image (exe + Windows runtime) and
# swaps in this version's app JAR, then injects Temurin java.exe/javaw.exe.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT_DIR="${OUT_DIR:-$ROOT/../dist}"
WIN_BASE_ZIP="${WIN_BASE_ZIP:-}"

VERSION_NAME="$(grep -E 'VERSION_NAME' "$ROOT/src/main/kotlin/com/totaliptv/pro/desktop/AppVersion.kt" \
  | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
[[ -n "$VERSION_NAME" ]] || { echo "Could not read VERSION_NAME"; exit 1; }

LINUX_TAR="$OUT_DIR/TotalIptvPro-Desktop-${VERSION_NAME}-linux.tar.gz"
WIN_ZIP="$OUT_DIR/TotalIptvPro-Desktop-${VERSION_NAME}-windows.zip"

echo "==> Desktop $VERSION_NAME  JAVA_HOME=$JAVA_HOME"
mkdir -p "$OUT_DIR"
cd "$ROOT"

echo "==> ./gradlew createDistributable"
./gradlew --no-daemon createDistributable

APP_DIR=""
for candidate in \
  "$ROOT/build/compose/binaries/main/app/TotalIptvPro" \
  "$ROOT/build/compose/binaries/main/app/total-iptv-pro"
do
  if [[ -d "$candidate" ]]; then
    APP_DIR="$candidate"
    break
  fi
done
if [[ -z "$APP_DIR" ]]; then
  APP_DIR="$(find "$ROOT/build/compose/binaries" -type d -name 'TotalIptvPro' 2>/dev/null | head -1 || true)"
fi
[[ -n "$APP_DIR" && -d "$APP_DIR" ]] || { echo "createDistributable app dir missing"; exit 1; }

LINUX_JAVA="$APP_DIR/lib/runtime/bin/java"
[[ -x "$LINUX_JAVA" ]] || { echo "FAIL: missing $LINUX_JAVA"; find "$APP_DIR/lib/runtime" -maxdepth 3 | head; exit 1; }
echo "    Linux runtime java: $LINUX_JAVA"

echo "==> tar $LINUX_TAR"
rm -f "$LINUX_TAR"
tar -C "$(dirname "$APP_DIR")" -czf "$LINUX_TAR" "$(basename "$APP_DIR")"
if ! tar -tzf "$LINUX_TAR" | grep -E 'lib/runtime/bin/java$' >/dev/null; then
  echo "FAIL: linux tar missing */lib/runtime/bin/java"
  tar -tzf "$LINUX_TAR" | grep runtime | head
  exit 1
fi

APP_JAR="$(ls -1 "$APP_DIR/lib/app"/TotalIptvPro-Desktop-*.jar | head -1)"
[[ -f "$APP_JAR" ]] || { echo "App JAR missing under $APP_DIR/lib/app"; exit 1; }
echo "    App JAR: $(basename "$APP_JAR")"

if [[ -z "$WIN_BASE_ZIP" ]]; then
  if [[ -f /tmp/inspect-125/TotalIptvPro-Desktop-1.2.5-windows.zip ]]; then
    WIN_BASE_ZIP=/tmp/inspect-125/TotalIptvPro-Desktop-1.2.5-windows.zip
  else
    echo "==> Downloading previous Windows zip as jpackage shell"
    mkdir -p /tmp/win-base
    gh release download v1.2.5 --repo billf195-wq/total-iptv-pro \
      -p 'TotalIptvPro-Desktop-1.2.5-windows.zip' -D /tmp/win-base
    WIN_BASE_ZIP=/tmp/win-base/TotalIptvPro-Desktop-1.2.5-windows.zip
  fi
fi
[[ -f "$WIN_BASE_ZIP" ]] || { echo "Windows base zip missing: $WIN_BASE_ZIP"; exit 1; }

STAGE="${TMPDIR:-/tmp}/win-pkg-${VERSION_NAME}"
rm -rf "$STAGE"
mkdir -p "$STAGE"
python3 - "$WIN_BASE_ZIP" "$STAGE" "$APP_JAR" "$VERSION_NAME" "$WIN_ZIP" <<'PY'
import pathlib, re, shutil, sys, zipfile

base_zip, stage, app_jar, version, out_zip = sys.argv[1:6]
stage = pathlib.Path(stage)
app_jar = pathlib.Path(app_jar)

with zipfile.ZipFile(base_zip) as zf:
    for info in zf.infolist():
        name = info.filename.replace("\\", "/")
        dest = stage / name
        if name.endswith("/"):
            dest.mkdir(parents=True, exist_ok=True)
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, open(dest, "wb") as dst:
            shutil.copyfileobj(src, dst)

app_dir = stage / "app"
old_jars = list(app_dir.glob("TotalIptvPro-Desktop-*.jar"))
if not old_jars:
    raise SystemExit("No TotalIptvPro-Desktop-*.jar in Windows app image")
for old in old_jars:
    old.unlink()
shutil.copy2(app_jar, app_dir / app_jar.name)

cfg = app_dir / "TotalIptvPro.cfg"
text = cfg.read_text(encoding="utf-8", errors="replace")
text = re.sub(
    r"TotalIptvPro-Desktop-[0-9.]+-[0-9a-f]+\.jar",
    app_jar.name,
    text,
)
text = re.sub(
    r"java-options=-Djpackage\.app-version=[^\r\n]+",
    f"java-options=-Djpackage.app-version={version}",
    text,
)
cfg.write_text(text, encoding="utf-8")

xml = app_dir / ".jpackage.xml"
if xml.exists():
    xml_text = xml.read_text(encoding="utf-8", errors="replace")
    xml_text = re.sub(r"<app-version>[^<]+</app-version>", f"<app-version>{version}</app-version>", xml_text)
    xml.write_text(xml_text, encoding="utf-8")

bat = "@echo off\r\ncd /d \"%~dp0\"\r\nstart \"\" \"%~dp0TotalIptvPro.exe\"\r\n"
(stage / "TotalIptvPro.bat").write_text(bat, encoding="ascii")
(stage / f"Total IPTV Pro {version}.bat").write_text(bat, encoding="ascii")
for leftover in stage.glob("Total IPTV Pro *.bat"):
    if leftover.name != f"Total IPTV Pro {version}.bat":
        leftover.unlink()

print(f"Staged Windows image at {stage}")
PY

bash "$ROOT/inject-windows-java.sh" "$STAGE"
[[ -f "$STAGE/runtime/bin/java.exe" && -f "$STAGE/runtime/bin/javaw.exe" ]] || {
  echo "FAIL: java.exe/javaw.exe missing after inject"
  ls -la "$STAGE/runtime/bin" | head
  exit 1
}

echo "==> zip $WIN_ZIP"
rm -f "$WIN_ZIP"
python3 - "$STAGE" "$WIN_ZIP" <<'PY'
import pathlib, sys, zipfile
stage, out_zip = pathlib.Path(sys.argv[1]), sys.argv[2]
with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    for path in sorted(stage.rglob("*")):
        if path.is_dir():
            continue
        zf.write(path, path.relative_to(stage).as_posix())
print("Wrote", out_zip)
PY

python3 - "$WIN_ZIP" <<'PY'
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
need = ["runtime/bin/java.exe", "runtime/bin/javaw.exe", "TotalIptvPro.exe"]
missing = [n for n in need if n not in names and n.replace("\\", "/") not in {x.replace("\\", "/") for x in names}]
if missing:
    raise SystemExit("FAIL: windows zip missing " + ", ".join(missing))
print("Windows zip contains java.exe, javaw.exe, TotalIptvPro.exe")
PY

echo
echo "OK"
ls -lh "$LINUX_TAR" "$WIN_ZIP"
sha256sum "$LINUX_TAR" "$WIN_ZIP"
