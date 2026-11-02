package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.DeviceKeyWrapper
import com.delta.aeria_nexus_prototype.data.red.ConexionBackend
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import com.delta.aeria_nexus_prototype.data.identity.SesionBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "AeriaAgoraToken"

/** Una clave semanal del canal (sos-and-proxy.md §2.7, punto 2). */
data class ClaveDelCanal(
    val keyId: String,
    /** 64 caracteres hex. Se le pasa TAL CUAL al SDK como `encryptionKey`. */
    val keyHex: String,
    /** 32 bytes en base64; descodificados son el `encryptionKdfSalt`. */
    val saltB64: String,
    val desdeMillis: Long,
    val hastaMillis: Long,
) {
    fun vigenteEn(ahora: Long): Boolean = ahora in desdeMillis until hastaMillis

    fun salt(): ByteArray = Base64.getDecoder().decode(saltB64)
}

/**
 * Lo que da `POST agora/token`: el uid que asigna AeriaOne, el token de 12 h, el de
 * reserva de 24 h y, solo si el backend tiene encendido el cifrado, las claves.
 * [token] null = proyecto de Agora sin certificado: se entra sin token, como hasta ahora.
 */
data class AccesoAgora(
    val uid: Int,
    val canal: String,
    val token: String?,
    val tokenHastaMillis: Long,
    val reserva: String?,
    val reservaHastaMillis: Long,
    val claves: List<ClaveDelCanal>,
) {
    /**
     * El token con el que entrar ahora: el normal mientras valga y, si no, el de reserva,
     * que solo existe para cuando no se llega al backend. Null si ninguno vale.
     */
    fun tokenPara(ahora: Long): String? = when {
        token != null && ahora < tokenHastaMillis -> token
        reserva != null && ahora < reservaHastaMillis -> reserva
        else -> null
    }

    /** La clave que vale ahora; null si el cifrado esta apagado (se entra en claro). */
    fun claveEn(ahora: Long): ClaveDelCanal? = claves.firstOrNull { it.vigenteEn(ahora) }

    /** Las claves que aun no han acabado, la actual y la siguiente. */
    fun clavesPendientes(ahora: Long): List<ClaveDelCanal> = claves.filter { ahora < it.hastaMillis }
}

/**
 * Pide a AeriaOne el acceso al canal de Agora (sos-and-proxy.md §2.7) para este
 * telefono y para su bodycam, y guarda el del telefono.
 *
 * Se guarda porque el SOS no puede depender de que haya red con el backend en ese
 * momento (decision 7): con lo guardado se vuelve a entrar con el uid asignado, el token
 * de reserva y la clave de la semana. Va cifrado con una clave del Keystore propia,
 * en noBackupFilesDir: con el token y la clave cualquiera entraria en el canal.
 *
 * Las peticiones son bloqueantes: quien llame tiene que estar en `Dispatchers.IO`.
 */
class AccesoAgoraRepository(
    context: Context,
    private val config: UploadConfig,
    sesionBackend: SesionBackend,
) {

    private val fichero = File(context.noBackupFilesDir, "agora_acceso.bin")

    private val _actual = MutableStateFlow(leerGuardado())

    init {
        // Cada sesion nueva, completa o restringida al SOS, trae un acceso al dia.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sesionBackend.actual.filterNotNull().collect { renovarEsteTelefono() }
        }
    }

    /** El ultimo acceso de este telefono, del backend o guardado; null si nunca hubo. */
    val actual: StateFlow<AccesoAgora?> = _actual.asStateFlow()

    /**
     * Pide el acceso de este telefono, lo guarda y lo publica en [actual]. Null si no hay
     * sesion o el backend no lo da (servidor viejo, cuarentena): se sigue con lo que hubiera.
     */
    fun renovarEsteTelefono(): AccesoAgora? {
        val respuesta = pedir(JSONObject().put("role", "publisher")) ?: return null
        val acceso = runCatching { leer(respuesta) }
            .onFailure { Log.e(TAG, "respuesta de agora/token ilegible: ${it.message}") }
            .getOrNull() ?: return null
        guardar(respuesta)
        _actual.value = acceso
        return acceso
    }

    /**
     * El acceso de la bodycam atada a este agente. Solo lo da AeriaOne con la atadura
     * registrada (`403 binding_invalid` si no). No se guarda: la camara lo conserva ella.
     * [alPonerEnCuarentena] se llama si AeriaOne dice que la camara esta en cuarentena.
     */
    fun paraLaCamara(bwcId: String, alPonerEnCuarentena: () -> Unit): AccesoAgora? {
        val cuerpo = JSONObject().put("role", "publisher").put("bwc_id", bwcId)
        val respuesta = pedir(cuerpo, alPonerEnCuarentena) ?: return null
        return runCatching { leer(respuesta) }.getOrNull()
    }

    /** Al destruir la identidad o retirar el terminal: el acceso es de este alta. */
    fun olvidar() {
        _actual.value = null
        fichero.delete()
    }

    /**
     * Un rechazo con `device_id` es de la camara pedida y no de este telefono: no se le
     * pasa a la sesion, que cortaria al agente sin motivo. Si es su cuarentena, se avisa
     * con [camaraEnCuarentena].
     */
    private fun pedir(cuerpo: JSONObject, camaraEnCuarentena: (() -> Unit)? = null): JSONObject? {
        val base = config.apiUrl()
        // Vale tambien la sesion restringida de un suspendido: recibe lo mismo (§2.7).
        val token = config.tokenParaSos()
        if (base.isBlank() || token == null) return null
        val conexion = ConexionBackend.abrir(base + RUTA)
        return try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = TIMEOUT_MILLIS
            conexion.readTimeout = TIMEOUT_MILLIS
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Authorization", "Bearer $token")
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = conexion.responseCode
            if (codigo != 200) {
                val error = runCatching {
                    JSONObject(conexion.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
                }.getOrNull()
                val deOtroAparato = error?.optString("device_id").orEmpty().isNotBlank()
                if (deOtroAparato) {
                    if (error?.optString("code") == CODIGO_CUARENTENA) camaraEnCuarentena?.invoke()
                } else {
                    // Los cortes del telefono se atienden en un solo sitio (IdentityRepository).
                    config.avisarSiRechazo(conexion, codigo)
                }
                Log.w(TAG, "agora/token contesto $codigo ${error?.optString("code").orEmpty()}")
                return null
            }
            JSONObject(conexion.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            Log.w(TAG, "agora/token sin respuesta: ${e.message}")
            null
        } finally {
            conexion.disconnect()
        }
    }

    private fun guardar(respuesta: JSONObject) {
        runCatching {
            fichero.writeBytes(DeviceKeyWrapper.agora.wrap(respuesta.toString().toByteArray(Charsets.UTF_8)))
        }.onFailure { Log.e(TAG, "no se pudo guardar el acceso a Agora: ${it.message}") }
    }

    private fun leerGuardado(): AccesoAgora? = runCatching {
        if (!fichero.isFile) return null
        val claro = DeviceKeyWrapper.agora.unwrap(fichero.readBytes()) ?: return null
        leer(JSONObject(String(claro, Charsets.UTF_8)))
    }.getOrNull()

    companion object {
        private const val RUTA = "agora/token"
        const val CODIGO_CUARENTENA = "device_quarantined"
        private const val TIMEOUT_MILLIS = 10_000

        /** La respuesta de `POST agora/token`. Lanza si falta el uid. */
        fun leer(json: JSONObject): AccesoAgora {
            val reserva = json.optJSONObject("reserve")
            val claves = json.optJSONObject("encryption")
                ?.takeIf { it.optString("mode") == MODO_CIFRADO }
                ?.let { cifrado -> listOfNotNull(cifrado.optJSONObject("current"), cifrado.optJSONObject("next")) }
                .orEmpty()
                .map(::clave)
            return AccesoAgora(
                uid = json.getLong("uid").toInt(),
                canal = json.optString("channel"),
                token = json.textoONull("token"),
                tokenHastaMillis = instante(json.optString("expires_at")),
                reserva = reserva?.textoONull("token"),
                reservaHastaMillis = instante(reserva?.optString("expires_at").orEmpty()),
                claves = claves,
            )
        }

        /** El unico modo que usan las tres apps; otro no se entiende y se entra en claro. */
        const val MODO_CIFRADO = "AES_256_GCM2"

        private fun clave(json: JSONObject) = ClaveDelCanal(
            keyId = json.getString("key_id"),
            keyHex = json.getString("key"),
            saltB64 = json.getString("salt"),
            desdeMillis = instante(json.getString("valid_from")),
            hastaMillis = instante(json.getString("valid_until")),
        )

        private fun instante(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

        // org.json devuelve "null" como texto para un null de JSON con optString.
        private fun JSONObject.textoONull(clave: String): String? =
            if (isNull(clave)) null else optString(clave).ifBlank { null }
    }
}
