package com.reciclaje.turnero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val tiposDoc = listOf("CC", "CE", "NIT", "PASAPORTE", "TI")

    private lateinit var prefs: android.content.SharedPreferences
    private var api: ApiClient? = null
    private var materiales: JSONArray = JSONArray()
    private var turnoActivo: Long = -1
    private var estadoAnterior: String? = null
    private var polling: Runnable? = null
    private var reintento: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("reciclaje", Context.MODE_PRIVATE)

        findViewById<Spinner>(R.id.spinnerTipoDoc).adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tiposDoc)

        findViewById<Button>(R.id.btnConectar).setOnClickListener { conectar() }
        findViewById<Button>(R.id.btnSolicitar).setOnClickListener { solicitarTurno() }
        findViewById<Button>(R.id.btnNuevoTurno).setOnClickListener {
            turnoActivo = -1
            prefs.edit().remove("turno_id").apply()
            mostrarVista(R.id.vistaRegistro)
            cargarMateriales()
        }
        findViewById<Button>(R.id.btnCambiarServidor).setOnClickListener {
            mostrarVista(R.id.vistaConfig)
        }

        restaurarSesion()
    }

    override fun onDestroy() {
        super.onDestroy()
        polling?.let { ui.removeCallbacks(it) }
        reintento?.let { ui.removeCallbacks(it) }
        io.shutdownNow()
    }

    // ---------- Navegación ----------
    private fun mostrarVista(id: Int) {
        for (v in listOf(R.id.vistaConfig, R.id.vistaRegistro, R.id.vistaTurno)) {
            findViewById<View>(v).visibility = if (v == id) View.VISIBLE else View.GONE
        }
    }

    private fun estadoConexion(texto: String) {
        findViewById<TextView>(R.id.txtEstadoConexion).text = texto
    }

    private fun restaurarSesion() {
        val host = prefs.getString("host", null)
        if (host == null) {
            mostrarVista(R.id.vistaConfig)
            return
        }
        api = ApiClient(host, prefs.getInt("puerto", 3000), prefs.getString("pin", "") ?: "")
        findViewById<EditText>(R.id.inputServidor).setText(host)
        findViewById<EditText>(R.id.inputPuerto).setText(prefs.getInt("puerto", 3000).toString())
        estadoConexion("Servidor: $host:${prefs.getInt("puerto", 3000)}")
        val turnoGuardado = prefs.getLong("turno_id", -1)
        if (turnoGuardado > 0) {
            turnoActivo = turnoGuardado
            mostrarVista(R.id.vistaTurno)
            iniciarPolling()
        } else {
            mostrarVista(R.id.vistaRegistro)
            cargarMateriales()
        }
    }

    // ---------- Vista 1: Configuración ----------
    private fun conectar() {
        val host = findViewById<EditText>(R.id.inputServidor).text.toString().trim()
        val puerto = findViewById<EditText>(R.id.inputPuerto).text.toString().toIntOrNull() ?: 3000
        val pin = findViewById<EditText>(R.id.inputPin).text.toString().trim()
        if (host.isEmpty() || pin.isEmpty()) {
            toast("Ingresa la IP del servidor y el PIN")
            return
        }
        val cliente = ApiClient(host, puerto, pin)
        estadoConexion("Conectando…")
        io.execute {
            try {
                val info = cliente.ping()
                ui.post {
                    api = cliente
                    prefs.edit().putString("host", host).putInt("puerto", puerto)
                        .putString("pin", pin).apply()
                    estadoConexion("Conectado a ${info.optString("nombre", "servidor")} ($host:$puerto)")
                    toast("Emparejamiento exitoso ✓")
                    mostrarVista(R.id.vistaRegistro)
                    cargarMateriales()
                }
            } catch (e: ApiException) {
                ui.post { estadoConexion("Error: ${e.message}"); toast(e.message ?: "PIN inválido") }
            } catch (e: Exception) {
                ui.post {
                    estadoConexion("Sin conexión con $host:$puerto")
                    toast("No se pudo conectar. Verifica la red WiFi y la IP.")
                }
            }
        }
    }

    // ---------- Vista 2: Registro ----------
    private fun cargarMateriales() {
        val cliente = api ?: return
        io.execute {
            try {
                val mats = cliente.materiales()
                ui.post {
                    materiales = mats
                    val nombres = mutableListOf(getString(R.string.sin_material))
                    for (i in 0 until mats.length()) {
                        val m = mats.getJSONObject(i)
                        nombres.add("${m.getString("familia")} / ${m.getString("subcategoria")} (${m.getString("presentacion")})")
                    }
                    findViewById<Spinner>(R.id.spinnerMaterial).adapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nombres)
                }
            } catch (e: Exception) { /* el spinner queda vacío; el material es opcional */ }
        }
    }

    private fun solicitarTurno() {
        val cliente = api ?: return
        val tipoDoc = findViewById<Spinner>(R.id.spinnerTipoDoc).selectedItem.toString()
        val numeroDoc = findViewById<EditText>(R.id.inputDocumento).text.toString().trim()
        if (numeroDoc.length < 3) {
            toast("Ingresa un número de documento válido")
            return
        }
        val posMaterial = findViewById<Spinner>(R.id.spinnerMaterial).selectedItemPosition
        val materialId = if (posMaterial > 0 && posMaterial <= materiales.length())
            materiales.getJSONObject(posMaterial - 1).getLong("id") else null

        // Offline-first: si falla, se encola y se reintenta automáticamente
        prefs.edit()
            .putString("pendiente", JSONObject()
                .put("tipo", tipoDoc).put("doc", numeroDoc)
                .put("material", materialId ?: JSONObject.NULL).toString())
            .apply()
        estadoConexion("Solicitando turno…")
        intentarEnvioPendiente(cliente)
    }

    private fun intentarEnvioPendiente(cliente: ApiClient) {
        val pendiente = prefs.getString("pendiente", null) ?: return
        io.execute {
            try {
                val p = JSONObject(pendiente)
                val materialId = if (p.isNull("material")) null else p.getLong("material")
                val turno = cliente.crearTurno(p.getString("tipo"), p.getString("doc"), materialId)
                ui.post {
                    prefs.edit().remove("pendiente")
                        .putLong("turno_id", turno.getLong("id")).apply()
                    turnoActivo = turno.getLong("id")
                    mostrarVista(R.id.vistaTurno)
                    pintarTurno(turno)
                    iniciarPolling()
                }
            } catch (e: ApiException) {
                ui.post {
                    prefs.edit().remove("pendiente").apply()
                    estadoConexion("Error: ${e.message}")
                    toast(e.message ?: "Solicitud rechazada")
                }
            } catch (e: Exception) {
                // Sin red: reintentar en 5 s (cola offline)
                ui.post {
                    estadoConexion("Sin conexión — reintentando en 5 s…")
                    reintento = Runnable { intentarEnvioPendiente(cliente) }
                    ui.postDelayed(reintento!!, 5000)
                }
            }
        }
    }

    // ---------- Vista 3: Turno activo ----------
    private fun iniciarPolling() {
        polling?.let { ui.removeCallbacks(it) }
        val tarea = object : Runnable {
            override fun run() {
                consultarTurno()
                ui.postDelayed(this, 3000)
            }
        }
        polling = tarea
        ui.post(tarea)
    }

    private fun consultarTurno() {
        val cliente = api ?: return
        if (turnoActivo <= 0) return
        io.execute {
            try {
                val t = cliente.turno(turnoActivo)
                ui.post { pintarTurno(t) }
            } catch (e: Exception) {
                ui.post { estadoConexion("Sin conexión — reintentando…") }
            }
        }
    }

    private fun pintarTurno(t: JSONObject) {
        estadoConexion("Servidor: ${prefs.getString("host", "")}:${prefs.getInt("puerto", 3000)}")
        val numero = t.getInt("numero")
        val estado = t.getString("estado")
        val modulo = if (t.isNull("modulo_asignado")) null else t.getInt("modulo_asignado")

        findViewById<TextView>(R.id.txtNumeroTurno).text = String.format("%03d", numero)
        val txtEstado = findViewById<TextView>(R.id.txtEstadoTurno)
        val txtModulo = findViewById<TextView>(R.id.txtModulo)
        val btnNuevo = findViewById<Button>(R.id.btnNuevoTurno)
        val txtRecibo = findViewById<TextView>(R.id.txtRecibo)

        when (estado) {
            "ESPERANDO" -> {
                txtEstado.text = "EN ESPERA"
                txtEstado.setTextColor(getColor(R.color.verde))
                txtModulo.text = "Espera a ser llamado"
            }
            "LLAMADO" -> {
                txtEstado.text = "¡ES TU TURNO!"
                txtEstado.setTextColor(getColor(R.color.amarillo))
                txtModulo.text = "Acércate al módulo ${modulo ?: "-"}"
                if (estadoAnterior != "LLAMADO") vibrar()
            }
            "EN_PESAJE" -> {
                txtEstado.text = "EN PESAJE"
                txtEstado.setTextColor(getColor(R.color.amarillo))
                txtModulo.text = "Módulo ${modulo ?: "-"}"
            }
            "FINALIZADO" -> {
                txtEstado.text = "FINALIZADO ✓"
                txtEstado.setTextColor(getColor(R.color.verde))
                txtModulo.text = ""
                btnNuevo.visibility = View.VISIBLE
                if (estadoAnterior != "FINALIZADO") cargarRecibo()
                polling?.let { ui.removeCallbacks(it) }
            }
            "NO_PRESENTADO" -> {
                txtEstado.text = "NO PRESENTADO"
                txtEstado.setTextColor(getColor(R.color.rojo))
                txtModulo.text = "Solicita un nuevo turno"
                btnNuevo.visibility = View.VISIBLE
                txtRecibo.visibility = View.GONE
                polling?.let { ui.removeCallbacks(it) }
            }
        }
        estadoAnterior = estado

        findViewById<TextView>(R.id.txtPinTurno).text = "PIN del turno: ${t.getString("codigo_pin")}"
        val qr = t.getString("qr_code")
        findViewById<ImageView>(R.id.imgQr).setImageBitmap(generarQr(qr))
    }

    private fun cargarRecibo() {
        val cliente = api ?: return
        io.execute {
            try {
                val r = cliente.recibo(turnoActivo) ?: return@execute
                val sb = StringBuilder("RECIBO\n")
                val detalle = r.getJSONArray("detalle")
                for (i in 0 until detalle.length()) {
                    val d = detalle.getJSONObject(i)
                    sb.append("• ${d.getString("material")}: ${d.getDouble("kg")} kg → $${formato(d.getLong("total"))}\n")
                }
                sb.append("\nTOTAL: $${formato(r.getLong("total"))}")
                ui.post {
                    val txt = findViewById<TextView>(R.id.txtRecibo)
                    txt.text = sb.toString()
                    txt.visibility = View.VISIBLE
                }
            } catch (e: Exception) { /* recibo aún no disponible */ }
        }
    }

    // ---------- Utilidades ----------
    private fun generarQr(contenido: String): Bitmap {
        val matriz = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, 440, 440)
        val bmp = Bitmap.createBitmap(440, 440, Bitmap.Config.RGB_565)
        for (x in 0 until 440) {
            for (y in 0 until 440) {
                bmp.setPixel(x, y, if (matriz[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun vibrar() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(1200)
        }
    }

    private fun formato(n: Long): String = String.format("%,d", n).replace(',', '.')

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
