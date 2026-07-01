package com.reciclaje.turnero

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP mínimo (HttpURLConnection, sin dependencias externas).
 * Todas las llamadas deben ejecutarse fuera del hilo principal.
 */
class ApiClient(private val host: String, private val puerto: Int, private val pin: String) {

    private fun abrir(ruta: String, metodo: String): HttpURLConnection {
        val conn = URL("http://$host:$puerto$ruta").openConnection() as HttpURLConnection
        conn.requestMethod = metodo
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.setRequestProperty("X-PIN", pin)
        conn.setRequestProperty("Content-Type", "application/json")
        return conn
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
