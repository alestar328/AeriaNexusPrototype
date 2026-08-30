package com.delta.aeria_nexus_prototype.data.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "EvidenceVault"

/**
 * Bóveda de evidencia del agente: la contraseña que hace falta para volver a ver en
 * el teléfono una foto, un vídeo o una nota de audio ya capturados.
 *
 * ── Por qué un par de claves y no la contraseña directamente ──────────────────
 *
 * La evidencia se cifra al cerrar la captura, en mitad de un servicio, sin nadie
 * delante: en ese momento no hay contraseña que pedir. Por eso la bóveda es un par
 * RSA-2048 propio del dispositivo:
 *
 *   cifrar    → con la **pública**, siempre disponible, sin contraseña
 *   descifrar → con la **privada**, que solo se puede reconstruir con la contraseña
 *
 * La privada se guarda en `vault.key` protegida en dos capas: primero AES-GCM con
 * una clave derivada de la contraseña (PBKDF2-HMAC-SHA256), y ese resultado envuelto
 * otra vez por la clave de AndroidKeyStore ([DeviceKeyWrapper]). La segunda capa es
 * lo que impide sacar el fichero del teléfono y probar contraseñas en un PC.
 *
 * ── Consecuencia que hay que tener presente ───────────────────────────────────
 *
 * Si se olvida la contraseña no hay forma de recuperar la evidencia local: es el
 * precio de que tampoco pueda recuperarla quien robe el teléfono. La copia que
 * recibe Nexus sigue siendo abrible por el servidor (ver [NexusKeyWrapper]), así que
 * la cadena de custodia no depende de esta contraseña.
 *
 * La contraseña nunca se guarda, ni en claro ni en hash: si es la correcta, el
 * descifrado de la privada cuadra; si no, falla la autenticación de AES-GCM.
 */
object EvidenceVault {

    /** Mínimo de caracteres de la contraseña. Se teclea en campo, a veces con guantes. */
    const val LONGITUD_MINIMA = 6

    /**
     * Identificador de este destinatario en la cabecera del .fev. Permite saber si
     * un fichero se cifró para esta bóveda sin tener que descifrarlo.
     */
    const val WRAPPER_ID = "vault:v1"

    private const val FILE_NAME = "vault.key"
    private const val VERSION = 1
    // Recomendación de OWASP para PBKDF2-HMAC-SHA256. Cuesta un par de décimas de
    // segundo en el teléfono y encarece cada intento de adivinar la contraseña.
    private const val ITERACIONES = 210_000
    private const val SALT_BYTES = 16
    private const val CLAVE_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val RSA_BITS = 2048

    private var archivo: File? = null
    private var publica: PublicKey? = null

    // La privada solo vive en memoria mientras la bóveda está desbloqueada; al
    // bloquear se suelta y hay que volver a teclear la contraseña.
    private var privada: PrivateKey? = null

    private val _desbloqueada = MutableStateFlow(false)
    val desbloqueada: StateFlow<Boolean> = _desbloqueada.asStateFlow()

    private val _configurada = MutableStateFlow(false)
    val configurada: StateFlow<Boolean> = _configurada.asStateFlow()

    /** Carga la parte pública, que es lo único que se necesita para cifrar. */
    fun init(context: Context) {
        val fichero = File(context.filesDir, FILE_NAME)
        archivo = fichero
        publica = if (fichero.isFile) leerPublica(fichero) else null
        _configurada.value = publica != null
    }

    /**
     * Destinatario de la DEK que representa a este agente, o null si todavía no ha
     * creado su contraseña. Lo consume [EvidenceKeys.recipients].
     */
    fun wrapper(): KeyWrapper? = if (publica != null) wrapper else null

    /**
     * Crea la bóveda con [contrasena] y la deja desbloqueada. Bloquea el hilo unos
     * cientos de milisegundos entre la derivación y el par RSA: llamar desde una
     * corrutina en Dispatchers.Default.
     *
     * Devuelve false si ya existe una bóveda: sobrescribirla dejaría ilegible toda
     * la evidencia cifrada hasta ahora.
     */
    fun configurar(contrasena: String): Boolean {
        val fichero = archivo ?: return false
        if (fichero.isFile) {
            Log.w(TAG, "ya hay una bóveda creada: no se sobrescribe")
            return false
        }
        return try {
            val par = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(RSA_BITS) }
                .generateKeyPair()
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val protegida = cifrarConContrasena(par.private.encoded, contrasena, salt)

            val json = JSONObject().apply {
                put("version", VERSION)
                put("salt", codificar(salt))
                put("iterations", ITERACIONES)
                put("private_key", codificar(DeviceKeyWrapper.wrap(protegida)))
                put("public_key", codificar(par.public.encoded))
            }
            fichero.writeText(json.toString())

            publica = par.public
            privada = par.private
            _configurada.value = true
            _desbloqueada.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo crear la bóveda: ${e.message}", e)
            fichero.delete()
            false
        }
    }

    /**
     * Reconstruye la clave privada con [contrasena]. Devuelve false si la contraseña
     * no es la correcta. Igual que [configurar], no se llama desde el hilo principal.
     */
    fun desbloquear(contrasena: String): Boolean {
        val fichero = archivo?.takeIf { it.isFile } ?: return false
        return try {
            val json = JSONObject(fichero.readText())
            val salt = decodificar(json.getString("salt"))
            val protegida = DeviceKeyWrapper.unwrap(decodificar(json.getString("private_key")))
                ?: return false
            val pkcs8 = descifrarConContrasena(
                blob = protegida,
                contrasena = contrasena,
                salt = salt,
                iteraciones = json.getInt("iterations"),
            ) ?: return false

            privada = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            Arrays.fill(pkcs8, 0)
            _desbloqueada.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo abrir la bóveda: ${e.message}", e)
            false
        }
    }

    /** Suelta la clave privada: a partir de aquí hace falta la contraseña otra vez. */
    fun bloquear() {
        privada = null
        _desbloqueada.value = false
    }

    /**
     * Destinatario "agente": cifra siempre con la pública y solo descifra mientras la
     * bóveda esté desbloqueada. Es un objeto anidado y no una clase suelta porque su
     * estado es exactamente el de la bóveda.
     */
    private val wrapper = object : KeyWrapper {

        override val id: String = WRAPPER_ID

        override fun wrap(dek: ByteArray): ByteArray =
            rsaOaepWrap(requireNotNull(publica) { "bóveda sin configurar" }, dek)

        override fun unwrap(blob: ByteArray): ByteArray? {
            val clave = privada ?: run {
                Log.w(TAG, "bóveda bloqueada: hace falta la contraseña del agente")
                return null
            }
            return try {
                rsaOaepUnwrap(clave, blob)
            } catch (e: Exception) {
                // Fichero cifrado para otra bóveda (otro teléfono, u otra contraseña
                // creada tras borrar los datos de la app).
                Log.w(TAG, "este .fev no es de esta bóveda: ${e.message}")
                null
            }
        }
    }

    private fun leerPublica(fichero: File): PublicKey? = try {
        val json = JSONObject(fichero.readText())
        val spki = decodificar(json.getString("public_key"))
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
    } catch (e: Exception) {
        Log.e(TAG, "vault.key ilegible: ${e.message}", e)
        null
    }

    /** blob = IV(12) ‖ ciphertext ‖ tag(16), con la clave derivada de la contraseña. */
    private fun cifrarConContrasena(claro: ByteArray, contrasena: String, salt: ByteArray): ByteArray {
        val clave = derivar(contrasena, salt, ITERACIONES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, clave)
        return cipher.iv + cipher.doFinal(claro)
    }

    /** Null cuando la contraseña no es la correcta: falla el tag de AES-GCM. */
    private fun descifrarConContrasena(
        blob: ByteArray,
        contrasena: String,
        salt: ByteArray,
        iteraciones: Int,
    ): ByteArray? = try {
        val clave = derivar(contrasena, salt, iteraciones)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    } catch (e: Exception) {
        Log.w(TAG, "contraseña incorrecta")
        null
    }

    private fun derivar(contrasena: String, salt: ByteArray, iteraciones: Int): SecretKeySpec {
        val spec = PBEKeySpec(contrasena.toCharArray(), salt, iteraciones, CLAVE_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()
        val clave = SecretKeySpec(bytes, "AES")
        Arrays.fill(bytes, 0)
        return clave
    }

    private fun codificar(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodificar(texto: String): ByteArray = Base64.getDecoder().decode(texto)
}
