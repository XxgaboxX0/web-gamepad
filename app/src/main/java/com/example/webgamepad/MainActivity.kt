package com.example.webgamepad

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Navegador/contenedor para juegos web clásicos con gamepad virtual.
 *
 * Dos modos:
 *  - NAVEGAR: barra superior (URL / búsqueda de Google) + WebView.
 *  - JUGAR  : pantalla completa, horizontal, con el gamepad flotante encima del WebView.
 *
 * Cada botón del gamepad (activity_main.xml) declara en `android:tag` el nombre de la tecla
 * (KeyboardEvent.code). Aquí se enlazan automáticamente: ACTION_DOWN -> keydown,
 * ACTION_UP/CANCEL -> keyup, mediante window.__vgp (assets/gamepad_controller.js).
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val HOME_URL = "https://www.google.com"
        const val SEARCH_URL = "https://www.google.com/search?q="
        const val SCRIPT_ASSET = "gamepad_controller.js"

        /** true = el WebView se presenta como navegador de escritorio (mejor para juegos de PC). */
        const val DESKTOP_MODE = true

        /** Esquemas que se navegan dentro del WebView; el resto (intent:, market:...) se bloquea. */
        val ALLOWED_SCHEMES = setOf("http", "https", "about", "data")
    }

    // ----------------------------------------------------------------- Vistas
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var urlInput: EditText
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var customViewContainer: FrameLayout
    private lateinit var gamepadOverlay: ViewGroup
    private lateinit var btnMenu: View

    // ----------------------------------------------------------------- Estado
    private var playMode = false
    private var hasDocumentStartScript = false

    // Pantalla completa HTML5 (element.requestFullscreen / vídeo)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Script JS del controlador, leído una sola vez desde /assets. */
    private val gamepadScript: String by lazy {
        assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
    }

    // =======================================================================
    // Ciclo de vida
    // =======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge explícito: así el comportamiento es igual en todas las versiones de Android.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        setupWebView()
        setupToolbar()
        bindGamepadButtons()
        setupBackHandling()

        // Restaurar la página si el proceso fue recreado; si no, cargar la home.
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        releaseAllKeys()   // evita teclas "pegadas" al salir de la app
        webView.onPause()  // pausa timers/animaciones del juego en segundo plano
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // =======================================================================
    // Inicialización de vistas
    // =======================================================================

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        urlInput = findViewById(R.id.urlInput)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        customViewContainer = findViewById(R.id.customViewContainer)
        gamepadOverlay = findViewById(R.id.gamepadOverlay)
        btnMenu = findViewById(R.id.btnMenu)
    }

    /** Respeta barras del sistema, notch y teclado con padding en la raíz. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
            val bars = insets.getInsets(types)
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbar() {
        findViewById<View>(R.id.btnGo).setOnClickListener { loadFromInput() }
        findViewById<View>(R.id.btnPlay).setOnClickListener { setPlayMode(true) }
        btnMenu.setOnClickListener { setPlayMode(false) }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chromeClient.onHideCustomView() // salir de fullscreen HTML5
                    playMode -> setPlayMode(false)                        // salir del modo juego
                    webView.canGoBack() -> webView.goBack()               // historial
                    else -> finish()
                }
            }
        })
    }

    // =======================================================================
    // WebView
    // =======================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true                       // imprescindible para los juegos
            domStorageEnabled = true                       // localStorage/sessionStorage (partidas guardadas)
            mediaPlaybackRequiresUserGesture = false       // audio/vídeo sin toque previo
            useWideViewPort = true                         // layout ancho tipo escritorio
            loadWithOverviewMode = true                    // ajusta la página al ancho de la pantalla
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false                        // seguridad: sin acceso a file://
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            if (DESKTOP_MODE) {
                // Quita las marcas "móvil" y "WebView" del User-Agent
                userAgentString = userAgentString
                    .replace("; wv", "")
                    .replace("Mobile Safari", "Safari")
            }
        }

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = chromeClient

        // Inyección ANTES de que cargue cualquier script de la página, en todos los frames (iframes incluidos).
        hasDocumentStartScript =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (hasDocumentStartScript) {
            WebViewCompat.addDocumentStartJavaScript(webView, gamepadScript, setOf("*"))
        }
        // Si el WebView del dispositivo es antiguo, se usa el plan B en onPageStarted/onPageFinished.
    }

    private fun createWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme ?: return false
            return scheme !in ALLOWED_SCHEMES // true = bloquear esquemas raros
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null && !urlInput.hasFocus()) urlInput.setText(url)
            if (!hasDocumentStartScript) injectGamepadScript()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!hasDocumentStartScript) injectGamepadScript()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                val reason = error?.description ?: ""
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_loading, reason),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.isVisible = newProgress < 100
        }

        // Pantalla completa HTML5. El contenedor queda DEBAJO del gamepad,
        // así los controles siguen visibles sobre el juego en fullscreen.
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) return
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            customViewContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            customViewContainer.isVisible = true
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            val callback = customViewCallback
            customView = null
            customViewCallback = null
            customViewContainer.removeView(view)
            customViewContainer.isVisible = false
            callback?.onCustomViewHidden()
        }
    }

    /** Plan B para WebViews sin soporte de document-start script (solo frame principal). */
    private fun injectGamepadScript() {
        webView.evaluateJavascript(gamepadScript, null)
    }

    // =======================================================================
    // Navegación: URL directa o búsqueda en Google
    // =======================================================================

    private fun loadFromInput() {
        webView.loadUrl(resolveInput(urlInput.text.toString()))
        hideKeyboard()
        urlInput.clearFocus()
        webView.requestFocus()
    }

    private fun resolveInput(raw: String): String {
        val text = raw.trim()
        return when {
            text.isEmpty() -> HOME_URL
            text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) -> text
            // "ejemplo.com/juego" -> https://ejemplo.com/juego
            !text.contains(' ') && Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
            // Cualquier otra cosa -> búsqueda en Google
            else -> SEARCH_URL + Uri.encode(text)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    // =======================================================================
    // Modo juego / modo navegar
    // =======================================================================

    private fun setPlayMode(enabled: Boolean) {
        playMode = enabled
        toolbar.isVisible = !enabled
        gamepadOverlay.isVisible = enabled
        btnMenu.isVisible = enabled
        if (!enabled) releaseAllKeys()

        setImmersive(enabled)
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        hideKeyboard()
        webView.requestFocus()
    }

    /** Oculta/muestra barra de estado y de navegación (deslizar desde el borde las muestra un instante). */
    private fun setImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // =======================================================================
    // Gamepad virtual
    // =======================================================================

    /** Recorre el overlay y ejecuta [block] por cada vista que tenga un android:tag con nombre de tecla. */
    private fun forEachKeyButton(group: ViewGroup, block: (View, String) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) forEachKeyButton(child, block)
            (child.tag as? String)?.let { block(child, it) }
        }
    }

    /** Enlaza TODOS los botones etiquetados. Para añadir uno nuevo basta con ponerle android:tag en el XML. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindGamepadButtons() {
        forEachKeyButton(gamepadOverlay) { button, keyName ->
            button.setOnTouchListener { v, event -> onKeyButtonTouch(v, keyName, event) }
        }
    }

    /**
     * Cada botón es una vista independiente y el overlay tiene splitMotionEvents="true",
     * por lo que se pueden pulsar varios a la vez (multitáctil: correr + saltar + disparar).
     */
    private fun onKeyButtonTouch(v: View, keyName: String, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true // activa el estado visual "pulsado" del drawable
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendKey("down", keyName)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                sendKey("up", keyName)
            }
        }
        return true // consumimos el gesto: no se propaga al WebView
    }

    /** action: "down" | "up". keyName viene de nuestros propios tags XML (solo letras/números). */
    private fun sendKey(action: String, keyName: String) {
        webView.evaluateJavascript("window.__vgp&&window.__vgp.$action('$keyName')", null)
    }

    /** Suelta todas las teclas en la página y apaga el estado visual de los botones. */
    private fun releaseAllKeys() {
        webView.evaluateJavascript("window.__vgp&&window.__vgp.reset()", null)
        forEachKeyButton(gamepadOverlay) { button, _ -> button.isPressed = false }
    }
}
