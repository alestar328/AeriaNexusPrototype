package com.delta.aeria_nexus_prototype.data.upload

import android.util.Base64
import android.util.Log
import com.delta.aeria_nexus_prototype.data.identity.ClaveEnKeystore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Sello `evidence-seal/1` de cada pieza que sube el telefono (aeria-contracts,
 * evidence-seal.md). Lo firma la clave del TERMINAL, nunca la del agente, y se ata al
 * fichero por su `sha256_cipher`: firmar ese hash equivale a firmar el fichero entero
 * sin cambiar el formato FEVD.
 *
 * La firma va sobre los bytes exactos del JSON que viajan en `seal`; el servidor no lo
 * vuelve a canonizar, asi que el orden de las claves y los escapes de org.json dan igual.
 */
data class SelloDeEvidencia(
    val sha256Cipher: String,
    val sha256Plain: String?,
    /** `evidence`, `proxy`, `transcript` o `manifest`, como en la subida. */
    val kind: String,
    val mediaType: String?,
    val incidentId: String?,
    /** El agente que grabo (la autoria sellada) o, sin ella, el de la credencial instalada. */
    val principal: String?,
    /** Vacio si no se sabe: el contrato lo admite cuando quien firma no capturo. */
    val capturedAt: String?,
    /** `goggles` para lo que grabaron las gafas FalconOne y cifra el telefono. */
    val recordedBy: String? = null,
) {

    /**
     * `seal` y `seal_sig` para Upload-Metadata, o nada si el terminal aun no tiene su
     * certificado de AeriaOne. Sin sello la subida sale igual: durante el piloto el
     * servidor lo anota como `absent` y no la rechaza (evidence-seal.md §4).
     */
    fun metadata(): Map<String, String> {
        val deviceId = ClaveEnKeystore.deviceIdDelTerminal() ?: return emptyMap()
        val serie = ClaveEnKeystore.terminal.certificado()?.serialNumber ?: return emptyMap()
        val bytes = json(deviceId, serie.toString(16), ahoraIso()).toByteArray(Charsets.UTF_8)
        val firma = runCatching { ClaveEnKeystore.terminal.firmarReto(bytes) }
            .onFailure { Log.e(TAG, "no se pudo firmar el sello: ${it.message}") }
            .getOrNull() ?: return emptyMap()
        return mapOf(
            // Upload-Metadata vuelve a pasar estos valores a base64: el JSON va en base64
            // dentro para que sus bytes lleguen exactos, que es lo que se ha firmado.
            CLAVE_SELLO to base64(bytes),
            CLAVE_FIRMA to base64(firma),
        )
    }

    /** El JSON tal como se firma. Separado de [metadata] para poder probarlo sin Keystore. */
    internal fun json(deviceId: String, certSerial: String, sealedAt: String): String {
        val sello = JSONObject()
            .put("schema", ESQUEMA)
            .put("sha256_cipher", sha256Cipher)
            .put("sha256_plain", sha256Plain.orEmpty())
            .put("device_id", deviceId)
            .put("cert_serial", certSerial)
            .put("principal", principal.orEmpty())
            // El telefono no tiene asignacion agente-camara: siempre vacio.
            .put("binding_id", "")
            .put("device_incident_id", incidentId.orEmpty())
            .put("captured_at", capturedAt.orEmpty())
            .put("sealed_at", sealedAt)
            .put("kind", kind)
            .put("media_type", mediaType.orEmpty())
        recordedBy?.let { sello.put("recorded_by", it) }
        return sello.toString()
    }

    companion object {
        private const val TAG = "SelloEvidencia"
        const val ESQUEMA = "evidence-seal/1"
        private const val CLAVE_SELLO = "seal"
        private const val CLAVE_FIRMA = "seal_sig"

        /** Clave de los datos de cada pieza donde se guarda cuando se capturo. */
        const val CLAVE_CAPTURA = "captured_at"

        /** Lo que bajo de las gafas lleva este prefijo (LocalEvidenceRepository.createGafasVideoTarget). */
        private const val PREFIJO_GAFAS = "gafas_"

        /** `goggles` si el fichero viene de las gafas, sea el video, su proxy o su transcripcion. */
        fun grabadoPor(nombreDelFichero: String): String? =
            "goggles".takeIf { nombreDelFichero.contains(PREFIJO_GAFAS) }

        /** ISO 8601 en UTC con `Z`, como piden captured_at y sealed_at. */
        fun ahoraIso(): String = iso(System.currentTimeMillis())

        fun iso(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMillis))

        private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
