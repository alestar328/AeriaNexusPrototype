package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.DeviceKeyWrapper
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

private const val TAG = "AeriaPin"

/** Lo que hay que saber para decidir si el teclado admite otro intento. */
data class EstadoIntentos(
    val intentosRestantes: Int,
    /** Epoch millis hasta el que el teclado esta bloqueado; 0 si no lo esta. */
    val bloqueadoHasta: Long,
)

/**
 * PIN local del agente (workflow 4).
 *
 * **El PIN no se guarda en ninguna parte, ni en claro ni en resumen.** Lo que se
 * guarda es un verificador: un testigo conocido cifrado con AES-GCM bajo una clave
 * derivada del PIN con PBKDF2-HMAC-SHA256. Si el PIN es el correcto, el testigo
 * sale entero; si no, falla el tag de GCM y no hay nada que comparar. Es el mismo
 * mecanismo que ya usa la boveda de evidencia, que lleva funcionando desde agosto.
 *
 * Y el fichero entero va ademas envuelto por una clave de AndroidKeyStore
 * ([DeviceKeyWrapper.identidad], distinta de la de la evidencia). Esa segunda capa
 * es la que importa de verdad con un secreto de seis digitos: sin ella, sacar el
 * fichero con `adb` y probar el millon de combinaciones en un PC seria cuestion de
 * minutos. Con ella, adivinarlo obliga a hacerlo en este telefono y de uno en uno,
 * que es justo donde muerde el limite de intentos.
 *
 * **El contador de intentos vive aqui dentro**, no en SharedPreferences en claro
 * como hasta ahora: si se pudiera poner a cero editando un XML, el limite de
 * intentos no limitaria nada. No hace falta el PIN para leerlo ni para escribirlo,
 * solo la clave de Keystore, porque hay que contar los fallos precisamente cuando
 * el PIN no se sabe.
 *
 * **Hasta donde llega esto, dicho claro.** El PIN autoriza el uso de la credencial
 * *dentro de la aplicacion*: es la app la que se niega a seguir, no el Keystore.
 * Atar la clave del agente a una autenticacion del sistema es la decision D2, que
 * sigue abierta y esta marcada en [ClaveEnKeystore]. Lo que si es cierto hoy es que
 * la clave privada no sale del Keystore pase lo que pase, y que borrar los datos de
 * la app para reiniciar el contador destruye tambien esa clave y deja el terminal
 * sin identidad: el atajo existe, pero cuesta la credencial.
 */
class PinLocal(private val context: Context) {

    private val fichero: File get() = File(context.filesDir, "credential").apply { mkdirs() }
        .resolve(NOMBRE_FICHERO)

    fun existe(): Boolean = fichero.exists() && leer() != null

    /**
     * Da de alta el PIN o lo sustituye (workflow 4 paso 13, y workflow 5).
     *
     * Cambiar el PIN reinicia el contador de intentos: quien acaba de demostrar que
     * sabe el anterior no arrastra los fallos de otro.
     */
    fun establecer(pin: String): Boolean {
        require(pin.length == IdentityRepository.PIN_LENGTH) { "El PIN no tiene la longitud fijada" }
        return try {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val iteraciones = iteracionesParaEsteTerminal(salt)
            val contenido = JSONObject().apply {
                put("version", VERSION)
                put("salt", codificar(salt))
                put("iterations", iteraciones)
                put("verificador", codificar(cifrarTestigo(pin, salt, iteraciones)))
                put("fallos", 0)
                put("bloqueos", 0)
                put("fin_bloqueo", 0L)
            }
            escribir(contenido)
            true
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo establecer el PIN", e)
            false
        }
    }

    /**
     * true solo si el PIN abre el verificador. No compara nada: o el testigo sale
     * intacto o el tag de AES-GCM falla.
     */
    fun verificar(pin: String): Boolean {
        val contenido = leer() ?: return false
        return try {
            val salt = decodificar(contenido.getString("salt"))
            val clave = derivar(pin, salt, contenido.getInt("iterations"))
            val blob = decodificar(contenido.getString("verificador"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val testigo = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            testigo.contentEquals(TESTIGO)
        } catch (e: Exception) {
            // Lo normal cuando el PIN es incorrecto: falla la autenticacion del tag.
            false
        }
    }

    fun estadoDeIntentos(): EstadoIntentos {
        val contenido = leer() ?: return EstadoIntentos(IdentityRepository.MAX_ATTEMPTS, 0L)
        val finBloqueo = contenido.optLong("fin_bloqueo", 0L)
        return EstadoIntentos(
            intentosRestantes = IdentityRepository.MAX_ATTEMPTS - contenido.optInt("fallos", 0),
            bloqueadoHasta = if (finBloqueo > System.currentTimeMillis()) finBloqueo else 0L,
        )
    }

    /**
     * Suma un fallo y aplica el bloqueo temporal si se agoto la tanda (workflow 27,
     * paso 7).
     *
     * El bloqueo crece con cada tanda para que probar el PIN a ciegas deje de ser
     * viable, pero sin llegar nunca a inutilizar el terminal: un agente que se
     * equivoca al empezar el turno no puede quedarse sin app durante el turno entero.
     */
    fun registrarFallo(): EstadoIntentos {
        val contenido = leer() ?: return EstadoIntentos(IdentityRepository.MAX_ATTEMPTS, 0L)
        val fallos = contenido.optInt("fallos", 0) + 1

        if (fallos < IdentityRepository.MAX_ATTEMPTS) {
            contenido.put("fallos", fallos)
            escribir(contenido)
            return EstadoIntentos(IdentityRepository.MAX_ATTEMPTS - fallos, 0L)
        }

        val bloqueos = contenido.optInt("bloqueos", 0) + 1
        val finBloqueo = System.currentTimeMillis() + duracionBloqueoMillis(bloqueos)
        contenido.put("fallos", 0)
        contenido.put("bloqueos", bloqueos)
        contenido.put("fin_bloqueo", finBloqueo)
        escribir(contenido)
        return EstadoIntentos(IdentityRepository.MAX_ATTEMPTS, finBloqueo)
    }

    /** Tanda superada: se olvidan los fallos y las tandas anteriores. */
    fun reiniciarIntentos() {
        val contenido = leer() ?: return
        contenido.put("fallos", 0)
        contenido.put("bloqueos", 0)
        contenido.put("fin_bloqueo", 0L)
        escribir(contenido)
    }

    /** §13: el PIN se va con la identidad que protegia. */
    fun borrar() {
        runCatching { fichero.delete() }
    }

    private fun cifrarTestigo(pin: String, salt: ByteArray, iteraciones: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derivar(pin, salt, iteraciones))
        return cipher.iv + cipher.doFinal(TESTIGO)
    }

    /**
     * Cuantas iteraciones aguanta ESTE terminal dentro del presupuesto de tiempo.
     *
     * No se copia una cifra de una guia: se mide. Android resuelve
     * PBKDF2withHmacSHA256 con una implementacion en Java que en un terminal de
     * gama media puede tardar segundos en las 210.000 iteraciones que recomienda
     * OWASP para contrasenas; medido en el Redmi Note 8 Pro, 2.703 ms. Cuatro
     * segundos al empezar cada turno, con guantes y de noche, no es una medida de
     * seguridad: es una razon para que el agente odie la aplicacion.
     *
     * Por que se puede bajar aqui y no en la boveda: este fichero va ademas
     * envuelto por una clave de Keystore no exportable. Copiarlo a un PC no sirve
     * de nada, asi que el ataque realista es ejecutar codigo como la app en este
     * telefono, y ahi el coste por intento sigue siendo la unica barrera pero
     * multiplicado por el millon de PIN posibles: con el suelo de abajo son mas de
     * una semana de computo continuo. El numero exacto lo tiene que ratificar
     * ciberseguridad junto con el resto de la politica de credenciales, que es uno
     * de los artefactos que reconoce pendientes.
     *
     * El resultado se guarda en el fichero, asi que un terminal rapido usa mas
     * iteraciones y uno lento sigue siendo usable, sin que ninguno tenga que
     * recalibrar al desbloquear.
     */
    private fun iteracionesParaEsteTerminal(salt: ByteArray): Int {
        val inicio = System.currentTimeMillis()
        derivar("000000", salt, ITERACIONES_DE_MUESTRA)
        val coste = (System.currentTimeMillis() - inicio).coerceAtLeast(1)

        val estimadas = (ITERACIONES_DE_MUESTRA.toLong() * PRESUPUESTO_MILLIS / coste).toInt()
        val elegidas = estimadas.coerceIn(ITERACIONES_MINIMAS, ITERACIONES_MAXIMAS)
        Log.i(TAG, "calibracion: $ITERACIONES_DE_MUESTRA iteraciones en $coste ms -> se fijan $elegidas")
        return elegidas
    }

    /**
     * Derivacion deliberadamente cara: es lo unico que encarece probar el millon de
     * combinaciones de un PIN de seis digitos. Se registra el coste real porque es
     * un numero que hay que vigilar en los terminales lentos, no un detalle: si
     * sube demasiado, el agente lo nota en cada arranque de turno.
     */
    private fun derivar(pin: String, salt: ByteArray, iteraciones: Int): SecretKeySpec {
        val inicio = System.currentTimeMillis()
        val spec = PBEKeySpec(pin.toCharArray(), salt, iteraciones, CLAVE_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256").generateSecret(spec).encoded
        Log.d(TAG, "derivacion de $iteraciones iteraciones en ${System.currentTimeMillis() - inicio} ms")
        return SecretKeySpec(bytes, "AES")
    }

    private fun leer(): JSONObject? = try {
        val envuelto = fichero.takeIf { it.isFile }?.readBytes()
        val claro = envuelto?.let { DeviceKeyWrapper.identidad.unwrap(it) }
        claro?.let { JSONObject(String(it, Charsets.UTF_8)) }
    } catch (e: Exception) {
        Log.e(TAG, "fichero de PIN ilegible", e)
        null
    }

    private fun escribir(contenido: JSONObject) {
        val claro = contenido.toString().toByteArray(Charsets.UTF_8)
        fichero.writeBytes(DeviceKeyWrapper.identidad.wrap(claro))
    }

    private fun codificar(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodificar(texto: String): ByteArray = Base64.getDecoder().decode(texto)

    private companion object {
        const val NOMBRE_FICHERO = "pin.bin"
        const val VERSION = 1

        /** Muestra corta para calibrar sin que el alta del PIN se note. */
        const val ITERACIONES_DE_MUESTRA = 20_000

        /** Lo que se acepta que tarde el agente en cada arranque de turno. */
        const val PRESUPUESTO_MILLIS = 400L

        /**
         * Suelo y techo de la calibracion. El techo es la recomendacion actual de
         * OWASP para PBKDF2-HMAC-SHA256, que solo alcanzaran terminales rapidos; el
         * suelo es lo que mantiene usable un terminal de campo lento, y esta
         * razonado en [iteracionesParaEsteTerminal].
         */
        const val ITERACIONES_MINIMAS = 50_000
        const val ITERACIONES_MAXIMAS = 600_000

        const val SALT_BYTES = 16
        const val CLAVE_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** Testigo conocido. No es secreto: lo secreto es poder descifrarlo. */
        val TESTIGO = "aeria.pin.v1".toByteArray(Charsets.UTF_8)

        /** 1 min, 5 min y 30 min a partir de la tercera tanda. */
        fun duracionBloqueoMillis(bloqueos: Int): Long = when (bloqueos) {
            1 -> 60_000L
            2 -> 300_000L
            else -> 1_800_000L
        }
    }
}
