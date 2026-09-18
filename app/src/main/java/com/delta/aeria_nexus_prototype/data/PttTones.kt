package com.delta.aeria_nexus_prototype.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "NexusTone"

/**
 * Senal sonora del PTT, como la de un walkie de verdad.
 *
 * Gemelo de `PttTones` de BodyCamServer, y a proposito: el agente que lleva la
 * bodycam en el pecho y el telefono en la mano tiene que oir exactamente los
 * mismos tres tonos en las dos puntas, o dejan de significar nada. **Si se
 * cambia una frecuencia o una duracion aqui, hay que cambiarla igual alli** —
 * mismo criterio que EvidenceCrypto/EvidenceKeys, que tambien viven duplicados.
 *
 *   • [abrir]     subida  (880 → 1320 Hz)  "canal abierto, habla"
 *   • [cerrar]    bajada  (1320 → 880 Hz)  "canal cerrado, has soltado"
 *   • [denegado]  zumbido grave doble      "NO se abrio — no te estan oyendo"
 *   • [entra]     pitido agudo suelto      "otro ha abierto el canal"
 *   • [sale]      pitido medio suelto      "el otro ha soltado, canal libre"
 *   • [pisando]   tres pitidos agudos      "hay dos microfonos abiertos a la vez"
 *
 * La regla que separa unos de otros sin pensar: **dos notas son tuyas, una nota
 * es de otro, tres notas sois los dos.** [entra] y [sale] los dispara
 * AgoraRepository al abrirse o cerrarse el PTT de un companero, venga de su
 * telefono (data stream) o de una bodycam (su audio en el canal). [pisando]
 * sustituye a [entra] cuando el propio PTT ya esta abierto.
 *
 * Aqui el boton es de mantener-para-hablar, asi que el tono confirma algo que en
 * la bodycam no hace falta confirmar: que el dedo llego a agarrar el boton y que
 * al soltarlo el microfono se ha cerrado de verdad.
 *
 * Se sintetizan en PCM en vez de tirar de ToneGenerator o de un .ogg: los tonos
 * de ToneGenerator son de telefonia (DTMF y supervision) y no permiten la subida
 * y la bajada que hacen reconocible el par abrir/cerrar.
 */
object PttTones {

    private const val SAMPLE_RATE = 44_100
    private const val AMPLITUD    = 0.35   // el volumen se fija en la propia onda
    private const val AMPLITUD_RX = 0.22   // lo que llega de fuera suena mas bajo: el
                                           // pitido no debe tapar la primera palabra

    /**
     * Los tonos suenan fuera del hilo que los pide y de uno en uno. Que no
     * bloqueen importa mas que en la bodycam: aqui quien los dispara es el gesto
     * de Compose, en el hilo principal, y 200 ms ahi serian un tiron visible.
     */
    private val altavoz = Executors.newSingleThreadExecutor()

    /** Microfono abierto: ya se puede hablar. */
    fun abrir() = reproducir(listOf(880 to 70, 1320 to 110))

    /** Microfono cerrado: el canal se ha soltado. */
    fun cerrar() = reproducir(listOf(1320 to 70, 880 to 110))

    /** El PTT no se abrio. Nadie esta oyendo al agente. */
    fun denegado() = reproducir(listOf(300 to 160, 0 to 70, 300 to 220))

    /**
     * Otro ha abierto el canal: su voz empieza a sonar. Una sola nota, no dos:
     * es lo que distingue de un plumazo la radio de los demas de la propia, y no
     * hay que aprenderselo.
     */
    fun entra() = reproducir(listOf(1568 to 90), AMPLITUD_RX)

    /** El que hablaba ha soltado: el canal queda libre. */
    fun sale() = reproducir(listOf(1046 to 90), AMPLITUD_RX)

    /**
     * Dos microfonos abiertos a la vez: el agente esta hablando y otro ha abierto
     * el canal encima, o al reves. Va a volumen pleno y no al de recepcion porque
     * tiene que oirse por encima de la propia voz; con [entra] a ese volumen el
     * agente que habla no se enteraba de que le estaban pisando.
     */
    fun pisando() = reproducir(listOf(1760 to 60, 0 to 40, 1760 to 60, 0 to 40, 1760 to 60))

    /** tramos = pares (frecuencia en Hz, duracion en ms). Frecuencia 0 = silencio. */
    private fun reproducir(tramos: List<Pair<Int, Int>>, amplitud: Double = AMPLITUD) {
        altavoz.execute {
            val pcm = sintetizar(tramos, amplitud)
            var track: AudioTrack? = null
            try {
                track = construirTrack(pcm.size * 2)
                track.play()
                track.write(pcm, 0, pcm.size)
                // write() vuelve al copiar, no al sonar: sin esta espera el
                // release() de abajo cortaria el pitido por la mitad.
                Thread.sleep(tramos.sumOf { it.second }.toLong() + 80)
                track.stop()
            } catch (e: Exception) {
                // Un altavoz que falla no puede tumbar el PTT: se pierde el aviso
                // sonoro, no la transmision.
                Log.w(TAG, "No se pudo emitir el tono del PTT: ${e.message}")
            } finally {
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * USAGE_VOICE_COMMUNICATION_SIGNALLING: el pitido pertenece a la misma
     * conversacion que la voz, asi que sale por donde sale la voz y al volumen
     * que el agente ya tiene puesto para la radio. (La bodycam usa USAGE_ALARM,
     * pero alli es por otra razon: sus volumenes vienen a cero de fabrica.)
     */
    private fun construirTrack(bytes: Int): AudioTrack {
        val minimo = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(bytes, minimo))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun sintetizar(tramos: List<Pair<Int, Int>>, amplitud: Double): ShortArray {
        val muestras = tramos.map { (hz, ms) -> hz to ms * SAMPLE_RATE / 1000 }
        val pcm = ShortArray(muestras.sumOf { it.second })
        var i = 0
        for ((hz, n) in muestras) {
            // Rampa de 5 ms a la entrada y a la salida de cada tramo: cortar una
            // senoidal en seco suena a chasquido, no a walkie.
            val rampa = (SAMPLE_RATE * 5 / 1000).coerceAtMost(n / 2).coerceAtLeast(1)
            for (j in 0 until n) {
                if (hz > 0) {
                    val ganancia = when {
                        j < rampa      -> j.toDouble() / rampa
                        j >= n - rampa -> (n - j).toDouble() / rampa
                        else           -> 1.0
                    }
                    val v = sin(2 * PI * hz * j / SAMPLE_RATE) * amplitud * ganancia
                    pcm[i] = (v * Short.MAX_VALUE).toInt().toShort()
                }
                i++
            }
        }
        return pcm
    }
}
