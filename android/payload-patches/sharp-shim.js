/**
 * `sharp` stand-in for Android.
 *
 * sharp is a libvips binding shipped as prebuilt native binaries; there is no
 * Android build, and libvips is far too large to bundle in an APK even if
 * there were. Only @deepseek-ai/dsh-attachment-local uses it -- for image
 * metadata, resizing, and colourspace conversion of image attachments -- and
 * the Android profile disables that plugin.
 *
 * The module still has to *import* cleanly, because a failed import aborts
 * the whole plugin tree before any config can disable anything. So this
 * exports a callable that refuses at use time rather than at load time. If a
 * future profile re-enables image attachments, the failure will name sharp
 * directly instead of surfacing as a mysterious module-resolution error.
 */

'use strict'

function unavailable() {
  throw new Error(
    'android: image processing is unavailable — sharp (libvips) has no Android build, '
    + 'so image attachments are disabled in this app',
  )
}

/** The sharp factory: constructing a pipeline is itself the unsupported step. */
function sharp() {
  unavailable()
}

// Static helpers consumers may touch before ever constructing a pipeline.
sharp.cache = () => ({ memory: {}, files: {}, items: {} })
sharp.concurrency = () => 1
sharp.counters = () => ({ queue: 0, process: 0 })
sharp.simd = () => false
sharp.format = {}
sharp.versions = { vips: '0.0.0' }
sharp.queue = { on: () => {} }

module.exports = sharp
module.exports.default = sharp
module.exports.__esModule = true
