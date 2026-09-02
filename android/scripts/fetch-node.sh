#!/usr/bin/env bash
#
# Download a Node-for-Android build and stage it as a jniLib.
#
# Android will not execute a binary from an app-writable directory (W^X since
# API 29). Files packaged under lib/<abi>/ are the one exception: the
# installer places them in a read-only directory with the execute bit set. So
# the Node CLI ships named libnode.so even though it is a plain executable.
#
# Upstream Node publishes no Android binaries; this pulls from the
# Towartz/nodejs-arm CI, which cross-compiles the official sources with the
# NDK. Override NODE_RELEASE_TAG / NODE_ASSET to pin a different build.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
JNI_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"

NODE_REPO="${NODE_REPO:-Towartz/nodejs-arm}"
NODE_RELEASE_TAG="${NODE_RELEASE_TAG:-node-android-v26.7.0-run165}"
NODE_ASSET="${NODE_ASSET:-node-v26.7.0-android-arm64-v8a.zip}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

URL="https://github.com/$NODE_REPO/releases/download/$NODE_RELEASE_TAG/$NODE_ASSET"
echo "==> downloading $URL"
curl -fsSL --retry 3 -o "$WORK_DIR/node.zip" "$URL"

echo "==> extracting"
unzip -q "$WORK_DIR/node.zip" -d "$WORK_DIR/unpacked"

# The archive layout varies between builds (bare `node`, or bin/node); find
# the executable rather than hard-coding a path that a rebuild may change.
NODE_BIN="$(find "$WORK_DIR/unpacked" -type f -name node | head -n 1)"
if [ -z "$NODE_BIN" ]; then
  echo "error: no 'node' executable inside $NODE_ASSET" >&2
  find "$WORK_DIR/unpacked" -maxdepth 3 >&2
  exit 1
fi

mkdir -p "$JNI_DIR"
cp "$NODE_BIN" "$JNI_DIR/libnode.so"
chmod +x "$JNI_DIR/libnode.so"

echo "==> staged $(du -h "$JNI_DIR/libnode.so" | cut -f1) at $JNI_DIR/libnode.so"
file "$JNI_DIR/libnode.so" 2>/dev/null || true
