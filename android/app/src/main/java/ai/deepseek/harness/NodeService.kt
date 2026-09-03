package ai.deepseek.harness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs `node dsh web` as a child process for as long as the app is alive.
 *
 * This is a foreground service because a model turn is long-running work the
 * user is waiting on: without the foreground promotion Android suspends the
 * process as soon as the activity leaves the screen, which would kill the
 * HTTP server the WebView is talking to mid-request.
 */
class NodeService : Service() {

    companion object {
        private const val TAG = "NodeService"
        private const val CHANNEL_ID = "dsh_runtime"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ai.deepseek.harness.START"
        const val ACTION_STOP = "ai.deepseek.harness.STOP"

        /** Loopback-only: the harness refuses 0.0.0.0 because it is remote code execution. */
        const val HOST = "127.0.0.1"
        const val PORT = 3080

        /** Server URL including the boot token, published once the child prints it. */
        val serverUrl = AtomicReference<String?>(null)

        /** Rolling log tail, surfaced in the app's status screen for diagnosis. */
        val logTail = StringBuilder()

        @Volatile
        var lastError: String? = null

        @Synchronized
        fun appendLog(line: String) {
            logTail.append(line).append('\n')
            // An unbounded builder would grow with every model token echoed
            // to stderr; keep only what a human would actually read.
            if (logTail.length > 40_000) logTail.delete(0, logTail.length - 20_000)
        }

        @Synchronized
        fun snapshotLog(): String = logTail.toString()
    }

    private var process: Process? = null
    private var runner: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHarness()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting DeepSeek Harness…"))

        if (process?.isAlive != true) startHarness()
        // START_STICKY would restart us with a null intent after an OOM kill,
        // spawning a second Node on a port the first may still hold.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopHarness()
        super.onDestroy()
    }

    private fun startHarness() {
        val context = applicationContext
        val nodeBinary = NodeBinary.resolve(context)
        if (nodeBinary == null) {
            fail("Node binary is missing from this build (unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}).")
            return
        }
        val entry = PayloadInstaller.dshEntry(context)
        if (!entry.isFile) {
            fail("Runtime payload is not installed.")
            return
        }

        PayloadInstaller.seedProfilePatch(context)

        val home = PayloadInstaller.dshHome(context)
        val workspace = PayloadInstaller.workspaceDir(context)
        home.mkdirs()
        workspace.mkdirs()

        val command = listOf(
            nodeBinary.absolutePath,
            entry.absolutePath,
            "web",
            "--no-open",
            "--host", HOST,
            "--port", PORT.toString(),
        )

        val builder = ProcessBuilder(command)
            // cwd is the agent's default workspace: unqualified paths the
            // model writes land in a directory the user can find and clear.
            .directory(workspace)
            .redirectErrorStream(true)

        val env = builder.environment()
        env["HOME"] = context.filesDir.absolutePath
        env["DSH_HOME"] = home.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        // Node resolves the payload's own modules; nothing else is on disk.
        env["NODE_PATH"] = File(PayloadInstaller.runtimeDir(context), "node_modules").absolutePath
        // Android has no /bin/sh in PATH by default for app uids; give the
        // bash executor and any child tooling the standard system locations
        // plus the app's own bin dir where the node binary lives.
        env["PATH"] = listOf(
            nodeBinary.parentFile?.absolutePath,
            "/system/bin",
            "/system/xbin",
        ).filterNotNull().joinToString(":")
        env["SHELL"] = "/system/bin/sh"
        env["TERM"] = "dumb"
        // The device has finite RAM and V8's default heap assumes a desktop;
        // a runaway heap here means the OS kills the whole app.
        env["NODE_OPTIONS"] = "--max-old-space-size=512"

        serverUrl.set(null)
        lastError = null

        runner = Thread {
            try {
                val proc = builder.start()
                process = proc
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, line)
                        appendLog(line)
                        // The harness prints its tokenized URL exactly once on
                        // a successful boot; that line is our readiness signal.
                        if (serverUrl.get() == null && line.contains("http://$HOST:$PORT")) {
                            val url = Regex("http://\\S+").find(line)?.value
                            if (url != null) {
                                serverUrl.set(url)
                                notify("DeepSeek Harness is running")
                            }
                        }
                    }
                }
                val code = proc.waitFor()
                appendLog("[harness exited with code $code]")
                if (serverUrl.get() == null) lastError = "The harness exited (code $code) before it started serving."
                serverUrl.set(null)
            } catch (t: Throwable) {
                Log.e(TAG, "harness failed", t)
                fail("Failed to launch the harness: ${t.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun fail(message: String) {
        lastError = message
        appendLog("[error] $message")
        notify("DeepSeek Harness stopped")
    }

    private fun stopHarness() {
        // destroyForcibly on the parent leaves Node's own children orphaned,
        // but Android reaps the whole uid's processes when the app dies, so
        // the leak is bounded by the app lifetime.
        process?.destroy()
        process = null
        runner = null
        serverUrl.set(null)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Harness runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps the DeepSeek Harness server running." }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
