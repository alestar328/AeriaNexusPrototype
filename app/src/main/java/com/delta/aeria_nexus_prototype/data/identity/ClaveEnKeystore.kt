package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.io.File
import java.util.Properties

/**
 * Una clave del Keystore de Android, identificada por su alias.
 *
 * La clave privada se genera DENTRO del Keystore y nunca sale: no hay ninguna
 * funcion aqui que la devuelva, y no es un descuido. Lo unico que se puede hacer
 * con ella es pedirle que firme. Ese es el sentido de los workflows 3 y 12: el
 * backend no confia en que digamos quienes somos, confia en que podemos firmar
 * un reto que acaba de inventarse.
 *
 * Hay dos instancias y son deliberadamente independientes, porque el documento de
 * arquitectura lo exige en sus reglas de no equivalencia: "user certificate is not
 * the device certificate". Revocar al agente no puede dejar inservible el
 * telefono, y retirar el telefono no puede borrar la identidad del agente.
 *
 * Sobre la decision D2 (como se protege la clave del agente), que sigue abierta:
 * aqui NO se llama a `setUserAuthenticationRequired`. Atar la clave a la
 * credencial del sistema significa que sin patron de pantalla no hay identidad, y
 * que el agente mete dos secretos por turno. Cuando D2 se cierre, se cambia en
 * [generar] y en ningun otro sitio.
 */
internal class ClaveEnKeystore private constructor(
    /** Alias de la primera generacion. Las renovaciones van en `<base>.r<n>`. */
    private val base: String,
    /** 0 la del alta; cada renovacion suma uno (credential-lifecycle.md §1). */
    private val generacion: Int,
    /** Como se nombra esta clave en los mensajes de error, p. ej. "del agente". */
    private val descripcion: String,
) {

    private val alias: String = aliasDe(base, generacion)

    private val keystore: KeyStore
        get() = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    fun existe(): Boolean = runCatching { keystore.containsAlias(alias) }.getOrDefault(false)

    /**
     * Genera el par, preferiblemente respaldado por hardware.
     *
     * [retoDeAtestacion] solo aplica a la clave del terminal: viaja dentro de la
     * cadena de atestacion que emite el propio dispositivo y es lo que impide
     * reutilizar una atestacion vieja o la de otro telefono. La clave del agente
     * se genera sin reto porque la atestacion acredita la plataforma, no a la
     * persona, y la plataforma ya quedo acreditada en el alta del terminal.
     *
     * Se intenta en varios escalones, de mas a menos garantia. Existen porque hay
     * terminales de campo que fallan en cada uno de ellos; quedarse sin clave por
     * eso seria peor que quedarse sin atestacion, que el backend puede exigir o no
     * por politica.
     */
    fun generarPar(retoDeAtestacion: ByteArray? = null): KeyPair {
        var ultimoFallo: Exception? = null
        intentos(conAtestacion = retoDeAtestacion != null).forEach { intento ->
            try {
                return generar(intento, retoDeAtestacion)
            } catch (e: java.security.ProviderException) {
                // Aqui cae tambien StrongBoxUnavailableException, que hereda de
                // ProviderException. NO se captura por su nombre: esa clase existe
                // desde API 28 y el minSdk es 26, asi que nombrarla en un catch
                // hace que el verificador de ART no resuelva el metodo en Android
                // 8.0/8.1 y reviente al entrar, antes siquiera de generar la clave.
                // Ademas varios terminales lanzan ProviderException a secas cuando
                // la atestacion no esta soportada o la clave del fabricante caduco.
                ultimoFallo = e
            }
        }
        throw IllegalStateException("No se pudo generar la clave $descripcion", ultimoFallo)
    }

    private fun intentos(conAtestacion: Boolean): List<Intento> = buildList {
        val haySoporteStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (conAtestacion) {
            // Con reto se prefiere una clave atestada en el TEE a una sin atestar
            // en StrongBox: sin atestacion el backend no puede comprobar nada.
            if (haySoporteStrongBox) add(Intento(strongBox = true, conAtestacion = true))
            add(Intento(strongBox = false, conAtestacion = true))
        } else if (haySoporteStrongBox) {
            add(Intento(strongBox = true, conAtestacion = false))
        }
        add(Intento(strongBox = false, conAtestacion = false))
    }

    private fun generar(intento: Intento, reto: ByteArray?): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVA))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (intento.conAtestacion && reto != null) setAttestationChallenge(reto)
                if (intento.strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVEEDOR)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    fun clavePublica(): PublicKey? = keystore.getCertificate(alias)?.publicKey

    /**
     * Manejador de la clave privada. No contiene la clave: es una referencia que
     * solo sirve para pedirle operaciones al Keystore.
     */
    fun manejadorDeClavePrivada(): PrivateKey? = keystore.getKey(alias, null) as? PrivateKey

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
     * Peticion de certificado para esta clave, en DER (workflow 12 paso 11,
     * workflow 3 paso 7). Se devuelve el DER y no el PEM porque hay quien tiene
     * que firmarlo ademas de enviarlo: ver [CredentialRepository].
     */
    fun crearCsr(sujeto: SujetoCsr): ByteArray {
        val publica = clavePublica() ?: error("No hay clave $descripcion")
        val privada = manejadorDeClavePrivada() ?: error("No hay clave $descripcion")
        return Pkcs10.crear(sujeto, publica, privada)
    }

    /**
     * Cadena de certificados asociada al alias.
     *
     * Antes de que la CA emita, es la cadena de atestacion que puso el propio
     * dispositivo: va firmada hasta una raiz de Google e incluye el reto, el
     * estado del arranque verificado y donde vive la clave. Es lo que el backend
     * verifica en los pasos 5 y 6 del workflow 12; nosotros solo la
     * transportamos, no la interpretamos. Despues de [instalarCadena] es ya la
     * cadena del certificado emitido.
     */
    fun cadenaDeCertificados(): List<X509Certificate> =
        keystore.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()

    /**
     * Sustituye la cadena de certificados por la que emitio la CA. El par no se
     * toca: se reutiliza el mismo manejador, asi que la clave privada sigue donde
     * estaba y sin haber salido de alli.
     */
    private fun instalarCadena(cadena: List<X509Certificate>) {
        require(cadena.isNotEmpty()) { "La cadena del certificado $descripcion viene vacia" }
        val privada = manejadorDeClavePrivada()
            ?: error("No hay clave $descripcion que certificar: hay que generarla antes")
        keystore.setKeyEntry(alias, privada, null, cadena.toTypedArray())
    }

    /**
     * Instala el certificado que devolvio la CA, comprobando antes que es de esta
     * clave (workflow 12 paso 15, workflow 3 paso 11). Acepta uno o varios
     * certificados en PEM, de la hoja hacia la raiz, y devuelve la hoja.
     *
     * La comprobacion de la clave publica no es una formalidad: instalar aqui el
     * certificado de otro par dejaria el Keystore en un estado incoherente que
     * solo se descubriria mucho despues, al fallar una firma.
     */
    fun instalarCertificadoEmitido(pemDeLaCadena: String): X509Certificate {
        val cadena = Pem.leerCertificados(pemDeLaCadena)
        require(cadena.isNotEmpty()) { "No se encontro ningun certificado en el PEM" }

        val hoja = cadena.first()
        val publicaDelKeystore = clavePublica() ?: error("No hay clave $descripcion")
        require(hoja.publicKey.encoded.contentEquals(publicaDelKeystore.encoded)) {
            "El certificado no corresponde a la clave $descripcion"
        }

        instalarCadena(cadena)
        return hoja
    }

    /** Certificado ya emitido por la CA. Null si aun no se instalo. */
    fun certificado(): X509Certificate? = keystore.getCertificate(alias) as? X509Certificate

    /**
     * Nombre comun del sujeto del certificado, extraido del DN impreso
     * ("O=AeriaOne, OU=QPD, CN=DEV-74435826"). Es suficiente para lo que se usa y
     * evita traerse un lector de X.500 entero por un campo.
     */
    fun commonNameDelCertificado(): String? = certificado()
        ?.subjectX500Principal
        ?.name
        ?.split(",")
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("CN=") }
        ?.removePrefix("CN=")

    /** Prueba de posesion: se firma el reto del backend sin exponer la clave. */
    fun firmarReto(reto: ByteArray): ByteArray {
        val privada = manejadorDeClavePrivada() ?: error("No hay clave $descripcion")
        return Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initSign(privada)
            update(reto)
            sign()
        }
    }

    /**
     * Destruye la clave y todas sus generaciones. Es lo que exige el §13 al resetear:
     * "reset must destroy/invalidate old local identity".
     */
    fun borrar() {
        aliasDeLaFamilia(base).forEach { viejo -> runCatching { keystore.deleteEntry(viejo) } }
        runCatching { fijarGeneracion(base, 0) }
    }

    /** Solo esta generacion: la de una renovacion que no llego a terminar. */
    fun borrarSoloEsta() {
        runCatching { keystore.deleteEntry(alias) }
    }

    /**
     * Par nuevo para renovar el certificado (credential-lifecycle.md §1.2). No pasa a
     * ser la vigente hasta [promover]: mientras, la vieja sigue firmando y abriendo sesion.
     * Lo que quedase de una renovacion a medias se borra antes.
     */
    fun siguienteGeneracion(): ClaveEnKeystore {
        aliasDeLaFamilia(base)
            .filter { generacionDe(base, it) > generacion }
            .forEach { sobrante -> runCatching { keystore.deleteEntry(sobrante) } }
        return ClaveEnKeystore(base, generacion + 1, descripcion)
    }

    /** Esta generacion, ya con su certificado instalado, pasa a ser la vigente. */
    fun promover() {
        fijarGeneracion(base, generacion)
    }

    /**
     * Borra las generaciones anteriores a esta. Se llama cuando la nueva ya se ha usado
     * con exito: hasta entonces la vieja es la red si algo sale mal (§1.3).
     */
    fun borrarAnteriores() {
        aliasDeLaFamilia(base)
            .filter { generacionDe(base, it) < generacion }
            .forEach { viejo -> runCatching { keystore.deleteEntry(viejo) } }
    }

    /** True si hay alguna generacion anterior pendiente de borrar. */
    fun tieneAnteriores(): Boolean = aliasDeLaFamilia(base).any { generacionDe(base, it) < generacion }

    private fun aliasDeLaFamilia(base: String): List<String> =
        runCatching { keystore.aliases().toList() }.getOrDefault(emptyList())
            .filter { it == base || it.startsWith("$base$SEPARADOR") }

    private data class Intento(val strongBox: Boolean, val conAtestacion: Boolean)

    companion object {

        const val PROVEEDOR = "AndroidKeyStore"

        private const val CURVA = "secp256r1"

        private const val BASE_TERMINAL = "aeria.device.key"
        private const val BASE_AGENTE = "aeria.user.key"
        private const val SEPARADOR = ".r"

        /**
         * Que generacion de cada clave es la vigente. El Keystore no deja renombrar una
         * entrada, asi que renovar crea otra con alias nuevo y esto apunta a ella. En
         * noBackupFilesDir: como la clave, no debe viajar a otro telefono. Sin fichero
         * (telefonos anteriores a la renovacion) la vigente es la primera.
         */
        @Volatile
        private var ficheroDeGeneraciones: File? = null

        /** Lo llama AppContainer antes de que nadie use una clave. */
        fun iniciar(context: Context) {
            ficheroDeGeneraciones = File(context.noBackupFilesDir, "claves_vigentes.properties")
        }

        /** Identidad del TELEFONO (workflow 12). Se genera con reto de atestacion. */
        val terminal: ClaveEnKeystore
            get() = ClaveEnKeystore(BASE_TERMINAL, generacionVigente(BASE_TERMINAL), "del terminal")

        /** Identidad del AGENTE (workflow 3). Nunca es la misma que la del terminal. */
        val agente: ClaveEnKeystore
            get() = ClaveEnKeystore(BASE_AGENTE, generacionVigente(BASE_AGENTE), "del agente")

        private fun aliasDe(base: String, generacion: Int): String =
            if (generacion == 0) base else "$base$SEPARADOR$generacion"

        private fun generacionDe(base: String, alias: String): Int =
            if (alias == base) 0 else alias.removePrefix("$base$SEPARADOR").toIntOrNull() ?: -1

        private fun leerGeneraciones(): Properties = Properties().apply {
            ficheroDeGeneraciones?.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use(::load) } }
        }

        private fun generacionVigente(base: String): Int = leerGeneraciones().getProperty(base)?.toIntOrNull() ?: 0

        @Synchronized
        private fun fijarGeneracion(base: String, generacion: Int) {
            val fichero = ficheroDeGeneraciones ?: error("ClaveEnKeystore.iniciar() no se ha llamado")
            val props = leerGeneraciones().apply { setProperty(base, generacion.toString()) }
            fichero.outputStream().use { props.store(it, null) }
        }

        /**
         * El nombre comun del certificado que AeriaOne emitio a este terminal (DEV-xxxx).
         * Null antes del alta: el Keystore guarda uno provisional de Android sin el prefijo.
         */
        fun deviceIdDelTerminal(): String? =
            runCatching { terminal.commonNameDelCertificado() }.getOrNull()?.takeIf { it.startsWith("DEV-") }
    }
}
