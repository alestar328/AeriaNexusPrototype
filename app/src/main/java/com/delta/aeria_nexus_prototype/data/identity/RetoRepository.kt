package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Base64
import org.json.JSONObject

private const val TAG = "AeriaReto"

/**
 * Para que sirve un reto concreto.
 *
 * Un reto vale para una cosa y solo una. Si el mismo nonce sirviera para el alta y
 * para el desbloqueo, quien capturase una respuesta de alta podria presentarla como
 * un inicio de sesion: eso es exactamente lo que el modelo llama replay.
 */
enum class PropositoDelReto {
    /** Va dentro de la cadena de atestacion al crear la clave (workflow 12, paso 10). */
    ATESTACION_TERMINAL,

    /** Prueba de posesion de la clave del TERMINAL (workflow 12, paso 16). */
    POSESION_TERMINAL,

    /** Prueba de posesion de la clave del AGENTE (workflow 3, paso 14). */
    POSESION_AGENTE,

    /** Reto de autenticacion al desbloquear (workflow 27, pasos 11 y 12). */
    LOGIN,
}

/** Un reto emitido por el backend, con su caducidad. */
data class Reto(
    val proposito: PropositoDelReto,
    val bytes: ByteArray,
    val emisor: String,
    val caducaEn: Long,
) {
    val caducado: Boolean get() = System.currentTimeMillis() > caducaEn

    // data class con ByteArray: equals/hashCode van por referencia si no se
    // sobreescriben, y dos retos iguales pareceria distintos.
    override fun equals(other: Any?): Boolean =
        other is Reto && proposito == other.proposito && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * proposito.hashCode() + bytes.contentHashCode()
}

/**
 * Retos de autenticacion (workflow 27 pasos 11 y 12; workflow 12 paso 16;
 * workflow 3 paso 14).
 *
 * POR QUE EXISTE ESTO. Hasta ahora las pruebas de posesion se firmaban sobre un
 * valor que se inventaba el propio telefono: `SecureRandom` para la atestacion y la
 * cadena literal "prueba-de-posesion" para las dos firmas. Eso acredita que las dos
 * mitades del par se corresponden, pero **no acredita frescura**: una respuesta
 * capturada vale para siempre y se puede reutilizar. El documento lo pide explicito
 * en IAM-04, "fresh challenge-response", y era la costura mas visible de que faltaba
 * la otra mitad.
 *
 * Un reto que llega de fuera lo arregla porque el telefono no puede predecirlo: la
 * firma prueba que quien responde tiene la clave AHORA, no que la tuvo alguna vez.
 *
 * QUE ES HOY EL "BACKEND". Sin canal, los retos los entrega `tools/reto.sh` por
 * intent y las respuestas se recogen del disco con `run-as`. El nonce lo genera el
 * script, que es lo unico que importa para la frescura: el telefono no lo elige.
 * El transporte es de mentira; la propiedad criptografica es real.
 *
 * Un reto es **de un solo uso** y **caduca**. Las dos cosas se comprueban aqui, y no
 * porque este lado sea el que manda —el que decide es el backend— sino para que la
 * app no llegue a firmar algo que va a ser rechazado y el fallo se vea donde ocurre.
 */
class RetoRepository(private val context: Context) {

    private val carpeta: File get() = File(context.filesDir, "challenges").apply { mkdirs() }

    /** Guarda un reto recien emitido. Lo llama el puente de depuracion. */
    fun recibir(proposito: PropositoDelReto, bytes: ByteArray, emisor: String, validoSegundos: Long) {
        val contenido = JSONObject().apply {
            put("purpose", proposito.name)
            put("nonce", Base64.getEncoder().encodeToString(bytes))
            put("issuer", emisor)
            put("expires_at", System.currentTimeMillis() + validoSegundos * 1_000)
        }
        fichero(proposito).writeText(contenido.toString(2))
        Log.i(TAG, "reto de ${proposito.name} recibido de $emisor, valido $validoSegundos s")
    }

    /** El reto pendiente si lo hay y no ha caducado. No lo consume. */
    fun pendiente(proposito: PropositoDelReto): Reto? {
        val fichero = fichero(proposito).takeIf { it.isFile } ?: return null
        val reto = try {
            val json = JSONObject(fichero.readText())
            Reto(
                proposito = proposito,
                bytes = Base64.getDecoder().decode(json.getString("nonce")),
                emisor = json.getString("issuer"),
                caducaEn = json.getLong("expires_at"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "reto ilegible, se descarta", e)
            fichero.delete()
            return null
        }

        if (reto.caducado) {
            Log.w(TAG, "el reto de ${proposito.name} ha caducado; se descarta")
            fichero.delete()
            return null
        }
        return reto
    }

    /**
     * Devuelve el reto y lo retira: de un solo uso.
     *
     * Se retira ANTES de firmar, no despues. Si se retirase despues, un fallo a
     * mitad dejaria el reto disponible para un segundo intento, que es justo lo que
     * un nonce de un solo uso tiene que impedir.
     */
    fun consumir(proposito: PropositoDelReto): Reto? {
        val reto = pendiente(proposito) ?: return null
        fichero(proposito).delete()
        return reto
    }

    /**
     * Deja la respuesta firmada donde el backend simulado pueda recogerla.
     *
     * Ademas del nonce y la firma se guarda el contexto que el backend necesita para
     * elegir con que certificado verificar. En el modelo real esto viaja por un canal
     * autenticado junto a la referencia del enrolamiento.
     */
    fun responder(reto: Reto, firma: ByteArray, sujeto: String): File {
        val codificador = Base64.getEncoder()
        val contenido = JSONObject().apply {
            put("purpose", reto.proposito.name)
            put("subject", sujeto)
            put("nonce", codificador.encodeToString(reto.bytes))
            put("signature", codificador.encodeToString(firma))
            put("signature_algorithm", Pkcs10.ALGORITMO_FIRMA)
            put("issuer", reto.emisor)
        }
        return File(carpeta, "${reto.proposito.name.lowercase()}.response.json")
            .apply { writeText(contenido.toString(2)) }
    }

    /** §13: los retos y sus respuestas se van con la identidad. */
    fun borrar() {
        carpeta.deleteRecursively()
    }

    private fun fichero(proposito: PropositoDelReto) =
        File(carpeta, "${proposito.name.lowercase()}.challenge.json")
}
