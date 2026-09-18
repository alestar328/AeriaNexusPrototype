package com.delta.aeria_nexus_prototype.data.identity

import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** El backend ha contestado que no, o no ha contestado. El mensaje se puede ensenar tal cual. */
class IamException(message: String) : Exception(message)

/** Lo que devuelve el alta del telefono: el identificador lo decide el backend, no nosotros. */
data class AltaDeTerminal(val deviceId: String, val cadenaPem: String)

/** Reto emitido por el backend. Se devuelve con su id: el backend solo acepta la respuesta a SU reto. */
class RetoEmitido(val id: String, val nonce: ByteArray, val emisor: String)

data class SesionEmitida(val token: String, val caducaEnMillis: Long)

/**
 * Las llamadas al IAM de AeriaOne. Contrato: `aeria-contracts/iam-session.md` §4 y §5.
 *
 * Sustituye a los scripts `tools/alta-*.sh` y `tools/reto.sh`, que hacian de backend
 * por adb. Aqui solo hay transporte: quien firma es el Keystore y quien decide que se
 * firma son los repositorios de identidad.
 *
 * Todas las llamadas son **bloqueantes**: quien llame tiene que estar ya en
 * `Dispatchers.IO`.
 */
class IamClient(private val config: UploadConfig) {

    /** Sin `api_url` en upload.conf no hay backend al que hablar. */
    fun configurado(): Boolean = config.apiUrl().isNotBlank()

    /** `POST iam/devices/enroll`. La postura se informa, no se decide: la valora el backend. */
    fun enrolarTerminal(
        csrPem: String,
        atributos: DeviceAttributes,
        postura: DevicePosture,
    ): AltaDeTerminal {
        val cuerpo = JSONObject()
            .put("csr_pem", csrPem)
            .put("device_kind", "phone")
            .put("attributes", atributosEnJson(atributos))
            .put("posture", posturaEnJson(postura))
        val respuesta = post("iam/devices/enroll", cuerpo, esperado = HttpURLConnection.HTTP_CREATED)
        return AltaDeTerminal(
            deviceId = respuesta.getString("device_id"),
            cadenaPem = cadenaPem(respuesta),
        )
    }

    /**
     * `POST iam/users/enroll`. Devuelve la cadena del certificado del agente en PEM.
     *
     * [firmaDelTerminal] tiene que ser la firma sobre los bytes UTF-8 de [csrPem] tal
     * cual se envia: el backend verifica ese texto exacto, no el DER del CSR.
     */
    fun enrolarAgente(
        csrPem: String,
        userId: String,
        deviceId: String,
        firmaDelTerminal: ByteArray,
    ): String {
        val cuerpo = JSONObject()
            .put("csr_pem", csrPem)
            .put("user_id", userId)
            .put("device_id", deviceId)
            .put("device_signature", Base64.getEncoder().encodeToString(firmaDelTerminal))
        val respuesta = post("iam/users/enroll", cuerpo, esperado = HttpURLConnection.HTTP_CREATED)
        return cadenaPem(respuesta)
    }

    /** `POST iam/challenge`. El nonce lo genera el servidor, que es lo que da frescura a la firma. */
    fun pedirReto(proposito: PropositoDelReto, sujeto: String): RetoEmitido {
        val cuerpo = JSONObject()
            .put("purpose", proposito.name)
            .put("subject_id", sujeto)
        val respuesta = post("iam/challenge", cuerpo, esperado = HttpURLConnection.HTTP_OK)
        return RetoEmitido(
            id = respuesta.getString("challenge_id"),
            nonce = Base64.getDecoder().decode(respuesta.getString("challenge")),
            emisor = respuesta.optString("issuer", "AeriaOne"),
        )
    }

    /** `POST iam/session`: cambia la firma del reto por el token de la sesion. */
    fun abrirSesion(
        reto: RetoEmitido,
        firma: ByteArray,
        deviceId: String,
        appInstanceId: String,
        appVersion: String,
    ): SesionEmitida {
        val codificador = Base64.getEncoder()
        val cuerpo = JSONObject()
            .put("challenge_id", reto.id)
            .put("nonce", codificador.encodeToString(reto.nonce))
            .put("signature", codificador.encodeToString(firma))
            .put("device_id", deviceId)
            .put("app_instance_id", appInstanceId)
            .put("app_version", appVersion)
        val respuesta = post("iam/session", cuerpo, esperado = HttpURLConnection.HTTP_OK)
        val segundos = respuesta.getLong("expires_in")
        return SesionEmitida(
            token = respuesta.getString("access_token"),
            caducaEnMillis = System.currentTimeMillis() + segundos * 1_000,
        )
    }

    /** `POST iam/session/logout`: el backend invalida el token aunque aun no haya caducado. */
    fun cerrarSesion(token: String) {
        post("iam/session/logout", JSONObject(), esperado = HttpURLConnection.HTTP_NO_CONTENT, token = token)
    }

    private fun post(ruta: String, cuerpo: JSONObject, esperado: Int, token: String? = null): JSONObject {
        if (!configurado()) throw IamException("No AeriaOne server configured (api_url in upload.conf)")
        val conexion = URL(config.apiUrl() + ruta).openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = TIMEOUT_MILLIS
            conexion.readTimeout = TIMEOUT_MILLIS
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Accept", "application/json")
            token?.let { conexion.setRequestProperty("Authorization", "Bearer $it") }
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }

            val codigo = conexion.responseCode
            val flujo = if (codigo < 400) conexion.inputStream else conexion.errorStream
            val texto = flujo?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo != esperado) throw IamException("AeriaOne answered $codigo: ${motivoDe(texto)}")
            return if (texto.isBlank()) JSONObject() else JSONObject(texto)
        } catch (e: IOException) {
            throw IamException("AeriaOne unreachable: ${e.message}")
        } finally {
            conexion.disconnect()
        }
    }

    /**
     * El `detail` del backend es lo que explica el rechazo ("Dispositivo no enrolado",
     * "Sujeto desconocido"...). Los errores de validacion de DRF no lo traen y llegan
     * como un objeto por campo; en ese caso se ensena el cuerpo recortado.
     */
    private fun motivoDe(texto: String): String {
        val detalle = runCatching { JSONObject(texto).optString("detail") }.getOrNull()
        return detalle?.takeIf { it.isNotBlank() } ?: texto.take(MAXIMO_MOTIVO)
    }

    /** Certificado emitido seguido de su cadena, que es lo que instala el Keystore. */
    private fun cadenaPem(respuesta: JSONObject): String {
        val cadena: JSONArray = respuesta.optJSONArray("chain_pem") ?: JSONArray()
        val intermedios = (0 until cadena.length()).map { cadena.getString(it) }
        return (listOf(respuesta.getString("certificate_pem")) + intermedios).joinToString("\n")
    }

    /**
     * Solo el aparato y nuestra app: el `android_id` del contrato no se manda porque
     * identifica a la persona fuera de AeriaOne, y el §15 limita el alta a lo que hace
     * falta para proteger el servicio.
     */
    private fun atributosEnJson(atributos: DeviceAttributes) = JSONObject()
        .put("model", atributos.model)
        .put("manufacturer", atributos.manufacturer)
        .put("os_version", atributos.androidRelease)
        .put("sdk_int", atributos.sdkInt)
        .put("security_patch", atributos.securityPatch)
        .put("build_fingerprint", atributos.buildFingerprint)
        .put("app_package", atributos.appPackage)
        .put("app_version", atributos.appVersion)
        .put("app_signer_sha256", atributos.appSignerSha256)

    private fun posturaEnJson(postura: DevicePosture) = JSONObject()
        .put("keystore_available", postura.keystoreDisponible)
        .put("declared_key_protection", postura.capacidadDeclarada.name)
        .put("tampering_indicators", JSONArray(postura.indiciosDeManipulacion))
        .put("emulator", postura.esEmulador)

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MAXIMO_MOTIVO = 200
    }
}
