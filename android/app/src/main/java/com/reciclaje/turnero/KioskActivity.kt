package com.reciclaje.turnero

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Modo Kiosko: muestra la pantalla del turnero (display.html) a pantalla completa.
 * Pensado para TVs Android. Verifica periódicamente que el acceso siga vigente:
 * si el administrador revoca este dispositivo, vuelve a pedir la vinculación.
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: android.content.SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var host: String? = null
    private var puerto: Int = 3000
    private var chequeoAcceso: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ocultarBarras()

        prefs = getSharedPreferences("reciclaje", Context.MODE_PRIVATE)
        host = prefs.getString("host", null)
        puerto = prefs.getInt("puerto", 3000)
        val h = host
        if (h == null) { volverAVincular(); return }

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(0xFF0D0D0D.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    // Servidor caído o red intermitente: reintentar solo
                    view.postDelayed({ view.loadUrl("http://$h:$puerto/display.html") }, 5000)
                }
            }
        }
        setContentView(web)
        web.loadUrl("http://$h:$puerto/display.html")

        iniciarChequeoAcceso()
    }

    override fun onResume() {
        super.onResume()
        ocultarBarras()
    }

    override fun onDestroy() {
        super.onDestroy()
        chequeoAcceso?.let { ui.removeCallbacks(it) }
        io.shutdownNow()
    }

    /**
     * Comprueba cada 60 s que el token siga vigente. Si el servidor responde y el
     * rol ya no es kiosko/admin, el acceso fue revocado -> volver a vincular.
     * Los errores de red NO desvinculan (tolerante a cortes/apagados de WiFi).
     */
    private fun iniciarChequeoAcceso() {
        val token = prefs.getString("token", null) ?: return
        val h = host ?: return
        val tarea = object : Runnable {
            override fun run() {
                io.execute {
                    try {
                        val info = ApiClient(h, puerto, token).ping()
                        val rol = info.optString("rol")
                        if (rol != "kiosko" && rol != "admin") {
                            ui.post { accesoRevocado() }
                        }
                    } catch (e: Exception) { /* sin red: seguir mostrando */ }
                }
                ui.postDelayed(this, 60_000)
            }
        }
        chequeoAcceso = tarea
        ui.postDelayed(tarea, 60_000)
    }

    private fun accesoRevocado() {
        chequeoAcceso?.let { ui.removeCallbacks(it) }
        prefs.edit().remove("token").apply()
        AlertDialog.Builder(this)
            .setTitle("Acceso revocado")
            .setMessage("El administrador revocó este televisor. Vuelve a vincularlo con el nuevo PIN de kiosko.")
            .setCancelable(false)
            .setPositiveButton("Vincular de nuevo") { _, _ -> volverAVincular() }
            .show()
    }

    private fun volverAVincular() {
        startActivity(Intent(this, KioskLauncherActivity::class.java))
        finish()
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
        if (BuildConfig.KIOSKO_APP) {
            // APK dedicado de TV: no se sale con Atrás y NO se pierde la vinculación
            AlertDialog.Builder(this)
                .setTitle("Modo TV")
                .setMessage("Esta pantalla muestra el turnero. ¿Cerrar la aplicación? El televisor seguirá vinculado.")
                .setPositiveButton("Cerrar") { _, _ -> finishAffinity() }
                .setNegativeButton("Seguir mostrando", null)
                .show()
        } else {
            // App multirol: Atrás regresa a la selección de rol
            AlertDialog.Builder(this)
                .setTitle("Modo Kiosko")
                .setMessage("¿Salir del modo kiosko y volver a la selección de rol?")
                .setPositiveButton("Salir") { _, _ ->
                    prefs.edit().remove("rol").apply()
                    finish()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
}
