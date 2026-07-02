package com.reciclaje.turnero

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var rolElegido: String? = null

    // Cliente
    private var turnoActivo: Long = -1
    private var estadoAnterior: String? = null

    // Pesaje
    private var turnoPesajeId: Long = -1

    private var tareaPeriodica: Runnable? = null
    private var reintento: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("reciclaje", Context.MODE_PRIVATE)

        findViewById<Spinner>(R.id.spinnerTipoDoc).adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tiposDoc)

        // Selección de rol
        findViewById<Button>(R.id.btnRolCliente).setOnClickListener { elegirRol("cliente") }
        findViewById<Button>(R.id.btnRolPesaje).setOnClickListener { elegirRol("pesaje") }
        findViewById<Button>(R.id.btnRolAdmin).setOnClickListener { elegirRol("admin") }
        findViewById<Button>(R.id.btnRolKiosko).setOnClickListener { elegirRol("kiosko") }

        // Conexión
        findViewById<Button>(R.id.btnConectar).setOnClickListener { conectar() }
        findViewById<Button>(R.id.btnBuscar).setOnClickListener { buscarServidor() }
        findViewById<Button>(R.id.btnVolverRol).setOnClickListener { mostrarVista(R.id.vistaRol) }

        // Cliente
        findViewById<Button>(R.id.btnSolicitar).setOnClickListener { solicitarTurno() }
        findViewById<Button>(R.id.btnNuevoTurno).setOnClickListener {
            turnoActivo = -1
            prefs.edit().remove("turno_id").apply()
            mostrarVista(R.id.vistaRegistro)
            cargarMateriales(R.id.spinnerMaterial, conVacio = true)
        }

        // Pesaje
        findViewById<Button>(R.id.btnAgregarPesaje).setOnClickListener { registrarPesaje() }
        findViewById<Button>(R.id.btnFinalizarPesaje).setOnClickListener { finalizarPesaje() }
        findViewById<Button>(R.id.btnVolverLista).setOnClickListener { entrarPesaje() }
        findViewById<EditText>(R.id.inputKg).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { actualizarValorEstimado() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Cambiar rol / servidor
        val reset = View.OnClickListener { cambiarRol() }
        findViewById<Button>(R.id.btnCambiarServidor).setOnClickListener(reset)
        findViewById<Button>(R.id.btnCambiarRolPesaje).setOnClickListener(reset)
        findViewById<Button>(R.id.btnCambiarRolAdmin).setOnClickListener(reset)

        restaurarSesion()
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerPeriodica()
        reintento?.let { ui.removeCallbacks(it) }
        io.shutdownNow()
    }

    override fun onResume() {
        super.onResume()
        // Al volver del kiosko con el rol borrado, regresar a la selección
        if (rolElegido == "kiosko" && prefs.getString("rol", null) == null) {
            mostrarVista(R.id.vistaRol)
            rolElegido = null
        }
    }

    // ---------- Navegación ----------
    private fun mostrarVista(id: Int) {
        detenerPeriodica()
        val vistas = listOf(
            R.id.vistaRol, R.id.vistaConfig, R.id.vistaRegistro, R.id.vistaTurno,
            R.id.vistaPesaje, R.id.vistaPesajeDetalle, R.id.vistaAdmin,
        )
        for (v in vistas) findViewById<View>(v).visibility = if (v == id) View.VISIBLE else View.GONE
    }

    private fun estadoConexion(texto: String) {
        findViewById<TextView>(R.id.txtEstadoConexion).text = texto
    }

    private fun detenerPeriodica() {
        tareaPeriodica?.let { ui.removeCallbacks(it) }
        tareaPeriodica = null
    }

    private fun iniciarPeriodica(intervaloMs: Long, accion: () -> Unit) {
        detenerPeriodica()
        val t = object : Runnable {
            override fun run() {
                accion()
                ui.postDelayed(this, intervaloMs)
            }
        }
        tareaPeriodica = t
        ui.post(t)
    }

    private fun cambiarRol() {
        prefs.edit().remove("rol").remove("turno_id").apply()
        rolElegido = null
        mostrarVista(R.id.vistaRol)
    }

    private fun restaurarSesion() {
        val rol = prefs.getString("rol", null)
        val host = prefs.getString("host", null)
        if (rol == null || host == null) {
            mostrarVista(R.id.vistaRol)
            return
        }
        rolElegido = rol
        api = ApiClient(host, prefs.getInt("puerto", 3000), prefs.getString("pin", "") ?: "")
        estadoConexion("Servidor: $host:${prefs.getInt("puerto", 3000)} · rol: $rol")
        when (rol) {
            "kiosko" -> { startActivity(Intent(this, KioskActivity::class.java)) }
            "cliente" -> {
                val turnoGuardado = prefs.getLong("turno_id", -1)
                if (turnoGuardado > 0) {
                    turnoActivo = turnoGuardado
                    mostrarVista(R.id.vistaTurno)
                    iniciarPeriodica(3000) { consultarTurno() }
                } else {
                    mostrarVista(R.id.vistaRegistro)
                    cargarMateriales(R.id.spinnerMaterial, conVacio = true)
                }
            }
            "pesaje" -> entrarPesaje()
            "admin" -> entrarAdmin()
        }
    }

    // ---------- Vista 0: Rol ----------
    private fun elegirRol(rol: String) {
        rolElegido = rol
        mostrarVista(R.id.vistaConfig)
        findViewById<EditText>(R.id.inputPin).visibility =
            if (rol == "kiosko") View.GONE else View.VISIBLE
        findViewById<EditText>(R.id.inputPin).hint = when (rol) {
            "pesaje" -> "PIN de pesaje"
            "admin" -> "PIN de administrador"
            else -> "PIN de cliente"
        }
        buscarServidor()
    }

    // ---------- Vista 1: Conexión ----------
    private fun buscarServidor() {
        val txt = findViewById<TextView>(R.id.txtBusqueda)
        txt.text = "🔍 Buscando servidor en la red…"
        io.execute {
            val servidor = DiscoveryClient.buscarServidor(this)
            ui.post {
                if (servidor != null) {
                    findViewById<EditText>(R.id.inputServidor).setText(servidor.optString("ip"))
                    findViewById<EditText>(R.id.inputPuerto).setText(servidor.optInt("puerto", 3000).toString())
                    txt.text = "✅ Encontrado: ${servidor.optString("nombre")} (${servidor.optString("ip")})"
                    // El kiosko no necesita PIN: conectar de una vez
                    if (rolElegido == "kiosko") conectar()
                } else {
                    txt.text = "⚠ No se encontró el servidor automáticamente. " +
                        "Verifica que el equipo con Reciclaje Turnero esté encendido en la misma red WiFi, " +
                        "o ingresa la IP manualmente."
                }
            }
        }
    }

    private fun conectar() {
        val host = findViewById<EditText>(R.id.inputServidor).text.toString().trim()
        val puerto = findViewById<EditText>(R.id.inputPuerto).text.toString().toIntOrNull() ?: 3000
        val pin = findViewById<EditText>(R.id.inputPin).text.toString().trim()
        val rol = rolElegido ?: return
        if (host.isEmpty()) { toast("No hay servidor. Usa la búsqueda o ingresa la IP."); return }
        if (rol != "kiosko" && pin.isEmpty()) { toast("Ingresa el PIN de tu rol"); return }

        val cliente = ApiClient(host, puerto, pin)
        estadoConexion("Conectando…")
        io.execute {
            try {
                val info = cliente.ping()
                val rolServidor = info.optString("rol", "kiosko")
                val autorizado = when (rol) {
                    "kiosko" -> true
                    "admin" -> rolServidor == "admin"
                    "pesaje" -> rolServidor == "pesaje" || rolServidor == "admin"
                    else -> rolServidor != "kiosko"
                }
                ui.post {
                    if (!autorizado) {
                        estadoConexion("PIN incorrecto para el rol seleccionado")
                        toast("Ese PIN no corresponde al rol elegido")
                        return@post
                    }
                    api = cliente
                    prefs.edit().putString("host", host).putInt("puerto", puerto)
                        .putString("pin", pin).putString("rol", rol).apply()
                    estadoConexion("Conectado a ${info.optString("nombre", "servidor")} · rol: $rol")
                    toast("Emparejamiento exitoso ✓")
                    when (rol) {
                        "kiosko" -> startActivity(Intent(this, KioskActivity::class.java))
                        "cliente" -> {
                            mostrarVista(R.id.vistaRegistro)
                            cargarMateriales(R.id.spinnerMaterial, conVacio = true)
                        }
                        "pesaje" -> entrarPesaje()
                        "admin" -> entrarAdmin()
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    estadoConexion("Sin conexión con $host:$puerto")
                    toast("No se pudo conectar. Verifica la red WiFi.")
                }
            }
        }
    }

    // ---------- Materiales (compartido) ----------
    private fun cargarMateriales(spinnerId: Int, conVacio: Boolean) {
        val cliente = api ?: return
        io.execute {
            try {
                val mats = cliente.materiales()
                ui.post {
                    materiales = mats
                    val nombres = mutableListOf<String>()
                    if (conVacio) nombres.add(getString(R.string.sin_material))
                    for (i in 0 until mats.length()) {
                        val m = mats.getJSONObject(i)
                        nombres.add("${m.getString("familia")} / ${m.getString("subcategoria")} (${m.getString("presentacion")})")
                    }
                    findViewById<Spinner>(spinnerId).adapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nombres)
                }
            } catch (e: Exception) { /* material es opcional */ }
        }
    }

    // ---------- ROL CLIENTE ----------
    private fun solicitarTurno() {
        val cliente = api ?: return
        val tipoDoc = findViewById<Spinner>(R.id.spinnerTipoDoc).selectedItem.toString()
        val numeroDoc = findViewById<EditText>(R.id.inputDocumento).text.toString().trim()
        if (numeroDoc.length < 3) { toast("Ingresa un número de documento válido"); return }
        val pos = findViewById<Spinner>(R.id.spinnerMaterial).selectedItemPosition
        val materialId = if (pos > 0 && pos <= materiales.length())
            materiales.getJSONObject(pos - 1).getLong("id") else null

        prefs.edit().putString("pendiente", JSONObject()
            .put("tipo", tipoDoc).put("doc", numeroDoc)
            .put("material", materialId ?: JSONObject.NULL).toString()).apply()
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
                    prefs.edit().remove("pendiente").putLong("turno_id", turno.getLong("id")).apply()
                    turnoActivo = turno.getLong("id")
                    mostrarVista(R.id.vistaTurno)
                    pintarTurno(turno)
                    iniciarPeriodica(3000) { consultarTurno() }
                }
            } catch (e: ApiException) {
                ui.post {
                    prefs.edit().remove("pendiente").apply()
                    estadoConexion("Error: ${e.message}")
                    toast(e.message ?: "Solicitud rechazada")
                }
            } catch (e: Exception) {
                ui.post {
                    estadoConexion("Sin conexión — reintentando en 5 s…")
                    reintento = Runnable { intentarEnvioPendiente(cliente) }
                    ui.postDelayed(reintento!!, 5000)
                }
            }
        }
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
        estadoConexion("Servidor: ${prefs.getString("host", "")}:${prefs.getInt("puerto", 3000)} · rol: cliente")
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
                if (estadoAnterior != "FINALIZADO") cargarReciboCliente()
                detenerPeriodica()
            }
            "NO_PRESENTADO" -> {
                txtEstado.text = "NO PRESENTADO"
                txtEstado.setTextColor(getColor(R.color.rojo))
                txtModulo.text = "Solicita un nuevo turno"
                btnNuevo.visibility = View.VISIBLE
                txtRecibo.visibility = View.GONE
                detenerPeriodica()
            }
        }
        estadoAnterior = estado

        findViewById<TextView>(R.id.txtPinTurno).text = "PIN del turno: ${t.getString("codigo_pin")}"
        findViewById<ImageView>(R.id.imgQr).setImageBitmap(generarQr(t.getString("qr_code")))
    }

    private fun cargarReciboCliente() {
        val cliente = api ?: return
        io.execute {
            try {
                val r = cliente.recibo(turnoActivo) ?: return@execute
                ui.post {
                    val txt = findViewById<TextView>(R.id.txtRecibo)
                    txt.text = textoRecibo(r)
                    txt.visibility = View.VISIBLE
                }
            } catch (e: Exception) { /* aún no disponible */ }
        }
    }

    // ---------- ROL PESAJE ----------
    private fun entrarPesaje() {
        mostrarVista(R.id.vistaPesaje)
        cargarMateriales(R.id.spinnerMaterialPesaje, conVacio = false)
        iniciarPeriodica(5000) { refrescarTurnosPesaje() }
    }

    private fun refrescarTurnosPesaje() {
        val cliente = api ?: return
        io.execute {
            try {
                val turnos = cliente.turnos()
                ui.post { pintarListaTurnos(turnos) }
            } catch (e: Exception) {
                ui.post { estadoConexion("Sin conexión — reintentando…") }
            }
        }
    }

    private fun pintarListaTurnos(turnos: JSONArray) {
        estadoConexion("Servidor: ${prefs.getString("host", "")} · rol: pesaje")
        val contenedor = findViewById<LinearLayout>(R.id.listaTurnosPesaje)
        contenedor.removeAllViews()
        var visibles = 0
        for (i in 0 until turnos.length()) {
            val t = turnos.getJSONObject(i)
            val estado = t.getString("estado")
            if (estado == "FINALIZADO" || estado == "NO_PRESENTADO") continue
            visibles++
            val fila = LinearLayout(this)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.setBackgroundColor(getColor(R.color.panel))
            fila.setPadding(24, 24, 24, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 12
            fila.layoutParams = lp

            val info = TextView(this)
            info.text = "Turno %03d · %s %s\n%s".format(
                t.getInt("numero"), t.getString("tipo_documento"),
                t.getString("numero_documento"), estado.replace('_', ' '))
            info.setTextColor(getColor(R.color.texto))
            info.textSize = 15f
            info.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            fila.addView(info)

            val boton = Button(this)
            if (estado == "ESPERANDO") {
                boton.text = "Llamar"
                boton.setOnClickListener { dialogoLlamar(t.getLong("id")) }
            } else {
                boton.text = "Pesar"
                boton.setBackgroundColor(getColor(R.color.amarillo))
                boton.setTextColor(getColor(R.color.fondo))
                boton.setOnClickListener { abrirDetallePesaje(t) }
            }
            fila.addView(boton)
            contenedor.addView(fila)
        }
        if (visibles == 0) {
            val vacio = TextView(this)
            vacio.text = "No hay turnos activos en este momento."
            vacio.setTextColor(getColor(R.color.gris))
            contenedor.addView(vacio)
        }
    }

    private fun dialogoLlamar(turnoId: Long) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Número de módulo (ej: 1)"
        AlertDialog.Builder(this)
            .setTitle("Llamar turno")
            .setView(input)
            .setPositiveButton("Llamar") { _, _ ->
                val modulo = input.text.toString().toIntOrNull() ?: 1
                io.execute {
                    try {
                        api?.setEstado(turnoId, "LLAMADO", modulo)
                        ui.post { refrescarTurnosPesaje() }
                    } catch (e: Exception) {
                        ui.post { toast("No se pudo llamar el turno") }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrirDetallePesaje(t: JSONObject) {
        turnoPesajeId = t.getLong("id")
        detenerPeriodica()
        mostrarVista(R.id.vistaPesajeDetalle)
        findViewById<TextView>(R.id.txtPesajeTitulo).text =
            "Turno %03d · %s %s".format(t.getInt("numero"),
                t.getString("tipo_documento"), t.getString("numero_documento"))
        findViewById<EditText>(R.id.inputKg).setText("")
        actualizarValorEstimado()
        refrescarPesajes()
    }

    private fun materialSeleccionado(): JSONObject? {
        val pos = findViewById<Spinner>(R.id.spinnerMaterialPesaje).selectedItemPosition
        if (pos < 0 || pos >= materiales.length()) return null
        return materiales.getJSONObject(pos)
    }

    private fun actualizarValorEstimado() {
        val kg = findViewById<EditText>(R.id.inputKg).text.toString().toDoubleOrNull() ?: 0.0
        val precio = materialSeleccionado()?.optDouble("precio_promedio", 0.0) ?: 0.0
        findViewById<TextView>(R.id.txtValorEstimado).text =
            "$" + formato(Math.round(kg * precio))
    }

    private fun refrescarPesajes() {
        val cliente = api ?: return
        io.execute {
            try {
                val pesajes = cliente.pesajes(turnoPesajeId)
                ui.post {
                    val sb = StringBuilder()
                    var total = 0L
                    for (i in 0 until pesajes.length()) {
                        val p = pesajes.getJSONObject(i)
                        total += p.getLong("valor_total")
                        sb.append("• ${p.getString("familia")} / ${p.getString("subcategoria")}: ")
                        sb.append("${p.getDouble("peso_kg")} kg → $${formato(p.getLong("valor_total"))}\n")
                    }
                    sb.append(if (sb.isEmpty()) "Sin pesajes aún." else "\nTOTAL: $${formato(total)}")
                    findViewById<TextView>(R.id.txtPesajesLista).text = sb.toString()
                }
            } catch (e: Exception) { /* siguiente refresh */ }
        }
    }

    private fun registrarPesaje() {
        val cliente = api ?: return
        val kg = findViewById<EditText>(R.id.inputKg).text.toString().toDoubleOrNull()
        val material = materialSeleccionado()
        if (kg == null || kg <= 0) { toast("Ingresa un peso válido"); return }
        if (material == null) { toast("Selecciona el material"); return }
        io.execute {
            try {
                cliente.registrarPesaje(turnoPesajeId, material.getLong("id"), kg)
                ui.post {
                    findViewById<EditText>(R.id.inputKg).setText("")
                    actualizarValorEstimado()
                    refrescarPesajes()
                    toast("Pesaje registrado ✓")
                }
            } catch (e: Exception) {
                ui.post { toast("Error registrando el pesaje: ${e.message}") }
            }
        }
    }

    private fun finalizarPesaje() {
        val cliente = api ?: return
        AlertDialog.Builder(this)
            .setTitle("Finalizar turno")
            .setMessage("¿Generar el recibo y finalizar este turno?")
            .setPositiveButton("Finalizar") { _, _ ->
                io.execute {
                    try {
                        val recibo = cliente.finalizarTurno(turnoPesajeId)
                        ui.post {
                            toast("Recibo generado: total $${formato(recibo.getLong("total"))}")
                            entrarPesaje()
                        }
                    } catch (e: Exception) {
                        ui.post { toast("Error al finalizar: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- ROL ADMINISTRADOR ----------
    private fun entrarAdmin() {
        mostrarVista(R.id.vistaAdmin)
        iniciarPeriodica(8000) { refrescarRecibos() }
    }

    private fun refrescarRecibos() {
        val cliente = api ?: return
        io.execute {
            try {
                val recibos = cliente.recibos(soloPendientes = true)
                ui.post { pintarRecibos(recibos) }
            } catch (e: Exception) {
                ui.post { estadoConexion("Sin conexión — reintentando…") }
            }
        }
    }

    private fun pintarRecibos(recibos: JSONArray) {
        estadoConexion("Servidor: ${prefs.getString("host", "")} · rol: administrador")
        val contenedor = findViewById<LinearLayout>(R.id.listaRecibos)
        contenedor.removeAllViews()
        if (recibos.length() == 0) {
            val vacio = TextView(this)
            vacio.text = "🎉 No hay desembolsos pendientes."
            vacio.setTextColor(getColor(R.color.gris))
            contenedor.addView(vacio)
            return
        }
        for (i in 0 until recibos.length()) {
            val r = recibos.getJSONObject(i)
            val u = r.getJSONObject("usuario")
            val fila = LinearLayout(this)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.setBackgroundColor(getColor(R.color.panel))
            fila.setPadding(24, 24, 24, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 12
            fila.layoutParams = lp

            val info = TextView(this)
            info.text = "Turno %03d · %s %s\nTotal: $%s".format(
                r.getInt("numero_turno"), u.getString("tipo_documento"),
                u.getString("numero_documento"), formato(r.getLong("total")))
            info.setTextColor(getColor(R.color.texto))
            info.textSize = 15f
            info.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            fila.addView(info)

            val boton = Button(this)
            boton.text = "💵 Pagar"
            boton.setOnClickListener { dialogoPagar(r) }
            fila.addView(boton)
            contenedor.addView(fila)
        }
    }

    private fun dialogoPagar(r: JSONObject) {
        val u = r.getJSONObject("usuario")
        AlertDialog.Builder(this)
            .setTitle("Autorizar desembolso")
            .setMessage("Turno %03d · %s %s\n\n¿Autorizar el pago de $%s en efectivo?".format(
                r.getInt("numero_turno"), u.getString("tipo_documento"),
                u.getString("numero_documento"), formato(r.getLong("total"))))
            .setPositiveButton("Autorizar") { _, _ ->
                io.execute {
                    try {
                        api?.pagarRecibo(r.getLong("turno_id"))
                        ui.post {
                            toast("Desembolso autorizado ✓")
                            refrescarRecibos()
                        }
                    } catch (e: Exception) {
                        ui.post { toast("Error: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Utilidades ----------
    private fun textoRecibo(r: JSONObject): String {
        val sb = StringBuilder("RECIBO")
        sb.append(if (r.optBoolean("pagado")) "  · PAGADO ✓\n" else "  · pendiente de pago\n")
        val detalle = r.getJSONArray("detalle")
        for (i in 0 until detalle.length()) {
            val d = detalle.getJSONObject(i)
            sb.append("• ${d.getString("material")}: ${d.getDouble("kg")} kg → $${formato(d.getLong("total"))}\n")
        }
        sb.append("\nTOTAL: $${formato(r.getLong("total"))}")
        return sb.toString()
    }

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
