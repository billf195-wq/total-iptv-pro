#!/usr/bin/env bash
# Idempotent Cloud Agent setup for Total IPTV Pro.
# Provisions JDK 21 + Android SDK and primes Gradle caches for the three modules:
#   desktop/    Compose Desktop JVM app (runnable GUI)
#   android/    Total IPTV Pro 2 (com.totaliptv.pro2) Android app
#   channelbox/ Total IPTV Pro (com.totaliptv.pro) Android app (tv + phone flavors)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- JDK 21 (present in the Cursor default image; install as a fallback) ---
if ! command -v java >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends openjdk-21-jdk
fi
JAVA_HOME_DIR="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# --- Android SDK ---
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
CLI_TOOLS_VER="11076708"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/clt.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CLI_TOOLS_VER}_latest.zip"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-36" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "build-tools;34.0.0" >/dev/null

# --- Persist env vars for future interactive shells (idempotent) ---
PROFILE="$HOME/.bashrc"
MARKER="# >>> total-iptv-pro env >>>"
if ! grep -qF "$MARKER" "$PROFILE" 2>/dev/null; then
  {
    echo "$MARKER"
    echo "export JAVA_HOME=\"$JAVA_HOME_DIR\""
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"'
    echo "# <<< total-iptv-pro env <<<"
  } >> "$PROFILE"
fi

# --- Prime Gradle: download wrappers + dependencies and verify all modules build ---
( cd "$REPO_ROOT/desktop"    && ./gradlew --no-daemon compileKotlin )
( cd "$REPO_ROOT/android"    && ./gradlew --no-daemon assembleDebug )
( cd "$REPO_ROOT/channelbox" && ./gradlew --no-daemon :app:assembleTvDebug :app:assemblePhoneDebug )

echo "Total IPTV Pro environment ready."
