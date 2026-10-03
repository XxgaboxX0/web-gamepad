package com.example.webgamepad

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var gamepadOverlay: View
    private lateinit var btnMenu: View
    private lateinit var btnPlay: View
    private lateinit var btnGo: View
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupUi()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUi() {
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)
        gamepadOverlay = findViewById(R.id.gamepadOverlay)
        btnMenu = findViewById(R.id.btnMenu)
        btnPlay = findViewById(R.id.btnPlay)
        btnGo = findViewById(R.id.btnGo)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadsImagesAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    urlInput.setText(url)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                findViewById<View>(R.id.customViewContainer).apply {
                    // Se usa un cast seguro o contenedor directo
                    visibility = View.VISIBLE
                }
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                findViewById<View>(R.id.customViewContainer).visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
            }
        }

        btnGo.setOnClickListener {
            var url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                webView.loadUrl(url)
            }
        }

        btnPlay.setOnClickListener {
            if (gamepadOverlay.visibility == View.VISIBLE) {
                gamepadOverlay.visibility = View.GONE
                btnMenu.visibility = View.GONE
            } else {
                gamepadOverlay.visibility = View.VISIBLE
                btnMenu.visibility = View.VISIBLE
            }
        }

        btnMenu.setOnClickListener {
            gamepadOverlay.visibility = View.GONE
            btnMenu.visibility = View.GONE
        }

        // Página de inicio por defecto para probar el navegador
        webView.loadUrl("https://html5games.com")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
