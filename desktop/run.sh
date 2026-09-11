#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-21}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 not found at $JAVA_HOME"
  echo "Install Temurin 21 under ~/.jdks/temurin-21 or set JAVA_HOME."
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
export DISPLAY="${DISPLAY:-:0}"
# Prefer Mutter Xwayland auth when present
if [[ -z "${XAUTHORITY:-}" ]]; then
  for f in /run/user/$(id -u)/.mutter-Xwaylandauth.*; do
    if [[ -r "$f" ]]; then export XAUTHORITY="$f"; break; fi
  done
fi
cd "$ROOT"
exec ./gradlew run "$@"
