package com.delta.aeria_nexus_prototype.data.upload

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.identity.AlmacenDeInvitacion
import com.delta.aeria_nexus_prototype.data.identity.SesionBackend
import java.io.File
import java.net.HttpURLConnection

private const val TAG = "FalconUploadCfg"

/**
 * Dónde sube el teléfono y con qué credencial.
 *
 * Gemelo de `UploadConfig` en BodyCamServer: las dos apps hablan el mismo protocolo
 * (`docs/UPLOAD-PROTOCOL.md` del repo de la bodycam) contra el mismo destino, así que
 * la configuración se lee igual y desde un fichero, no de una constante compilada.
 *
 *     /sdcard/Android/data/com.delta.aeria_nexus_prototype/files/upload.conf
 *     base_url=http://192.168.0.14:1080/files/
 *     api_url=http://192.168.0.14:1080/api/
 *     chunk_bytes=1048576
 *
 * Se pone por adb sin recompilar:
 *
 *     adb shell "echo base_url=http://192.168.0.14:1080/files/ > \
 *       /sdcard/Android/data/com.delta.aeria_nexus_prototype/files/upload.conf"
 *
 * La credencial NO esta en el fichero: es el token de la sesion que se abre con el PIN
 * ([SesionBackend]). Sin sesion no se sube nada, y lo pendiente sale al abrirla.
 *
 * Desde el alta por QR (iam-session.md §4.4) el servidor sale del QR escaneado. El
 * fichero solo cuenta en compilaciones de depuracion, y ahi manda sobre el QR; en una
 * version final se ignora: desviar la evidencia no puede costar un `adb push`.
 *
 * El destino por defecto queda **vacío a propósito**: una app sin QR ni `upload.conf` no
 * debe empezar a mandar evidencia a un sitio que nadie ha confirmado.
 */
class UploadConfig(
    private val context: Context,
    private val sesion: SesionBackend,
    private val invitacion: AlmacenDeInvitacion,
) {

    private val confFile: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, CONF_NAME) }

    /**
     * Se relee en cada acceso en vez de cachearse. Una subida puede estar reintentando
     * durante minutos; poder corregir la URL por adb sin reiniciar la app ahorra un ciclo
     * entero de prueba.
     */
    private fun conf(): Map<String, String> {
        if (!BuildConfig.DEBUG) return emptyMap()
        val file = confFile ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return try {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { line ->
                    val i = line.indexOf('=')
                    line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo leer $CONF_NAME: ${e.message}")
            emptyMap()
        }
    }

    /** Siempre con barra final: la URL de creación es un directorio, no un recurso. */
    fun baseUrl(): String {
        val raw = conf()["base_url"]?.takeIf { it.isNotBlank() }
            ?: invitacion.actual()?.baseUrl
            ?: DEFAULT_BASE_URL
        return if (raw.isBlank() || raw.endsWith("/")) raw else "$raw/"
    }

    /**
     * Base de la API del backend: hoy solo los avisos del SOS (SosNotifier). Vacia
     * mientras no se configure, y por el mismo motivo que [baseUrl]: no se avisa a
     * un servidor que nadie ha confirmado.
     */
    fun apiUrl(): String {
        val raw = conf()["api_url"]?.takeIf { it.isNotBlank() }
            ?: invitacion.actual()?.apiUrl.orEmpty()
        return if (raw.isBlank() || raw.endsWith("/")) raw else "$raw/"
    }

    /**
     * El token de la sesion abierta con AeriaOne, o null si no la hay. Ya no sale del
     * fichero: el backend rechaza cualquier cosa que no haya emitido el mismo.
     */
    fun token(): String? = sesion.token()

    /** Para los avisos de SOS, que un agente suspendido tambien puede mandar. */
    fun tokenParaSos(): String? = sesion.tokenParaSos()

    /**
     * El backend ha contestado 401 o 403 a una peticion con ese token. Si el motivo es
     * que el perfil esta suspendido o revocado, la sesion se descarta y se avisa al agente.
     */
    fun avisarSiRechazo(conexion: HttpURLConnection, codigoHttp: Int) {
        if (codigoHttp == HttpURLConnection.HTTP_UNAUTHORIZED || codigoHttp == HttpURLConnection.HTTP_FORBIDDEN) {
            sesion.rechazada(conexion.getHeaderField("X-Error-Code"))
        }
    }

    fun chunkBytes(): Int =
        conf()["chunk_bytes"]?.toIntOrNull()?.takeIf { it in 64 * 1024..64 * 1024 * 1024 }
            ?: DEFAULT_CHUNK_BYTES

    fun enabled(): Boolean {
        if (conf()["enabled"] == "false") return false
        if (baseUrl().isBlank()) {
            Log.w(TAG, "sin base_url — la subida por bloques está desactivada")
            return false
        }
        return true
    }

    /** El servidor sale de upload.conf y no del QR. Solo puede pasar en depuracion. */
    fun apiDesdeFichero(): Boolean = !conf()["api_url"].isNullOrBlank()

    fun describe(): String = "base_url=${baseUrl()} chunk=${chunkBytes() / 1024} KB"

    private companion object {
        const val CONF_NAME = "upload.conf"

        /** TODO: la URL real de Nexus cuando backend confirme el endpoint. */
        const val DEFAULT_BASE_URL = ""
        const val DEFAULT_CHUNK_BYTES = 8 * 1024 * 1024
    }
}
