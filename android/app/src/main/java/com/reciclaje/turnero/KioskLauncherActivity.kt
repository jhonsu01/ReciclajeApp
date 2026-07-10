package com.reciclaje.turnero

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Punto de entrada del APK de kiosko (y del auto-arranque al encender el TV).
 * - Si el televisor ya está vinculado (token de kiosko guardado) entra directo
 *   al turnero, sin pedir nada.
 * - Si no, muestra una pantalla mínima para vincularlo UNA sola vez: detecta el
 *   servidor automáticamente y pide el PIN de sesión de kiosko del panel.
 */
class KioskLauncherActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("reciclaje", Context.MODE_PRIVATE)

        // Ya vinculado -> al turnero directamente (sin UI, sin PIN)
        if (estaVinculado()) {
            irAlTurnero()
            return
        }

        setContentView(R.layout.activity_kiosk_launcher)
        findViewById<Button>(R.id.btnVincular).setOnClickListener { vincular() }
        findViewById<Button>(R.id.btnBuscar).setOnClickListener { buscarServidor() }
        buscarServidor()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun estaVinculado() =
        prefs.getString("token", null) != null &&
        prefs.getString("rol", null) == "kiosko" &&
        prefs.getString("host", null) != null

    private fun irAlTurnero() {
        startActivity(Intent(this, KioskActivity::class.java))
        finish()
    }

    private fun buscarServidor() {
        val txt = findViewById<TextView>(R.id.txtBusqueda)
        txt.text = "🔍 Buscando servidor en la red…"
        io.execute {
            val servidor = DiscoveryClient.buscarServidor(this)
            ui.post {
                if (isFinishing) return@post
                if (servidor != null) {
                    findViewById<EditText>(R.id.inputServidor).setText(servidor.optString("ip"))
                    findViewById<EditText>(R.id.inputPuerto).setText(servidor.optInt("puerto", 3000).toString())
                    txt.text = "✅ Encontrado: ${servidor.optString("nombre")} (${servidor.optString("ip")})"
                } else {
                    txt.text = "⚠ No se encontró el servidor. Verifica que el PC con Reciclaje Turnero " +
                        "esté encendido en la misma red WiFi, o escribe la IP a mano."
                }
            }
        }
    }

    private fun vincular() {
        val host = findViewById<EditText>(R.id.inputServidor).text.toString().trim()
        val puerto = findViewById<EditText>(R.id.inputPuerto).text.toString().toIntOrNull() ?: 3000
        val pin = findViewById<EditText>(R.id.inputPin).text.toString().trim()
        if (host.isEmpty()) { toast("No hay servidor. Usa la búsqueda o escribe la IP."); return }
        if (pin.isEmpty()) { toast("Ingresa el PIN de sesión de Kiosko que muestra el panel del PC"); return }

        findViewById<TextView>(R.id.txtBusqueda).text = "Vinculando…"
        io.execute {
            try {
                val resultado = ApiClient(host, puerto)
                    .emparejar("kiosko", pin, "TV ${Build.MANUFACTURER} ${Build.MODEL}")
                val token = resultado.getString("token")
                ui.post {
                    prefs.edit()
                        .putString("host", host).putInt("puerto", puerto)
                        .putString("token", token).putString("rol", "kiosko").apply()
                    toast("Televisor vinculado ✓")
                    irAlTurnero()
                }
            } catch (e: ApiException) {
                ui.post {
                    findViewById<TextView>(R.id.txtBusqueda).text = "❌ ${e.message}"
                    toast(e.message ?: "PIN de kiosko incorrecto")
                }
            } catch (e: Exception) {
                ui.post {
                    findViewById<TextView>(R.id.txtBusqueda).text = "❌ Sin conexión con $host:$puerto"
                    toast("No se pudo conectar. Verifica la red WiFi.")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
