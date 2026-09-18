package com.delta.aeria_nexus_prototype.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Avisa al backend de que un SOS de este telefono empieza, sigue vivo y termina, para
 * que lo grabe con Agora Cloud Recording. Contrato: docs/BACKEND-PROXY-AND-SOS.md §2.
 *
 * Hace falta porque Agora no avisa cuando alguien empieza a publicar video (sus avisos
 * de canal solo cubren entrar y salir), y el telefono esta siempre dentro del canal.
 * Sin estos avisos el backend no sabria ni cuando grabar ni cuando parar.
 *
 * Nada de esto puede frenar el SOS: los avisos van en segundo plano con pocos
 * reintentos, y un fallo solo queda en el log. La emergencia sale por Agora igual.
 */
class SosNotifier(
    private val context: Context,
    private val config: UploadConfig,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun inicio(
        sosId: String,
        channel: String,
        uid: Int,
        officer: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        val cuerpo = JSONObject()
            .put("sos_id", sosId)
            .put("source", "phone")
            .put("channel", channel)
            .put("uid", uid)
            .put("officer_code", officer)
            .put("device_id", androidId())
            .put("device_model", Build.MODEL)
            .put("started_at", ahoraIso())
        // Sin posicion se omiten: un 0.0 es un punto real en el golfo de Guinea.
        latitude?.let { cuerpo.put("latitude", it) }
        longitude?.let { cuerpo.put("longitude", it) }
        enviar("sos/start", cuerpo, reintentos = REINTENTOS)
    }

    /**
     * Un latido perdido no se reintenta: llega otro a los pocos segundos, y el
     * backend solo corta si faltan varios seguidos.
     */
    fun latido(sosId: String) {
        enviar("sos/heartbeat", JSONObject().put("sos_id", sosId).put("at", ahoraIso()), reintentos = 0)
    }

    fun fin(sosId: String, motivo: String) {
        val cuerpo = JSONObject()
            .put("sos_id", sosId)
            .put("stopped_at", ahoraIso())
            .put("reason", motivo)
        enviar("sos/stop", cuerpo, reintentos = REINTENTOS)
    }

    private fun enviar(ruta: String, cuerpo: JSONObject, reintentos: Int) {
        val base = config.apiUrl()
        if (base.isBlank()) return
        scope.launch {
            for (intento in 0..reintentos) {
                if (post(base + ruta, cuerpo)) return@launch
                if (intento < reintentos) delay(ESPERA_INICIAL_MILLIS shl intento)
            }
            // Solo la ruta: el cuerpo lleva la placa del agente y su posicion.
            Log.w(TAG, "$ruta: el backend no ha contestado")
        }
    }

    private fun post(url: String, cuerpo: JSONObject): Boolean {
        val conexion = URL(url).openConnection() as HttpURLConnection
        return try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = TIMEOUT_MILLIS
            conexion.readTimeout = TIMEOUT_MILLIS
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Sin sesion el aviso sale igual: que el backend lo rechace queda en su
            // log, y retenerlo aqui retrasaria la grabacion de una emergencia.
            config.token()?.let { conexion.setRequestProperty("Authorization", "Bearer $it") }
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            conexion.responseCode in 200..299
        } catch (e: IOException) {
            false
        } finally {
            conexion.disconnect()
        }
    }

    // El mismo identificador que ya manda la bodycam en sus subidas: sirve para
    // cruzar en el backend que aparato pidio la grabacion.
    @SuppressLint("HardwareIds")
    private fun androidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    private fun ahoraIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private companion object {
        const val TAG = "SosNotifier"
        const val REINTENTOS = 3
        const val ESPERA_INICIAL_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 10_000
    }
}
