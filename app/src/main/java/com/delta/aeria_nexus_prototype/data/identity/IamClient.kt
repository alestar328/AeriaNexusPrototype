package com.delta.aeria_nexus_prototype.data.identity

import com.delta.aeria_nexus_prototype.data.red.ConexionBackend
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * El backend ha contestado que no, o no ha contestado. El mensaje se puede ensenar tal cual.
 *
 * [codigo] es el `code` estable del contrato (§5.7), por ejemplo `profile_suspended`.
 * Es por lo que se decide: el texto de `detail` puede cambiar de redaccion.
 */
class IamException(message: String, val codigo: String? = null) : Exception(message)

/**
 * Lo que devuelve el alta del telefono: el identificador lo decide el backend, no nosotros.
 *
 * [anclaDePerifericosPem] es la CA de dispositivos (`device_ca_pem`): la que firma a
 * este terminal y a las bodycams. [caDeAgentesPem] es `user_ca_pem`. Null si el
 * backend no las manda.
 */
data class AltaDeTerminal(
    val deviceId: String,
    val cadenaPem: String,
    val anclaDePerifericosPem: String?,
    val caDeAgentesPem: String?,
)

/** Lo que devuelve el alta del agente. Las anclas viajan tambien aqui (ver [AltaDeTerminal]). */
data class AltaDeAgente(
    val cadenaPem: String,
    val anclaDePerifericosPem: String?,
    val caDeAgentesPem: String?,
    val perfil: PerfilRecibido?,
)

/** Reto emitido por el backend. Se devuelve con su id: el backend solo acepta la respuesta a SU reto. */
class RetoEmitido(val id: String, val nonce: ByteArray, val emisor: String)

/**
 * [perfil] es null solo si contesta un backend anterior al 9-oct-2026, que no lo manda.
 *
 * [soloSos] marca la sesion restringida de un agente suspendido (iam-session.md §5.8):
 * su token solo vale para los avisos de SOS, y con el no se sube nada.
 */
data class SesionEmitida(
    val token: String,
    val caducaEnMillis: Long,
    val perfil: PerfilRecibido? = null,
    val soloSos: Boolean = false,
)

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

    /** Sin `api_url` (del QR de alta, o de upload.conf en depuracion) no hay backend al que hablar. */
    fun configurado(): Boolean = config.apiUrl().isNotBlank()

    /** Solo en depuracion: el servidor lo fija upload.conf y se puede dar de alta sin QR. */
    fun servidorDesdeFichero(): Boolean = config.apiDesdeFichero()

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
            anclaDePerifericosPem = anclaDePerifericos(respuesta),
            caDeAgentesPem = caDeAgentes(respuesta),
        )
    }

    /**
     * `POST iam/users/enroll`. Devuelve la cadena del certificado del agente en PEM.
     *
     * [firmaDelTerminal] tiene que ser la firma sobre los bytes UTF-8 de [csrPem] tal
     * cual se envia: el backend verifica ese texto exacto, no el DER del CSR.
     *
     * [codigoDeAlta] es el `code` del QR (§4.4). Null en el reintento idempotente,
     * que no lo necesita, y en las altas sin QR.
     */
    fun enrolarAgente(
        csrPem: String,
        userId: String,
        deviceId: String,
        firmaDelTerminal: ByteArray,
        codigoDeAlta: String? = null,
    ): AltaDeAgente {
        val cuerpo = JSONObject()
            .put("csr_pem", csrPem)
            .put("user_id", userId)
            .put("device_id", deviceId)
            .put("device_signature", Base64.getEncoder().encodeToString(firmaDelTerminal))
        codigoDeAlta?.let { cuerpo.put("enrollment_code", it) }
        // 200 es la misma credencial entregada otra vez: el 201 de un intento anterior
        // se perdio y se repite con el mismo par de claves (§4.2).
        val respuesta = post(
            "iam/users/enroll",
            cuerpo,
            esperado = HttpURLConnection.HTTP_CREATED,
            tambienVale = HttpURLConnection.HTTP_OK,
        )
        return AltaDeAgente(
            cadenaPem = cadenaPem(respuesta),
            anclaDePerifericosPem = anclaDePerifericos(respuesta),
            caDeAgentesPem = caDeAgentes(respuesta),
            perfil = perfilDe(respuesta),
        )
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

    /**
     * `POST iam/session`: cambia la firma del reto por el token de la sesion.
     *
     * Con [soloSos] se pide la sesion restringida que AeriaOne concede a un agente
     * suspendido. Si entretanto lo han reactivado, el backend ignora la peticion y
     * devuelve una sesion completa: por eso se mira la respuesta y no lo que se pidio.
     */
    fun abrirSesion(
        reto: RetoEmitido,
        firma: ByteArray,
        deviceId: String,
        appInstanceId: String,
        appVersion: String,
        soloSos: Boolean = false,
    ): SesionEmitida {
        val codificador = Base64.getEncoder()
        val cuerpo = JSONObject()
            .put("challenge_id", reto.id)
            .put("nonce", codificador.encodeToString(reto.nonce))
            .put("signature", codificador.encodeToString(firma))
            .put("device_id", deviceId)
            .put("app_instance_id", appInstanceId)
            .put("app_version", appVersion)
        if (soloSos) cuerpo.put("sos_only", true)
        val respuesta = post("iam/session", cuerpo, esperado = HttpURLConnection.HTTP_OK)
        val segundos = respuesta.getLong("expires_in")
        return SesionEmitida(
            token = respuesta.getString("access_token"),
            caducaEnMillis = System.currentTimeMillis() + segundos * 1_000,
            perfil = perfilDe(respuesta),
            soloSos = respuesta.optString("restricted").isNotBlank(),
        )
    }

    /**
     * `POST iam/session/logout`: el backend invalida el token aunque aun no haya caducado.
     *
     * Con [cerrarAtaduras] cierra antes, en la misma peticion, las ataduras con camara
     * de este agente desde este terminal (§5.4). Solo al cerrar la sesion de verdad:
     * al renovar el token a mitad de turno la camara sigue siendo del agente.
     */
    fun cerrarSesion(token: String, cerrarAtaduras: Boolean = false) {
        val cuerpo = JSONObject()
        if (cerrarAtaduras) cuerpo.put("end_bindings", true)
        post("iam/session/logout", cuerpo, esperado = HttpURLConnection.HTTP_NO_CONTENT, token = token)
    }

    /**
     * `POST iam/bindings` (§6.1): deja escrito en AeriaOne que camara lleva el agente.
     * El agente y el terminal los saca el backend de la sesion, no del cuerpo.
     *
     * Devuelve el `binding_id` del backend: un UUID suyo, distinto del `BIND-…` de la
     * declaracion firmada, y el que pide [terminarAtadura]. Contesta 200 en vez de
     * 201 si esa misma atadura ya estaba registrada, y vale igual.
     */
    fun registrarAtadura(token: String, atadura: BindingActivo): String {
        val cuerpo = JSONObject()
            .put("peripheral_id", atadura.bwcId)
            .put("declaration_id", atadura.bindingId)
            .put("expires_at", atadura.caducaEn)
        val respuesta = post(
            "iam/bindings",
            cuerpo,
            esperado = HttpURLConnection.HTTP_CREATED,
            tambienVale = HttpURLConnection.HTTP_OK,
            token = token,
        )
        return respuesta.getString("binding_id")
    }

    /** `POST iam/bindings/<id>/end` (§6.2). Repetirlo no falla. */
    fun terminarAtadura(token: String, idEnBackend: String) {
        post("iam/bindings/$idEnBackend/end", JSONObject(), esperado = HttpURLConnection.HTTP_OK, token = token)
    }

    private fun post(
        ruta: String,
        cuerpo: JSONObject,
        esperado: Int,
        token: String? = null,
        tambienVale: Int? = null,
    ): JSONObject {
        if (!configurado()) throw IamException("No AeriaOne server configured: scan the enrollment QR")
        val conexion = ConexionBackend.abrir(config.apiUrl() + ruta)
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
            if (codigo != esperado && codigo != tambienVale) {
                throw IamException("AeriaOne answered $codigo: ${motivoDe(texto)}", codigoDeError(texto))
            }
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

    /** `profile` (§5.3): nombre, rango, placa y unidad del agente, iguales en el alta y en la sesion. */
    private fun perfilDe(respuesta: JSONObject): PerfilRecibido? =
        respuesta.optJSONObject("profile")?.let(PerfilDelAgente::desdeJson)

    private fun codigoDeError(texto: String): String? =
        runCatching { JSONObject(texto).optString("code") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Certificado emitido seguido de su cadena, que es lo que instala el Keystore. */
    private fun cadenaPem(respuesta: JSONObject): String {
        val cadena: JSONArray = respuesta.optJSONArray("chain_pem") ?: JSONArray()
        val intermedios = (0 until cadena.length()).map { cadena.getString(it) }
        return (listOf(respuesta.getString("certificate_pem")) + intermedios).joinToString("\n")
    }

    /**
     * `device_ca_pem`: la CA de dispositivos del tenant, la misma que firma a la W1
     * cuando se da de alta. Es la que el telefono necesita para aceptarla al emparejar.
     */
    private fun anclaDePerifericos(respuesta: JSONObject): String? =
        respuesta.optString("device_ca_pem").takeIf { it.isNotBlank() }

    /** `user_ca_pem`: la CA que firma las credenciales de los agentes. */
    private fun caDeAgentes(respuesta: JSONObject): String? =
        respuesta.optString("user_ca_pem").takeIf { it.isNotBlank() }

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
