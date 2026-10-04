package com.example.webgamepad

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
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
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Navegador/contenedor para juegos web clásicos con gamepad virtual.
 *
 * Modos:
 *  - NAVEGAR: barra superior (URL / búsqueda de Google) + página.
 *  - JUGAR  : pantalla completa, horizontal, con el gamepad flotante encima de la página.
 *
 * Novedades de esta versión:
 *  - Zoom con los dedos, botones Acercar/Alejar y opción "Bloquear zoom".
 *  - "Modo computadora" activable desde el menú (User-Agent de PC + viewport de escritorio).
 *  - Varias pestañas (menú ⋮ / ☰) y enlaces target="_blank" que abren una pestaña nueva.
 *
 * Cada botón del gamepad (activity_main.xml) declara en `android:tag` el nombre de la tecla
 * (KeyboardEvent.code). Se enlazan automáticamente: ACTION_DOWN -> keydown, ACTION_UP/CANCEL -> keyup,
 * mediante window.__vgp (assets/gamepad_controller.js).
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val HOME_URL = "https://www.google.com"
        const val SEARCH_URL = "https://www.google.com/search?q="
        const val GAMEPAD_SCRIPT_ASSET = "gamepad_controller.js"
        const val DESKTOP_SCRIPT_ASSET = "desktop_viewport.js"

        // Preferencias persistentes
        const val PREFS_NAME = "web_gamepad_prefs"
        const val PREF_DESKTOP_MODE = "desktop_mode"
        const val PREF_ZOOM_LOCKED = "zoom_locked"

        // Estado guardado (por si Android mata el proceso en segundo plano)
        const val STATE_TAB_URLS = "tab_urls"
        const val STATE_CURRENT_TAB = "current_tab"

        // Identificadores del menú
        const val MENU_PLAY = 1
        const val MENU_NEW_TAB = 2
        const val MENU_TABS = 3
        const val MENU_ZOOM_IN = 4
        const val MENU_ZOOM_OUT = 5
        const val MENU_ZOOM_LOCK = 6
        const val MENU_DESKTOP = 7
        const val MENU_RELOAD = 8

        const val ZOOM_STEP_IN = 1.25f
        const val ZOOM_STEP_OUT = 0.8f

        /** Esquemas que se navegan dentro del WebView; el resto (intent:, market:...) se bloquea. */
        val ALLOWED_SCHEMES = setOf("http", "https", "about", "data")
    }

    // ----------------------------------------------------------------- Vistas
    private lateinit var root: View
    private lateinit var toolbar: View
    private lateinit var urlInput: EditText
    private lateinit var webContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var customViewContainer: FrameLayout
    private lateinit var gamepadOverlay: ViewGroup
    private lateinit var btnMenu: View
    private lateinit var btnMore: View

    // ----------------------------------------------------------------- Estado
    private var playMode = false
    private var desktopMode = true
    private var zoomLocked = false

    /** Pestañas abiertas (cada una es un WebView) y la que está visible. */
    private val tabs = mutableListOf<WebView>()
    private var currentTab = -1

    /** WebView de la pestaña activa. */
    private val webView: WebView
        get() = tabs[currentTab]

    /** Handlers del script de viewport de escritorio (uno por pestaña) para poder quitarlo al desactivar el modo. */
    private val desktopScriptHandlers = HashMap<WebView, ScriptHandler>()

    // Pantalla completa HTML5 (element.requestFullscreen / vídeo)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // ------------------------------------------------------- Valores perezosos
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val hasDocumentStartScript: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    private val gamepadScript: String by lazy { readAsset(GAMEPAD_SCRIPT_ASSET) }
    private val desktopViewportScript: String by lazy { readAsset(DESKTOP_SCRIPT_ASSET) }

    private val defaultUserAgent: String by lazy { WebSettings.getDefaultUserAgent(this) }
    private val desktopUserAgent: String by lazy { buildDesktopUserAgent() }

    // =======================================================================
    // Ciclo de vida
    // =======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        desktopMode = prefs.getBoolean(PREF_DESKTOP_MODE, true)
        zoomLocked = prefs.getBoolean(PREF_ZOOM_LOCKED, false)

        bindViews()
        applyWindowInsets()
        setupToolbar()
        bindGamepadButtons()
        setupBackHandling()

        if (gamepadScript.isEmpty()) toast(R.string.toast_missing_script)
        restoreOrOpenTabs(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_TAB_URLS, ArrayList(tabs.map { it.url ?: HOME_URL }))
        outState.putInt(STATE_CURRENT_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        if (currentTab in tabs.indices) webView.onResume()
    }

    override fun onPause() {
        releaseAllKeys()                                 // evita teclas "pegadas" al salir de la app
        if (currentTab in tabs.indices) webView.onPause() // pausa timers/animaciones en segundo plano
        super.onPause()
    }

    override fun onDestroy() {
        tabs.toList().forEach { destroyWebView(it) }
        tabs.clear()
        currentTab = -1
        super.onDestroy()
    }

    // =======================================================================
    // Inicialización de vistas
    // =======================================================================

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        urlInput = findViewById(R.id.urlInput)
        webContainer = findViewById(R.id.webContainer)
        progressBar = findViewById(R.id.progressBar)
        customViewContainer = findViewById(R.id.customViewContainer)
        gamepadOverlay = findViewById(R.id.gamepadOverlay)
        btnMenu = findViewById(R.id.btnMenu)
        btnMore = findViewById(R.id.btnMore)
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
        btnMore.setOnClickListener { showAppMenu(it) }
        btnMenu.setOnClickListener { showAppMenu(it) }

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
                    webView.canGoBack() -> webView.goBack()               // historial de la pestaña
                    tabs.size > 1 -> closeTab(currentTab)                 // cerrar pestaña (como un navegador)
                    else -> finish()
                }
            }
        })
    }

    // =======================================================================
    // Pestañas
    // =======================================================================

    private fun restoreOrOpenTabs(state: Bundle?) {
        val urls = state?.getStringArrayList(STATE_TAB_URLS)
        if (urls.isNullOrEmpty()) {
            openNewTab(HOME_URL)
            return
        }
        urls.forEach { openNewTab(it) }
        val index = (state?.getInt(STATE_CURRENT_TAB, 0) ?: 0).coerceIn(0, tabs.lastIndex)
        switchToTab(index)
    }

    /** Crea una pestaña nueva, la muestra y (si [url] no es null) carga esa dirección. */
    private fun openNewTab(url: String?): WebView {
        val wv = WebView(this)
        configureWebView(wv)
        webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tabs.add(wv)
        switchToTab(tabs.lastIndex)
        if (url != null) wv.loadUrl(url)
        return wv
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        if (currentTab in tabs.indices && currentTab != index) {
            releaseAllKeys()              // suelta teclas en la pestaña que se abandona
            tabs[currentTab].onPause()
        }
        currentTab = index
        tabs.forEachIndexed { i, wv ->
            wv.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        webView.onResume()
        refreshChrome()
        webView.requestFocus()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {                // la última pestaña no se cierra: vuelve a la página de inicio
            tabs[0].loadUrl(HOME_URL)
            return
        }
        val closed = tabs.removeAt(index)
        destroyWebView(closed)
        currentTab = -1                      // fuerza la actualización completa en switchToTab
        switchToTab(minOf(index, tabs.lastIndex))
    }

    private fun destroyWebView(wv: WebView) {
        desktopScriptHandlers.remove(wv)?.remove()
        webContainer.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    private fun isCurrentTab(view: WebView?): Boolean =
        view != null && currentTab in tabs.indices && tabs[currentTab] === view

    /** Sincroniza barra de URL y barra de progreso con la pestaña activa. */
    private fun refreshChrome() {
        val wv = webView
        if (!urlInput.hasFocus()) urlInput.setText(wv.url ?: "")
        progressBar.progress = wv.progress
        progressBar.isVisible = wv.progress in 1..99
    }

    private fun showTabsDialog() {
        val labels = tabs.mapIndexed { i, wv ->
            val title = wv.title?.takeIf { it.isNotBlank() } ?: wv.url ?: getString(R.string.tab_blank)
            (if (i == currentTab) "● " else "○ ") + title
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs_title, tabs.size))
            .setItems(labels) { _, which -> switchToTab(which) }
            .setPositiveButton(R.string.tab_new) { _, _ -> openNewTab(HOME_URL) }
            .setNeutralButton(R.string.tab_close_current) { _, _ -> closeTab(currentTab) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // =======================================================================
    // WebView (se configura cada pestaña nueva)
    // =======================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true                       // imprescindible para los juegos
            domStorageEnabled = true                       // localStorage/sessionStorage (partidas guardadas)
            mediaPlaybackRequiresUserGesture = false       // audio/vídeo sin toque previo
            useWideViewPort = true                         // layout ancho tipo escritorio
            loadWithOverviewMode = true                    // ajusta la página al ancho de la pantalla
            allowFileAccess = false                        // seguridad: sin acceso a file://
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)                // permite target="_blank" / window.open (-> nueva pestaña)
            javaScriptCanOpenWindowsAutomatically = true   // los pop-ups sin toque del usuario se bloquean en onCreateWindow
        }

        wv.webViewClient = webClient
        wv.webChromeClient = chromeClient

        // Controlador de teclado: se inyecta ANTES de cualquier script de la página, en todos los frames.
        if (hasDocumentStartScript && gamepadScript.isNotEmpty()) {
            WebViewCompat.addDocumentStartJavaScript(wv, gamepadScript, setOf("*"))
        }

        applyZoomSettings(wv)
        applyDesktopMode(wv)
    }

    private val webClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val scheme = request?.url?.scheme ?: return false
            return scheme !in ALLOWED_SCHEMES // true = bloquear esquemas raros
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (isCurrentTab(view) && url != null && !urlInput.hasFocus()) urlInput.setText(url)
            // Plan B para WebViews antiguos sin document-start script (solo frame principal)
            if (view != null && !hasDocumentStartScript && gamepadScript.isNotEmpty()) {
                view.evaluateJavascript(gamepadScript, null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || hasDocumentStartScript) return
            if (gamepadScript.isNotEmpty()) view.evaluateJavascript(gamepadScript, null)
            if (desktopMode && desktopViewportScript.isNotEmpty()) {
                view.evaluateJavascript(desktopViewportScript, null)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (isCurrentTab(view) && request?.isForMainFrame == true) {
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
            if (!isCurrentTab(view)) return
            progressBar.progress = newProgress
            progressBar.isVisible = newProgress < 100
        }

        // Enlaces con target="_blank" / window.open -> nueva pestaña (solo si hubo toque del usuario)
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (!isUserGesture || resultMsg == null) return false // bloquea pop-ups automáticos
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val newWebView = openNewTab(null)
            transport.webView = newWebView
            resultMsg.sendToTarget()
            return true
        }

        // window.close() desde la página -> cierra esa pestaña
        override fun onCloseWindow(window: WebView?) {
            val closing = window ?: return
            val index = tabs.indexOf(closing)
            if (index >= 0) closeTab(index)
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

    // =======================================================================
    // Zoom y modo computadora
    // =======================================================================

    /** Zoom con los dedos permitido solo si no está bloqueado. */
    private fun applyZoomSettings(wv: WebView) {
        wv.settings.apply {
            setSupportZoom(!zoomLocked)
            builtInZoomControls = !zoomLocked
            displayZoomControls = false // sin los botones +/- flotantes de Android
        }
    }

    private fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        prefs.edit().putBoolean(PREF_ZOOM_LOCKED, zoomLocked).apply()
        tabs.forEach { applyZoomSettings(it) }
        toast(if (zoomLocked) R.string.toast_zoom_locked else R.string.toast_zoom_unlocked)
    }

    private fun zoomPage(factor: Float) {
        if (zoomLocked) {
            toast(R.string.toast_unlock_zoom_first)
            return
        }
        webView.zoomBy(factor)
    }

    /** User-Agent de PC + (si el WebView lo soporta) viewport de escritorio forzado. */
    private fun applyDesktopMode(wv: WebView) {
        wv.settings.userAgentString = if (desktopMode) desktopUserAgent else defaultUserAgent

        desktopScriptHandlers.remove(wv)?.remove()
        if (desktopMode && hasDocumentStartScript && desktopViewportScript.isNotEmpty()) {
            desktopScriptHandlers[wv] =
                WebViewCompat.addDocumentStartJavaScript(wv, desktopViewportScript, setOf("*"))
        }
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        prefs.edit().putBoolean(PREF_DESKTOP_MODE, desktopMode).apply()
        tabs.forEach { applyDesktopMode(it) }
        webView.reload() // recarga la pestaña activa con el nuevo User-Agent
        toast(if (desktopMode) R.string.toast_desktop_on else R.string.toast_desktop_off)
    }

    /** UA de Chrome de escritorio (Linux) usando la versión real de Chrome del WebView. */
    private fun buildDesktopUserAgent(): String {
        val chromeVersion = Regex("Chrome/([\\d.]+)")
            .find(defaultUserAgent)?.groupValues?.get(1) ?: "120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeVersion Safari/537.36"
    }

    // =======================================================================
    // Menú (⋮ en la barra superior, ☰ en modo juego)
    // =======================================================================

    private fun showAppMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(
            Menu.NONE, MENU_PLAY, 0,
            getString(if (playMode) R.string.menu_exit_play else R.string.menu_play)
        )
        menu.add(Menu.NONE, MENU_NEW_TAB, 1, getString(R.string.menu_new_tab))
        menu.add(Menu.NONE, MENU_TABS, 2, getString(R.string.menu_tabs, tabs.size))
        menu.add(Menu.NONE, MENU_ZOOM_IN, 3, getString(R.string.menu_zoom_in))
        menu.add(Menu.NONE, MENU_ZOOM_OUT, 4, getString(R.string.menu_zoom_out))
        menu.add(Menu.NONE, MENU_ZOOM_LOCK, 5, getString(R.string.menu_zoom_lock)).apply {
            isCheckable = true
            isChecked = zoomLocked
        }
        menu.add(Menu.NONE, MENU_DESKTOP, 6, getString(R.string.menu_desktop)).apply {
            isCheckable = true
            isChecked = desktopMode
        }
        menu.add(Menu.NONE, MENU_RELOAD, 7, getString(R.string.menu_reload))

        popup.setOnMenuItemClickListener { item -> handleMenuAction(item.itemId) }
        popup.show()
    }

    private fun handleMenuAction(id: Int): Boolean {
        when (id) {
            MENU_PLAY -> setPlayMode(!playMode)
            MENU_NEW_TAB -> openNewTab(HOME_URL)
            MENU_TABS -> showTabsDialog()
            MENU_ZOOM_IN -> zoomPage(ZOOM_STEP_IN)
            MENU_ZOOM_OUT -> zoomPage(ZOOM_STEP_OUT)
            MENU_ZOOM_LOCK -> toggleZoomLock()
            MENU_DESKTOP -> toggleDesktopMode()
            MENU_RELOAD -> webView.reload()
            else -> return false
        }
        return true
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
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

        applyImmersiveMode(enabled)
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        hideKeyboard()
        webView.requestFocus()
    }

    /** Oculta/muestra barra de estado y de navegación (deslizar desde el borde las muestra un instante). */
    private fun applyImmersiveMode(enabled: Boolean) {
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
        return true // consumimos el gesto: no se propaga a la página
    }

    /** action: "down" | "up". keyName viene de nuestros propios tags XML (solo letras/números). */
    private fun sendKey(action: String, keyName: String) {
        if (currentTab !in tabs.indices) return
        webView.evaluateJavascript("window.__vgp&&window.__vgp.$action('$keyName')", null)
    }

    /** Suelta todas las teclas en la página activa y apaga el estado visual de los botones. */
    private fun releaseAllKeys() {
        forEachKeyButton(gamepadOverlay) { button, _ -> button.isPressed = false }
        if (currentTab in tabs.indices) {
            webView.evaluateJavascript("window.__vgp&&window.__vgp.reset()", null)
        }
    }
}
