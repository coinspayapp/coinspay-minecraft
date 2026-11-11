#!/bin/sh
# Runs Gradle in Docker: no local JDK needed. The Gradle cache lives in a named volume.
#   bin/gradle.sh build
#   bin/gradle.sh :core:test
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd -W 2>/dev/null || pwd)"
MSYS_NO_PATHCONV=1 exec docker run --rm \
  -v "$ROOT":/project \
  -v coinspay-mc-gradle:/home/gradle/.gradle \
  -w /project \
  gradle:8.14.3-jdk21 \
  gradle --no-daemon --console=plain "$@"
