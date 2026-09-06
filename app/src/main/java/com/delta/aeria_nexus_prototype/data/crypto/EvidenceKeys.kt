package com.delta.aeria_nexus_prototype.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
// java.util.Base64 requiere API 26 y el minSdk de esta app es exactamente 26, asi
// que esta disponible. Si alguna vez se bajase el minSdk, aqui hay que cambiar a
// android.util.Base64 (Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)).
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

private const val TAG = "FalconKeys"

/**
 * Custodia de la clave de evidencia.
 *
 * Cada fichero se cifra con una DEK propia y aleatoria (ver [EvidenceCrypto]); lo
 * que se decide aquí es **quién puede recuperar esa DEK**. Un [KeyWrapper] es un
 * destinatario: envuelve la DEK con su propia clave y guarda el resultado en la
 * cabecera del .fev.
 *
 * La lista de destinatarios es la costura que deja abierta la decisión de custodia
 * pendiente con el manager. Añadir o quitar destinatarios no cambia ni un byte del
 * formato de los bloques cifrados:
 *
 *   solo servidor  → opción B de Seguridad-Claves-Bodycam.md §3.5 (nadie descifra
 *                    en el dispositivo, ni siquiera quien lo tenga en la mano)
 *   ambos          → opción C, híbrido: el servidor descifra y el dispositivo además
 *                    puede reproducir lo que grabó
 *
 * Ver docs/CRYPTO-FORMAT.md §3 (repo BodyCamServer).
 */
interface KeyWrapper {

    /** Identificador ASCII que se graba en la cabecera. Dice quién puede abrir. */
    val id: String

    fun wrap(dek: ByteArray): ByteArray

    /** DEK en claro, o null si este destinatario no es capaz de abrir el blob. */
    fun unwrap(blob: ByteArray): ByteArray?
}

object EvidenceKeys {

    /**
     * Destinatarios de la DEK, en orden de preferencia al descifrar.
     *
     * Esto es la opción C (híbrido) de Seguridad-Claves-Bodycam.md §3.5: el servidor
     * puede abrir toda la evidencia, y el agente puede revisar en el propio teléfono
     * lo que ha capturado él, pero solo tras desbloquear la bóveda con su contraseña
     * (ver [EvidenceVault]).
     *
     * Mientras el agente no configure la bóveda solo queda el destinatario de Nexus:
     * la captura se cifra y se entrega igual, simplemente no es revisable en local.
     */
    fun recipients(): List<KeyWrapper> =
        listOfNotNull(EvidenceVault.wrapper(), NexusKeyWrapper.fromApk())

    /** El primero de [recipients] capaz de abrir uno de los envoltorios del fichero. */
    fun unwrap(wraps: List<Pair<String, ByteArray>>): ByteArray? {
        for (r in recipients()) {
            val blob = wraps.firstOrNull { it.first == r.id }?.second ?: continue
            r.unwrap(blob)?.let { return it }
        }
        Log.e(
            TAG,
            "ningún destinatario local puede abrir este fichero: " +
                wraps.joinToString { it.first }
        )
        return null
    }
}

/**
 * Cifra y descifra con una clave AES-256 de AndroidKeyStore que **no sale del
 * dispositivo**: no hay ningún secreto en el APK que filtrar.
 *
 * Su trabajo es que un fichero protegido por una contraseña o un PIN no se pueda
 * atacar fuera de este teléfono: envuelto además por el Keystore, adivinarlos
 * obliga a hacerlo aquí y uno por uno. Copiando el fichero a un PC no se puede
 * hacer fuerza bruta contra él, que es el ataque realista contra un secreto corto.
 *
 * Hay **dos instancias con dos claves distintas**, y no es cosmética: el documento
 * de arquitectura pide claves separadas por propósito e independientemente
 * revocables (IAM-05). Comprometer la evidencia no puede comprometer la identidad.
 *
 * blob = IV(12) ‖ ciphertext ‖ tag(16).
 *
 * No se pide autenticación de usuario (setUserAuthenticationRequired): la clave de
 * evidencia se usa al cerrar una captura, sin nadie delante. Quien pone al agente
 * delante es la contraseña de la bóveda o el PIN, según el caso.
 */
class DeviceKeyWrapper private constructor(private val alias: String) {

    private val STORE = "AndroidKeyStore"
    private val IV_BYTES = 12
    private val TAG_BITS = 128

    fun wrap(claro: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key()) { "sin clave de Keystore ($alias)" })
        val sellado = cipher.doFinal(claro)
        return cipher.iv + sellado
    }

    fun unwrap(blob: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireNotNull(key()) { "sin clave de Keystore ($alias)" },
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
        )
        cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    } catch (e: Exception) {
        // Esperable si la clave se perdió al reinstalar el sistema o al borrar los
        // datos de la app. No es un fallo del formato.
        Log.w(TAG, "no se pudo abrir el envoltorio de Keystore ($alias): ${e.message}")
        null
    }

    /** Crea la clave la primera vez y la reutiliza siempre. */
    @Synchronized
    private fun key(): SecretKey? = try {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: generate()
    } catch (e: Exception) {
        Log.e(TAG, "Keystore no disponible: ${e.message}")
        null
    }

    private fun generate(): SecretKey {
        Log.d(TAG, "generando clave de Keystore ($alias)")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    companion object {

        /** Protege el fichero de claves de la bóveda de evidencia ([EvidenceVault]). */
        val evidencia = DeviceKeyWrapper("aeria_evidence_v1")

        /** Protege el verificador del PIN del agente (workflow 4). Otra clave, a propósito. */
        val identidad = DeviceKeyWrapper("aeria_identity_v1")
    }
}

/**
 * Envuelve la DEK con la **clave pública** de Nexus (RSA-OAEP/SHA-256), de forma que
 * solo el servidor pueda abrirla. Es la opción recomendada en
 * Seguridad-Claves-Bodycam.md §3.5: una clave pública embebida en el APK no es una
 * filtración, es su sitio natural, y elimina de raíz el problema de los secretos en
 * el APK en vez de mitigarlo.
 *
 * ── Hoy va con una clave de DESARROLLO ────────────────────────────────────────
 *
 * La pública real de Nexus sigue pendiente (decisión 1 del plan de la semana 24-30
 * ago): la genera ciberseguridad en su infraestructura y nos entrega solo la
 * pública, porque quien tenga la privada puede descifrar toda la evidencia y eso
 * no puede acabar en manos del proveedor de software.
 *
 * Mientras tanto, y siguiendo lo que pidió backend —stubs en vez de bloqueos—, aquí
 * hay un par RSA-2048 de usar y tirar cuyo `kid` empieza por `dev-`. La privada está
 * en `tools/dev-keys/` del repo de BodyCamServer, fuera del control de versiones.
 *
 * **Tiene que ser exactamente la misma clave y el mismo `kid` que la unidad
 * bodycam.** Si divergen, la evidencia del teléfono y la de la unidad dejan de ser
 * abribles por el mismo destinatario y el formato compartido pierde su sentido.
 *
 * **El `kid` es la salvaguarda:** cualquier cosa cifrada para `dev-*` es material de
 * pruebas por definición. Sustituirla el día que llegue la real es cambiar estas dos
 * constantes; no toca ni el formato ni [EvidenceCrypto].
 */
class NexusKeyWrapper private constructor(
    private val kid: String,
    private val spki: ByteArray,
) : KeyWrapper {

    override val id: String = "srv:$kid"

    override fun wrap(dek: ByteArray): ByteArray {
        val publica = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
        return rsaOaepWrap(publica, dek)
    }

    /** La privada vive en el servidor: el teléfono no puede deshacer este envoltorio. */
    override fun unwrap(blob: ByteArray): ByteArray? = null

    companion object {
        /** El prefijo `dev-` marca que esto NO es la clave de producción. */
        private const val KEY_ID = "dev-2026-08"

        private const val PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApfQAeE9GxRa0424gB/zK7OB32ssLrslezEx3" +
            "ewMxOHPgKo7nPYvXGxcq9Wh2e3vfNyo1QkNpyVGF1J1cKieBEVEwZIpJT8INFQotwmTGzVOjqogRb9r4" +
            "giRW1AugkrA2rDNkRR/s5/770PuvrxDE/2F7PcMMQlBL/Dl+Tl6/l1zYb51QMvJ89w/eku7TAe7aD4Cx" +
            "9/lOaG0IQKqRd/zeuG0hubLDJC8ND9ARy8a47K9/Vgnl3uhrlE01HU37muzhbOCyMB9dxlZ6fLjTtfKG" +
            "VctsxIyD62iOYlQJ6XJJfbLv4HsxNZTz1iwnB11FXgKnbps/NXAdOPfYz5pyo7wSuQIDAQAB"

        fun fromApk(): NexusKeyWrapper? {
            if (PUBLIC_KEY_B64.isBlank() || KEY_ID.isBlank()) return null
            return try {
                NexusKeyWrapper(KEY_ID, Base64.getDecoder().decode(PUBLIC_KEY_B64))
            } catch (e: Exception) {
                Log.e(TAG, "la pública de Nexus no es un SPKI válido: ${e.message}")
                null
            }
        }
    }
}

/**
 * RSA-OAEP con SHA-256, compartido por los destinatarios de clave pública (Nexus y
 * la bóveda del agente).
 *
 * Los parámetros van explícitos y no como "OAEPWithSHA-256AndMGF1Padding": ese
 * nombre deja MGF1 en SHA-1 en varias versiones de Android, y entonces lo que cifra
 * el teléfono no lo descifra el servidor.
 */
internal fun rsaOaepWrap(publica: PublicKey, claro: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
    cipher.init(Cipher.ENCRYPT_MODE, publica, oaepParams())
    return cipher.doFinal(claro)
}

internal fun rsaOaepUnwrap(privada: PrivateKey, blob: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
    cipher.init(Cipher.DECRYPT_MODE, privada, oaepParams())
    return cipher.doFinal(blob)
}

private fun oaepParams() =
    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
