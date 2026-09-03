package ai.deepseek.harness

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The app shell: installs the payload, starts the harness service, then hands
 * the screen to a WebView pointed at the local server.
 *
 * The UI is built in code rather than XML because it is three views and a
 * WebView; a layout file would add indirection without buying anything.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var loaded = false
    private var attempts = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service runs either way */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        webView = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#111318"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // The harness UI is a single-page app served over loopback;
                // it needs same-origin XHR/WebSocket, which is the default,
                // but must not be allowed to read file:// URLs.
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    // Keep the loopback app inside the WebView; send anything
                    // else (docs links, provider sign-up pages) to the browser.
                    if (url.host == NodeService.HOST) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    NodeService.appendLog("[web] ${message.message()}")
                    return true
                }
            }
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        statusPanel = buildStatusPanel()
        root.addView(
            statusPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loaded && webView.canGoBack()) webView.goBack() else finish()
            }
        })

        requestNotificationPermission()
        bootstrap()
    }

    private fun buildStatusPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(48, 96, 48, 48)
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 22f
        })
        statusText = TextView(this).apply {
            text = getString(R.string.status_preparing)
            setTextColor(Color.parseColor("#9BA3B4"))
            textSize = 15f
            setPadding(0, 24, 0, 24)
        }
        panel.addView(statusText)

        panel.addView(Button(this).apply {
            text = getString(R.string.action_retry)
            setOnClickListener {
                attempts = 0
                startService(Intent(this@MainActivity, NodeService::class.java).setAction(NodeService.ACTION_START))
                pollForServer()
            }
        })

        logText = TextView(this).apply {
            setTextColor(Color.parseColor("#6C7484"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        panel.addView(ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f; topMargin = 24 }
        })
        panel.gravity = Gravity.TOP
        return panel
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Extract the payload off the main thread, then start the service. */
    private fun bootstrap() {
        Thread {
            val ok = PayloadInstaller.ensureInstalled(applicationContext) { message ->
                handler.post { statusText.text = message }
            }
            handler.post {
                if (!ok) {
                    statusText.text = getString(R.string.status_install_failed)
                    return@post
                }
                val intent = Intent(this, NodeService::class.java).setAction(NodeService.ACTION_START)
                ContextCompat.startForegroundService(this, intent)
                statusText.text = getString(R.string.status_starting)
                pollForServer()
            }
        }.start()
    }

    /**
     * Wait for the harness to publish its URL.
     *
     * Node's cold start on a phone -- unpacked JS, no snapshot -- takes tens
     * of seconds, so polling beats any fixed delay.
     */
    private fun pollForServer() {
        if (loaded) return
        val url = NodeService.serverUrl.get()
        if (url != null) {
            loaded = true
            statusPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(url)
            return
        }

        val error = NodeService.lastError
        logText.text = NodeService.snapshotLog().takeLast(4000)
        if (error != null) {
            statusText.text = error
            return
        }

        attempts++
        // ~3 minutes at 500ms: generous enough for a slow first boot on an
        // low-end device, short enough that a genuine hang surfaces.
        if (attempts > 360) {
            statusText.text = getString(R.string.status_timeout)
            return
        }
        statusText.text = getString(R.string.status_starting_seconds, attempts / 2)
        handler.postDelayed({ pollForServer() }, 500)
    }
}
