package ai.deepseek.harness

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks the Node payload shipped in assets into the app's private files
 * directory.
 *
 * The payload cannot execute from inside the APK: Node needs a real
 * filesystem tree it can `require()` from, and assets are compressed entries
 * in a zip, not files. So the first launch after install (and after every
 * upgrade that changes the payload) extracts it once.
 */
object PayloadInstaller {

    private const val TAG = "PayloadInstaller"
    private const val PAYLOAD_ASSET = "payload.zip"
    private const val VERSION_ASSET = "payload.version"
    private const val MARKER_NAME = ".payload-version"

    /** Root the payload is extracted into; also the harness's working area. */
    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")

    /** The dsh launcher entry script inside the extracted payload. */
    fun dshEntry(context: Context): File =
        File(runtimeDir(context), "node_modules/@deepseek-ai/dsh/lib/bin.js")

    /** $DSH_HOME: profiles and sessions live here, outside the replaceable payload. */
    fun dshHome(context: Context): File = File(context.filesDir, "dsh-home")

    /** The agent's working directory -- the only tree the model edits by default. */
    fun workspaceDir(context: Context): File = File(context.filesDir, "workspace")

    /**
     * Extract the payload if this build's copy is not already unpacked.
     *
     * @param onProgress invoked with a human-readable status line.
     * @return true when the runtime tree is ready to launch.
     */
    fun ensureInstalled(context: Context, onProgress: (String) -> Unit): Boolean {
        val expected = readAssetText(context, VERSION_ASSET) ?: return false
        val runtime = runtimeDir(context)
        val marker = File(runtime, MARKER_NAME)

        if (marker.isFile && marker.readText().trim() == expected && dshEntry(context).isFile) {
            onProgress("Runtime already installed.")
            return true
        }

        onProgress("Installing runtime (first launch may take a minute)…")
        // A partial extraction from a killed install would leave a tree that
        // looks complete but is missing modules; always start clean.
        runtime.deleteRecursively()
        runtime.mkdirs()

        var files = 0
        try {
            context.assets.open(PAYLOAD_ASSET).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val canonicalRoot = runtime.canonicalPath + File.separator
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(runtime, entry.name)
                        // Zip-slip guard: a crafted entry name like ../../x
                        // would otherwise write outside the runtime dir.
                        if (!target.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("payload entry escapes runtime dir: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zip.copyTo(out, 64 * 1024) }
                            files++
                            if (files % 500 == 0) onProgress("Installing runtime… $files files")
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "payload extraction failed", t)
            onProgress("Runtime install failed: ${t.message}")
            runtime.deleteRecursively()
            return false
        }

        if (!dshEntry(context).isFile) {
            onProgress("Runtime install failed: dsh entry point missing from payload.")
            return false
        }

        marker.writeText(expected)
        dshHome(context).mkdirs()
        workspaceDir(context).mkdirs()
        onProgress("Runtime installed ($files files).")
        return true
    }

    /**
     * Seed $DSH_HOME with the Android profile patch.
     *
     * dsh creates the profile directory itself on first boot, but its default
     * composition mounts the sandbox chain, which cannot work here. Writing
     * the patch up front means the very first boot already succeeds.
     */
    fun seedProfilePatch(context: Context) {
        val profileSource = File(runtimeDir(context), "profile")
        if (!profileSource.isDirectory) return
        val profileDir = File(dshHome(context), "profiles/web")
        profileDir.mkdirs()

        val patchSource = File(profileSource, "cordis.patch.yml")
        val patchTarget = File(profileDir, "cordis.patch.yml")
        // Never clobber edits the user made to their own profile patch; the
        // bash-local entry is the marker that our patch is already applied.
        if (patchSource.isFile && !(patchTarget.isFile && patchTarget.readText().contains("dsh-bash-local"))) {
            patchSource.copyTo(patchTarget, overwrite = true)
        }

        // The profile manifest selects patchReload: startup. dsh writes the
        // stock "live" template if this file is absent, and live reload pulls
        // in HMR, which refuses to start without --expose-internals -- a flag
        // Node rejects from NODE_OPTIONS. Writing the manifest first avoids
        // the whole chain.
        val manifestSource = File(profileSource, "package.json")
        val manifestTarget = File(profileDir, "package.json")
        if (manifestSource.isFile && !manifestTarget.isFile) {
            manifestSource.copyTo(manifestTarget, overwrite = true)
        }
    }

    private fun readAssetText(context: Context, name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText().trim() }
    } catch (t: Throwable) {
        Log.e(TAG, "missing asset $name", t)
        null
    }
}
