package com.delta.aeria_nexus_prototype.data.identity

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Identidad criptografica del terminal (workflow 12, pasos 10, 15 y 16).
 *
 * La clave privada se genera DENTRO del Keystore y nunca sale: no hay ninguna
 * funcion aqui que la devuelva, y no es un descuido. Lo unico que se puede hacer
 * con ella es pedirle que firme. Ese es el sentido de todo el workflow: el
 * backend no confia en que digamos quienes somos, confia en que podemos firmar
 * un reto que acaba de inventarse.
 *
 * Sobre la decision D2 (como se protege la clave), que sigue abierta: aqui NO se
 * llama a `setUserAuthenticationRequired`. Atar la clave a la credencial del
 * sistema significa que sin patron de pantalla no hay identidad, y que el agente
 * mete dos secretos por turno. Cuando D2 se cierre, se cambia en este punto y en
 * ningun otro.
 */
object DeviceKeystore {

    internal const val PROVEEDOR = "AndroidKeyStore"

    /** Alias de la clave del TELEFONO. La del agente sera otra (workflow 3). */
    private const val ALIAS = "aeria.device.key"

    private val keystore: KeyStore
        get() = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    fun existe(): Boolean = runCatching { keystore.containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Genera el par del terminal, preferiblemente respaldado por hardware.
     *
     * [retoDeAtestacion] lo manda el backend y viaja dentro del certificado de
     * atestacion que emite el propio dispositivo: es lo que impide reutilizar una
     * atestacion vieja o la de otro telefono. Sin reto no hay atestacion util.
     *
     * Se intenta por orden: StrongBox con atestacion, TEE con atestacion y TEE sin
     * atestacion. Los tres escalones existen porque hay terminales de campo que
     * fallan en cada uno de ellos; quedarse sin clave por eso seria peor que
     * quedarse sin atestacion, que el backend puede exigir o no por politica.
     */
    fun generarPar(retoDeAtestacion: ByteArray): KeyPair {
        val intentos = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(Intento(strongBox = true, conAtestacion = true))
            }
            add(Intento(strongBox = false, conAtestacion = true))
            add(Intento(strongBox = false, conAtestacion = false))
        }

        var ultimoFallo: Exception? = null
        intentos.forEach { intento ->
            try {
                return generar(intento, retoDeAtestacion)
            } catch (e: StrongBoxUnavailableException) {
                ultimoFallo = e
            } catch (e: java.security.ProviderException) {
                // Varios terminales lanzan ProviderException cuando la atestacion
                // no esta soportada o la clave de atestacion del fabricante caduco.
                ultimoFallo = e
            }
        }
        throw IllegalStateException("No se pudo generar la clave del terminal", ultimoFallo)
    }

    private fun generar(intento: Intento, reto: ByteArray): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVA))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (intento.conAtestacion) setAttestationChallenge(reto)
                if (intento.strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVEEDOR)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    fun clavePublica(): PublicKey? = keystore.getCertificate(ALIAS)?.publicKey

    /**
     * Manejador de la clave privada. No contiene la clave: es una referencia que
     * solo sirve para pedirle operaciones al Keystore.
     */
    fun manejadorDeClavePrivada(): PrivateKey? = keystore.getKey(ALIAS, null) as? PrivateKey

    /**
     * Donde ha quedado la clave de verdad, preguntado al sistema una vez creada.
     * Esto confirma o desmiente lo que declaraba [DeviceInspector.postura].
     */
    fun nivelDeLaClave(): NivelClave {
        val privada = manejadorDeClavePrivada() ?: return NivelClave.DESCONOCIDO
        val info = runCatching {
            KeyFactory.getInstance(privada.algorithm, PROVEEDOR)
                .getKeySpec(privada, KeyInfo::class.java)
        }.getOrNull() ?: return NivelClave.DESCONOCIDO

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> NivelClave.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> NivelClave.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> NivelClave.SOFTWARE
                else -> NivelClave.DESCONOCIDO
            }
        }
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) NivelClave.TEE else NivelClave.SOFTWARE
    }

    /**
     * Cadena de atestacion que emite el propio dispositivo al crear la clave.
     *
     * Va firmada hasta una raiz de Google e incluye el reto, el estado del arranque
     * verificado y donde vive la clave. Es lo que el backend verifica en los pasos
     * 5 y 6; nosotros solo la transportamos, no la interpretamos.
     */
    fun cadenaDeAtestacion(): List<X509Certificate> =
        keystore.getCertificateChain(ALIAS)?.filterIsInstance<X509Certificate>().orEmpty()

    /**
     * Sustituye la cadena de certificados de la clave por la que emitio la CA
     * (paso 15). El par no se toca: se reutiliza el mismo manejador, asi que la
     * clave privada sigue donde estaba y sin haber salido de alli.
     */
    fun instalarCadena(cadena: List<X509Certificate>) {
        require(cadena.isNotEmpty()) { "La cadena del certificado del terminal viene vacia" }
        val privada = manejadorDeClavePrivada()
            ?: error("No hay clave del terminal: hay que generarla antes de instalar su certificado")
        keystore.setKeyEntry(ALIAS, privada, null, cadena.toTypedArray())
    }

    /** Certificado del terminal, ya emitido por la CA. Null si aun no se instalo. */
    fun certificado(): X509Certificate? = keystore.getCertificate(ALIAS) as? X509Certificate

    /** Prueba de posesion: se firma el reto del backend (paso 16). */
    fun firmarReto(reto: ByteArray): ByteArray {
        val privada = manejadorDeClavePrivada() ?: error("No hay clave del terminal")
        return Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initSign(privada)
            update(reto)
            sign()
        }
    }

    /**
     * Destruye la identidad del terminal. Es lo que exige el §13 al resetear:
     * "reset must destroy/invalidate old local identity".
     */
    fun borrar() {
        runCatching { keystore.deleteEntry(ALIAS) }
    }

    private data class Intento(val strongBox: Boolean, val conAtestacion: Boolean)

    private const val CURVA = "secp256r1"
}
