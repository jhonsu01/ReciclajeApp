package com.reciclaje.turnero

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Modo Kiosko: muestra la pantalla del turnero (display.html) a pantalla completa.
 * Pensado para TVs Android: sin configuración, la app encuentra el servidor sola.
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ocultarBarras()

        val prefs = getSharedPreferences("reciclaje", Context.MODE_PRIVATE)
        val host = prefs.getString("host", null)
        val puerto = prefs.getInt("puerto", 3000)
        if (host == null) { finish(); return }

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF0D0D0D.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    // Servidor caído o red intermitente: reintentar solo
                    view.postDelayed({ view.loadUrl("http://$host:$puerto/display.html") }, 5000)
                }
            }
        }
        setContentView(web)
        web.loadUrl("http://$host:$puerto/display.html")
    }

    override fun onResume() {
        super.onResume()
        ocultarBarras()
    }

    private fun ocultarBarras() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Modo Kiosko")
            .setMessage("¿Salir del modo kiosko y volver a la selección de rol?")
            .setPositiveButton("Salir") { _, _ ->
                getSharedPreferences("reciclaje", Context.MODE_PRIVATE)
                    .edit().remove("rol").apply()
                finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
