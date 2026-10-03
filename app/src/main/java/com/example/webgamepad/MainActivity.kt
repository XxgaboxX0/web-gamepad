package com.example.webgamepad

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var btnGo: TextView
    private lateinit var btnPlay: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var gamepadOverlay: FrameLayout
    private lateinit var customViewContainer: FrameLayout
    private lateinit var btnMenu: TextView
    private lateinit var toolbar: View
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        btnGo = findViewById(R.id.btnGo)
        btnPlay = findViewById(R.id.btnPlay)
        progressBar = findViewById(R.id.progressBar)
        gamepadOverlay = findViewById(R.id.gamepadOverlay)
        customViewContainer = findViewById(R.id.customViewContainer)
        btnMenu = findViewById(R.id.btnMenu)
        toolbar = findViewById(R.id.toolbar)

        setupWebView()
        setupUi()
        setupGamepadButtons()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGamepadButtons() {
        // Recorremos los hijos del overlay y asignamos touch listeners a los que tengan tag
        fun attachRecursively(v: View) {
            if (v.tag != null) {
                v.setOnTouchListener { view, ev ->
                    val name = view.tag?.toString() ?: return@setOnTouchListener true
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> sendDown(name)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> sendUp(name)
                    }
                    true
                }
                // permitir múltiples punteros
                v.isClickable = true
                v.isFocusable = true
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) attachRecursively(v.getChildAt(i))
            }
        }
        attachRecursively(gamepadOverlay)

        btnMenu.setOnClickListener { exitGameMode() }

        btnGo.setOnClickListener { loadFromInput() }
        btnPlay.setOnClickListener { enterGameMode() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false

        // Opcional: presentar como desktop si quieres (descomenta para habilitar)
        // settings.userAgentString = settings.userAgentString.replace("Mobile", "Desktop")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.error_loading, error?.description ?: "error"),
                    Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                customViewContainer.addView(view)
                customViewContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                hideSystemUI()
            }

            override fun onHideCustomView() {
                customViewCallback?.onCustomViewHidden()
                customViewContainer.removeAllViews()
                customView = null
                customViewCallback = null
                customViewContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                showSystemUI()
            }
        }

        // Inyectar el JS de gamepad al inicio de los frames si la API lo permite.
        val js = assets.open("gamepad_controller.js").bufferedReader().use { it.readText() }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ADD_DOCUMENT_START_JAVASCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, js, listOf("*"))
            } else {
                // Fallback: inyectar en el frame principal cuando acabe de cargar
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(js, null)
                    }
                }
            }
        } catch (e: Exception) {
            // no bloquear el uso si falla la inyección
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(js, null)
                }
            }
        }

        // Cargar una página por defecto
        webView.loadUrl("https://www.google.com")
    }

    private fun sendDown(name: String) {
        val safe = escapeJsString(name)
        webView.post { webView.evaluateJavascript("window.__vgp && window.__vgp.down('$safe')", null) }
    }

    private fun sendUp(name: String) {
        val safe = escapeJsString(name)
        webView.post { webView.evaluateJavascript("window.__vgp && window.__vgp.up('$safe')", null) }
    }

    private fun escapeJsString(s: String): String {
        return s.replace("'", "\\'")
    }

    private fun loadFromInput() {
        val text = urlInput.text.toString().trim()
        if (text.isEmpty()) return
        val url = if (!text.contains(" ") && (text.contains(".") || text.startsWith("http"))) {
            if (text.startsWith("http")) text else "http://$text"
        } else {
            "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        }
        webView.loadUrl(url)
    }

    private fun enterGameMode() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        toolbar.visibility = View.GONE
        gamepadOverlay.visibility = View.VISIBLE
        btnMenu.visibility = View.VISIBLE
        hideSystemUI()
    }

    private fun exitGameMode() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        toolbar.visibility = View.VISIBLE
        gamepadOverlay.visibility = View.GONE
        btnMenu.visibility = View.GONE
        showSystemUI()
        // Asegurar que las teclas virtuales se sueltan
        webView.evaluateJavascript("window.__vgp && window.__vgp.reset()", null)
    }

    override fun onBackPressed() {
        when {
            customViewContainer.visibility == View.VISIBLE -> {
                webView.webChromeClient.onHideCustomView()
            }
            gamepadOverlay.visibility == View.VISIBLE -> exitGameMode()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}
