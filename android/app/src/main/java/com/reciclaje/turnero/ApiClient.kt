package com.reciclaje.turnero

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP mínimo (HttpURLConnection, sin dependencias externas).
 * Los roles elevados se autentican con un token de dispositivo (header X-TOKEN)
 * obtenido al emparejar con el PIN de sesión; el rol cliente no usa token.
 * Todas las llamadas deben ejecutarse fuera del hilo principal.
 */
class ApiClient(private val host: String, private val puerto: Int, private val token: String = "") {

    private fun abrir(ruta: String, metodo: String): HttpURLConnection {
        val conn = URL("http://$host:$puerto$ruta").openConnection() as HttpURLConnection
        conn.requestMethod = metodo
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        if (token.isNotEmpty()) conn.setRequestProperty("X-TOKEN", token)
        conn.setRequestProperty("Content-Type", "application/json")
        return conn
    }

    /** Empareja el dispositivo con un PIN de sesión y devuelve {token, rol}. */
    fun emparejar(rol: String, pin: String, nombreDispositivo: String): JSONObject {
        val conn = abrir("/api/emparejar", "POST")
        try {
            conn.doOutput = true
            val body = JSONObject().put("rol", rol).put("pin", pin).put("nombre", nombreDispositivo)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = leer(conn)
            if (conn.responseCode !in 200..299) throw ApiException(conn.responseCode, extraerError(resp))
            return JSONObject(resp)
        } finally {
            conn.disconnect()
        }
    }

    /** Historial de pagos del cliente, filtrado por su documento. */
    fun historial(tipoDoc: String, numeroDoc: String): JSONArray {
        val conn = abrir("/api/historial?tipo_documento=$tipoDoc&numero_documento=$numeroDoc", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun leer(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
    }

    fun ping(): JSONObject {
        val conn = abrir("/api/ping", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun materiales(): JSONArray {
        val conn = abrir("/api/materiales", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    fun crearTurno(tipoDoc: String, numeroDoc: String, materialId: Long?): JSONObject {
        val conn = abrir("/api/turnos", "POST")
        try {
            conn.doOutput = true
            val body = JSONObject()
                .put("tipo_documento", tipoDoc)
                .put("numero_documento", numeroDoc)
            if (materialId != null) body.put("material_id", materialId)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = leer(conn)
            if (conn.responseCode !in 200..299) throw ApiException(conn.responseCode, extraerError(resp))
            return JSONObject(resp)
        } finally {
            conn.disconnect()
        }
    }

    fun turno(id: Long): JSONObject {
        val conn = abrir("/api/turnos/$id", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun turnos(): JSONArray {
        val conn = abrir("/api/turnos", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    fun setEstado(id: Long, estado: String, modulo: Int?): JSONObject {
        val conn = abrir("/api/turnos/$id/estado", "PUT")
        try {
            conn.doOutput = true
            val body = JSONObject().put("estado", estado)
            if (modulo != null) body.put("modulo_asignado", modulo)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(resp))
            return JSONObject(resp)
        } finally {
            conn.disconnect()
        }
    }

    fun registrarPesaje(turnoId: Long, materialId: Long, pesoKg: Double): JSONArray {
        val conn = abrir("/api/pesaje", "POST")
        try {
            conn.doOutput = true
            val body = JSONObject()
                .put("turno_id", turnoId)
                .put("material_id", materialId)
                .put("peso_kg", pesoKg)
                .put("usuario_pesador", "app-pesaje")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = leer(conn)
            if (conn.responseCode !in 200..299) throw ApiException(conn.responseCode, extraerError(resp))
            return JSONArray(resp)
        } finally {
            conn.disconnect()
        }
    }

    fun pesajes(turnoId: Long): JSONArray {
        val conn = abrir("/api/pesajes/$turnoId", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    fun finalizarTurno(turnoId: Long): JSONObject {
        val conn = abrir("/api/turnos/$turnoId/finalizar", "POST")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun recibos(soloPendientes: Boolean): JSONArray {
        val ruta = if (soloPendientes) "/api/recibos?pendientes=1" else "/api/recibos"
        val conn = abrir(ruta, "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    fun pagarRecibo(turnoId: Long): JSONObject {
        val conn = abrir("/api/recibos/$turnoId/pagar", "POST")
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("autorizado_por", "app-admin").toString().toByteArray()) }
            val body = leer(conn)
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun recibo(turnoId: Long): JSONObject? {
        val conn = abrir("/api/recibos/$turnoId", "GET")
        try {
            val body = leer(conn)
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw ApiException(conn.responseCode, extraerError(body))
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun extraerError(body: String): String = try {
        JSONObject(body).optString("error", "Error del servidor")
    } catch (e: Exception) {
        "Error del servidor"
    }
}

class ApiException(val codigo: Int, mensaje: String) : Exception(mensaje)
