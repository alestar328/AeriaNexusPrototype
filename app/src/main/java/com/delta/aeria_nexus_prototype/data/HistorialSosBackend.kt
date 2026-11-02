package com.delta.aeria_nexus_prototype.data

import android.util.Log
import com.delta.aeria_nexus_prototype.data.red.ConexionBackend
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import org.json.JSONException
import org.json.JSONObject

/** Un SOS tal como lo tiene apuntado AeriaOne. */
data class SosDelBackend(
    val sosId: String,
    val source: String,
    val live: Boolean,
    val officerCode: String,
    val officerName: String,
    val startedAtMillis: Long,
    val stoppedAtMillis: Long?,
    // cancelled, superseded, signal_lost, o vacio mientras sigue en curso.
    val reason: String,
)

/** Respuesta de GET sos: total y en curso son del tenant entero, no solo de la pagina. */
data class HistorialDelBackend(
    val total: Int,
    val live: Int,
    val items: List<SosDelBackend>,
)

/**
 * Lee el historial de SOS de AeriaOne. Contrato: aeria-contracts sos-and-proxy.md §2.6.
 * Solo lectura: la ruta no admite borrar ni cambiar nada.
 */
class HistorialSosBackend(private val config: UploadConfig) {

    /**
     * La primera pagina, lo mas nuevo primero; null si no hay servidor, sesion o red.
     * Con la sesion restringida de un agente suspendido no se pide: el backend da 403.
     */
    fun primeraPagina(): HistorialDelBackend? {
        val base = config.apiUrl()
        val token = config.token()
        if (base.isBlank() || token == null) return null
        val conexion = ConexionBackend.abrir("${base}sos?limit=$LIMITE")
        return try {
            conexion.connectTimeout = TIMEOUT_MILLIS
            conexion.readTimeout = TIMEOUT_MILLIS
            conexion.setRequestProperty("Authorization", "Bearer $token")
            val codigo = conexion.responseCode
            config.avisarSiRechazo(conexion, codigo)
            if (codigo != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GET sos: $codigo")
                return null
            }
            leer(JSONObject(conexion.inputStream.bufferedReader().use { it.readText() }))
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            Log.w(TAG, "GET sos: respuesta ilegible", e)
            null
        } finally {
            conexion.disconnect()
        }
    }

    private fun leer(json: JSONObject): HistorialDelBackend {
        val items = json.getJSONArray("items")
        return HistorialDelBackend(
            total = json.getInt("total"),
            live = json.optInt("live"),
            items = (0 until items.length()).mapNotNull { leerItem(items.getJSONObject(it)) },
        )
    }

    private fun leerItem(json: JSONObject): SosDelBackend? {
        val inicio = aMillis(json.optString("started_at")) ?: return null
        return SosDelBackend(
            sosId = json.getString("sos_id"),
            source = json.optString("source"),
            live = json.optBoolean("live"),
            officerCode = json.optString("officer_code"),
            officerName = json.optString("officer_name"),
            startedAtMillis = inicio,
            stoppedAtMillis = aMillis(json.optString("stopped_at")),
            reason = json.optString("reason"),
        )
    }

    // OffsetDateTime y no Instant: Instant.parse en Android 8 solo acepta la "Z".
    private fun aMillis(texto: String): Long? {
        if (texto.isBlank() || texto == "null") return null
        return try {
            OffsetDateTime.parse(texto).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        const val TAG = "HistorialSosBackend"
        // Maximo que admite el backend por pagina.
        const val LIMITE = 200
        const val TIMEOUT_MILLIS = 10_000
    }
}
