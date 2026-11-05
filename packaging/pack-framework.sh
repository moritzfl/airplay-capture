#!/bin/sh
# Builds the framework-based SPK. The generic framework lives in a sibling checkout by default.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAMEWORK_DIR="${FRAMEWORK_DIR:-$ROOT/../synology-docker-bridge}"

if [ ! -f "$FRAMEWORK_DIR/packaging/generate.mjs" ]; then
    echo "framework not found at $FRAMEWORK_DIR; set FRAMEWORK_DIR" >&2
    exit 1
fi

node "$FRAMEWORK_DIR/packaging/generate.mjs" \
    --config "$ROOT/packaging/app.json" \
    --output "$ROOT/packaging/dist"
