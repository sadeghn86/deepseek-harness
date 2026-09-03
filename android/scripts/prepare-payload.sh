#!/usr/bin/env bash
#
# Build the Node payload that ships inside the APK.
#
# Produces android/app/src/main/assets/payload.zip containing an installed
# @deepseek-ai/dsh tree plus the default profile patch. The app unpacks this
# on first launch into its private files dir and runs `dsh web` against it
# with the bundled Node-for-Android binary.
#
# Usage: android/scripts/prepare-payload.sh [dsh-version-or-dist-tag]
set -euo pipefail

DSH_SPEC="${1:-alpha}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
ASSETS_DIR="$ANDROID_DIR/app/src/main/assets"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> installing @deepseek-ai/dsh@$DSH_SPEC into $WORK_DIR"
cd "$WORK_DIR"
cat > package.json <<'JSON'
{
  "name": "dsh-android-payload",
  "private": true,
  "type": "commonjs"
}
JSON

# --ignore-scripts: install hooks compile native addons for the BUILD host
# (glibc x64), which are useless on Android and only bloat the APK. The
# payload runs pure JS; see the node-pty shim applied below.
npm install "@deepseek-ai/dsh@$DSH_SPEC" \
  --ignore-scripts --omit=optional --no-audit --no-fund --loglevel=error

echo "==> pruning host-only artifacts"
# Source maps and markdown never execute; dropping them is pure size win.
find node_modules -type f \( -iname '*.map' -o -iname '*.md' \) -delete 2>/dev/null || true
# Prebuilt .node binaries are ELF for glibc/mach-o/PE. None can load under
# bionic on Android, so every one of them is dead weight.
find node_modules -type f -name '*.node' -delete 2>/dev/null || true
find node_modules -type d \( -name 'prebuilds' -o -name '*darwin*' -o -name '*win32*' \) \
  -prune -exec rm -rf {} + 2>/dev/null || true

echo "==> applying the pure-JS node-pty shim"
# dsh-subprocess-local imports node-pty unconditionally, and the import fails
# before any config can disable it. Replacing the entry point with a
# child_process-backed implementation keeps the whole plugin tree loadable.
if [ -d node_modules/node-pty/lib ]; then
  cp "$ANDROID_DIR/payload-patches/node-pty-shim.js" node_modules/node-pty/lib/index.js
  rm -rf node_modules/node-pty/src node_modules/node-pty/third_party node_modules/node-pty/scripts
else
  echo "warning: node-pty not present; skipping shim" >&2
fi

echo "==> applying the pure-JS koffi shim"
# koffi is a native FFI addon used only to reach Win32 APIs. It is imported at
# module scope by several plugins, so the import must succeed even though no
# call through it is reachable on Android.
if [ -d node_modules/koffi ]; then
  # koffi declares "type": "module", so its .js entries are parsed as ESM and
  # its .cjs entries as CommonJS; each needs the matching shim dialect.
  for entry in index.js indirect.js; do
    [ -f "node_modules/koffi/$entry" ] && \
      cp "$ANDROID_DIR/payload-patches/koffi-shim.mjs" "node_modules/koffi/$entry"
  done
  for entry in index.cjs indirect.cjs; do
    [ -f "node_modules/koffi/$entry" ] && \
      cp "$ANDROID_DIR/payload-patches/koffi-shim.js" "node_modules/koffi/$entry"
  done
  rm -rf node_modules/koffi/src node_modules/koffi/vendor node_modules/koffi/build node_modules/koffi/doc
else
  echo "warning: koffi not present; skipping shim" >&2
fi

echo "==> applying the sharp shim"
# sharp wraps libvips as a native addon and publishes no Android build. The
# attachment plugin that uses it is disabled in the profile patch, but its
# module-scope import still has to resolve, so the stub exists purely to make
# the import succeed and to fail loudly if anything actually calls it.
if [ -d node_modules/sharp/dist ]; then
  cp "$ANDROID_DIR/payload-patches/sharp-shim.js" node_modules/sharp/dist/index.cjs
  cp "$ANDROID_DIR/payload-patches/sharp-shim.mjs" node_modules/sharp/dist/index.mjs
  rm -rf node_modules/sharp/src node_modules/sharp/vendor node_modules/sharp/lib
else
  echo "warning: sharp/dist not present; skipping shim" >&2
fi

echo "==> staging the default profile patch"
mkdir -p profile
cp "$ANDROID_DIR/payload-patches/cordis.patch.yml" profile/cordis.patch.yml
# patchReload: startup instead of the web template's "live". Live reload
# installs file watchers and the HMR plugin, and HMR hard-requires the
# --expose-internals Node flag; neither is useful on a phone where nobody is
# editing the profile while it runs.
cp "$ANDROID_DIR/payload-patches/profile-package.json" profile/package.json

echo "==> zipping payload"
mkdir -p "$ASSETS_DIR"
rm -f "$ASSETS_DIR/payload.zip"
# -9 matters: the payload is the dominant contributor to APK size.
zip -q -r -9 "$ASSETS_DIR/payload.zip" node_modules package.json profile

# The app compares this against the unpacked marker to decide whether a
# freshly installed APK must re-extract its payload.
PAYLOAD_HASH="$(sha256sum "$ASSETS_DIR/payload.zip" | cut -c1-16)"
printf '%s' "$PAYLOAD_HASH" > "$ASSETS_DIR/payload.version"

echo "==> payload ready: $(du -h "$ASSETS_DIR/payload.zip" | cut -f1) (version $PAYLOAD_HASH)"
