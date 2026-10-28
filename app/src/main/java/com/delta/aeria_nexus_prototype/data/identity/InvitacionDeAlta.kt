package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import com.delta.aeria_nexus_prototype.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import org.json.JSONObject

/**
 * El QR de alta que genera el panel (aeria-contracts, iam-session.md §4.4).
 *
 * Trae lo que antes se ponia a mano: el servidor (`api_url`, `base_url`), el agente
 * y un codigo de un solo uso. Las dos huellas fijan las CA del despliegue: sin
 * ellas, un servidor impostor podria recibir el codigo y colar su propia CA.
 *
 * [codigo] es null cuando ya se gasto: el resto se conserva para el reintento.
 */
data class InvitacionDeAlta(
    val tenant: String,
    val apiUrl: String,
    val baseUrl: String,
    val userId: String,
    val codigo: String?,
    val caducaEnMillis: Long,
    val huellaCaDeTerminales: String,
    val huellaCaDeAgentes: String,
) {

    /**
     * §4.4 paso 1, antes de mandar el codigo: el `device_ca_pem` recibido es el del
     * QR (huella) y el certificado emitido al terminal lo firmo esa CA (firma).
     */
    fun comprobarCaDeTerminales(caPem: String?, cadenaPem: String) =
        comprobarCa(caPem, huellaCaDeTerminales, cadenaPem, "device")

    /** §4.4 paso 3: lo mismo con la credencial del agente y la CA de agentes. */
    fun comprobarCaDeAgentes(caPem: String?, cadenaPem: String) =
        comprobarCa(caPem, huellaCaDeAgentes, cadenaPem, "officer")

    /** True si [caPem] es la CA de terminales que fija el QR. Solo compara la huella. */
    fun esLaCaDeTerminales(caPem: String): Boolean =
        runCatching { huellaDe(caPem) == huellaCaDeTerminales }.getOrDefault(false)

    private fun comprobarCa(caPem: String?, huella: String, cadenaPem: String, cual: String) {
        if (caPem.isNullOrBlank()) throw ServidorNoVerificado("AeriaOne did not send its $cual CA")
        if (huellaDe(caPem) != huella) {
            throw ServidorNoVerificado("The $cual CA does not match the enrollment QR")
        }
        val ca = Pem.leerCertificados(caPem).first()
        val cadena = Pem.leerCertificados(cadenaPem)
        if (cadena.isEmpty()) throw ServidorNoVerificado("AeriaOne sent no $cual certificate")
        // Cada eslabon firmado por el siguiente, y el ultimo por la CA del QR. Si la
        // cadena ya trae la CA al final, verificarla contra si misma tambien vale.
        cadena.forEachIndexed { i, certificado ->
            val firmante = cadena.getOrNull(i + 1) ?: ca
            if (!firmadoPor(certificado, firmante)) {
                throw ServidorNoVerificado("The $cual certificate is not signed by the CA in the QR")
            }
        }
    }

    private fun firmadoPor(certificado: X509Certificate, firmante: X509Certificate): Boolean =
        runCatching { certificado.verify(firmante.publicKey) }.isSuccess

    fun aJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("tenant", tenant)
        .put("api_url", apiUrl)
        .put("base_url", baseUrl)
        .put("user_id", userId)
        .put("code", codigo ?: JSONObject.NULL)
        .put("expires_at", caducaEnMillis)
        .put("device_ca_sha256", huellaCaDeTerminales)
        .put("user_ca_sha256", huellaCaDeAgentes)

    companion object {
        const val VERSION = "AERIA-ENROLL-1"

        /**
         * Lee el texto del QR. Lanza [QrNoValido] con un mensaje para el agente si no
         * es un QR de alta de AeriaOne o si pide un servidor sin TLS en una version final.
         */
        fun desdeQr(texto: String): InvitacionDeAlta {
            val json = runCatching { JSONObject(texto.trim()) }.getOrNull()
                ?: throw QrNoValido("This is not an AeriaOne enrollment QR.")
            if (json.optString("v") != VERSION) {
                throw QrNoValido("This enrollment QR is from another version of AeriaOne. Update the app.")
            }
            val invitacion = desdeJson(json)
                ?: throw QrNoValido("The enrollment QR is incomplete. Ask your supervisor for a new one.")
            // Sin TLS el codigo se lee en transito. Solo se tolera en depuracion, que es
            // donde el backend corre en la wifi de la oficina.
            val sinTls = listOf(invitacion.apiUrl, invitacion.baseUrl).any { !it.startsWith("https://") }
            if (sinTls && !BuildConfig.DEBUG) {
                throw QrNoValido("The enrollment QR points to a server without HTTPS. It cannot be used.")
            }
            return invitacion
        }

        fun desdeJson(json: JSONObject): InvitacionDeAlta? {
            fun texto(campo: String) = json.optString(campo).trim().takeIf { it.isNotEmpty() }
            return InvitacionDeAlta(
                tenant = texto("tenant") ?: return null,
                apiUrl = conBarraFinal(texto("api_url") ?: return null),
                baseUrl = conBarraFinal(texto("base_url") ?: return null),
                userId = texto("user_id") ?: return null,
                codigo = if (json.isNull("code")) null else texto("code"),
                caducaEnMillis = json.optLong("expires_at"),
                huellaCaDeTerminales = texto("device_ca_sha256")?.lowercase() ?: return null,
                huellaCaDeAgentes = texto("user_ca_sha256")?.lowercase() ?: return null,
            )
        }

        /** SHA-256 en hexadecimal minusculas del DER del primer certificado del PEM. */
        fun huellaDe(pem: String): String {
            val der = Pem.leerCertificados(pem).first().encoded
            return MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        }

        private fun conBarraFinal(url: String) = if (url.endsWith("/")) url else "$url/"
    }
}

/** El texto escaneado no se puede usar. El mensaje se ensena tal cual al agente. */
class QrNoValido(message: String) : Exception(message)

/** El servidor no ha demostrado ser el del QR (§4.4). El mensaje se ensena tal cual. */
class ServidorNoVerificado(message: String) : Exception(message)

/**
 * Donde se guarda el QR entre las dos fases del alta: la app puede morir entre una y
 * otra, y el agente no deberia tener que volver a escanearlo.
 *
 * En noBackupFilesDir: el QR es de este telefono, y restaurarlo en otro por la copia
 * de seguridad de Android le daria un codigo y un servidor que no son suyos.
 */
class AlmacenDeInvitacion(context: Context) {

    private val fichero = File(context.noBackupFilesDir, "enrollment_invite.json")

    @Volatile private var enMemoria: InvitacionDeAlta? = null
    @Volatile private var leida = false

    fun actual(): InvitacionDeAlta? {
        if (!leida) {
            enMemoria = runCatching {
                fichero.takeIf { it.isFile }?.readText()?.let { InvitacionDeAlta.desdeJson(JSONObject(it)) }
            }.getOrNull()
            leida = true
        }
        return enMemoria
    }

    fun guardar(invitacion: InvitacionDeAlta) {
        fichero.writeText(invitacion.aJson().toString())
        enMemoria = invitacion
        leida = true
    }

    /** §4.4: el codigo se borra al recibir el 201 o el 200; el resto se conserva. */
    fun olvidarCodigo() {
        actual()?.let { guardar(it.copy(codigo = null)) }
    }

    fun borrar() {
        fichero.delete()
        enMemoria = null
        leida = true
    }
}
