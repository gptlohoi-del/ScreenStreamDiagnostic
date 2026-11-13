#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
javac -encoding UTF-8 -d "$WORKDIR" \
  tools/jvm-stubs/android/os/SystemClock.java \
  tools/jvm-stubs/com/example/screenstreamdiag/*.java \
  app/src/main/java/com/example/screenstreamdiag/{RtspServer,Rtcp,H264,AacRtp}.java \
  tools/RtspSmoke.java
java -cp "$WORKDIR" RtspSmoke
