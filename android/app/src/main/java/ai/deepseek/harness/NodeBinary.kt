package ai.deepseek.harness

import android.content.Context
import java.io.File

/**
 * Locates the bundled Node executable.
 *
 * Since API 29 Android refuses to `execve()` a file in an app-writable
 * directory (W^X), so a Node binary copied into filesDir would fail with
 * EACCES no matter what mode bits it carries. The supported escape hatch is
 * the native library directory: files packaged as `lib/<abi>/*.so` are
 * extracted by the installer into a read-only, executable location.
 *
 * The build therefore ships the Node CLI as `libnode.so` in jniLibs. It is a
 * plain ELF executable, not a shared library -- the `.so` name is purely
 * what makes the packager and the installer treat it correctly.
 */
object NodeBinary {

    private const val LIB_NAME = "libnode.so"

    /** @return the executable Node binary for this device's ABI, or null when absent. */
    fun resolve(context: Context): File? {
        val candidate = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (candidate.isFile && candidate.canExecute()) return candidate
        // A device that installed the APK without extracting native libs
        // (extractNativeLibs=false paths) still exposes them inside the APK,
        // but they are not executable there -- report absence rather than
        // returning a path that will fail at spawn time.
        return null
    }
}
