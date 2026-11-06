#!/bin/sh
# Builds the framework-based SPK. The generic framework lives in a sibling checkout by default.
# ARCH=armv8 builds the package for ARM models; the default is x86_64.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAMEWORK_DIR="${FRAMEWORK_DIR:-$ROOT/../synology-docker-bridge}"
ARCH="${ARCH:-x86_64}"

if [ ! -f "$FRAMEWORK_DIR/packaging/generate.mjs" ]; then
    echo "framework not found at $FRAMEWORK_DIR; set FRAMEWORK_DIR" >&2
    exit 1
fi

# The version lives in build.gradle.kts only. DSM accepts numeric versions only, so a
# development version such as 1.0.2-SNAPSHOT is packaged as 1.0.2; installing the release
# 1.0.2 later is then an ordinary update.
VERSION="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/build.gradle.kts")"
if [ -z "$VERSION" ]; then
    echo "no version found in build.gradle.kts" >&2
    exit 1
fi
PACKAGE_VERSION="${VERSION%-SNAPSHOT}"

# Next to app.json, so its relative paths keep working.
CONFIG="$ROOT/packaging/.app.build.json"
trap 'rm -f "$CONFIG"' EXIT
PACKAGE_VERSION="$PACKAGE_VERSION" node -e '
const fs = require("fs");
const config = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
config.version = process.env.PACKAGE_VERSION;
fs.writeFileSync(process.argv[2], JSON.stringify(config, null, 2) + "\n");
' "$ROOT/packaging/app.json" "$CONFIG"

node "$FRAMEWORK_DIR/packaging/generate.mjs" \
    --config "$CONFIG" \
    --arch "$ARCH" \
    --output "$ROOT/packaging/dist"
