package com.delta.aeria_nexus_prototype.data.transcript

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Saca la pista de audio de un MP4 o M4A y la deja como Whisper la pide:
 * 16 kHz, mono, en un fichero PCM de 16 bits little-endian.
 *
 * Va a disco y no a memoria porque una hora de audio son 230 MB en floats; asi
 * el motor la lee a trozos (ver MotorWhisper).
 */
object DecodificadorDeAudio {

    const val FRECUENCIA = 16_000
    private const val ESPERA_US = 10_000L

    sealed interface Resultado {
        data class Correcto(val muestras: Long) : Resultado
        data object SinAudio : Resultado
        data class Fallo(val motivo: String) : Resultado
    }

    fun decodificar(origen: File, destino: File): Resultado {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(origen.path)
            val pista = (0 until extractor.trackCount).firstOrNull { indice ->
                extractor.getTrackFormat(indice).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Resultado.SinAudio
            extractor.selectTrack(pista)
            val formato = extractor.getTrackFormat(pista)
            val decodificador = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec = decodificador
            decodificador.configure(formato, null, null, 0)
            decodificador.start()
            BufferedOutputStream(destino.outputStream(), 1 shl 16).use { salida ->
                val remuestreo = Remuestreo(salida)
                bucle(extractor, decodificador, formato, remuestreo)
                Resultado.Correcto(remuestreo.escritas)
            }
        } catch (e: Exception) {
            destino.delete()
            Resultado.Fallo("audio decode failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun bucle(extractor: MediaExtractor, codec: MediaCodec, formatoInicial: MediaFormat, remuestreo: Remuestreo) {
        val info = MediaCodec.BufferInfo()
        var entradaTerminada = false
        var formato = formatoInicial
        while (true) {
            if (!entradaTerminada) entradaTerminada = alimentar(extractor, codec)
            val indice = codec.dequeueOutputBuffer(info, ESPERA_US)
            when {
                indice == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> formato = codec.outputFormat
                indice >= 0 -> {
                    val buffer = codec.getOutputBuffer(indice)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        remuestreo.anadir(buffer.slice().order(ByteOrder.nativeOrder()), formato)
                    }
                    codec.releaseOutputBuffer(indice, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Pasa un bloque comprimido al decodificador. Devuelve true al acabarse la pista. */
    private fun alimentar(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val indice = codec.dequeueInputBuffer(ESPERA_US)
        if (indice < 0) return false
        val buffer = codec.getInputBuffer(indice) ?: return false
        val leidos = extractor.readSampleData(buffer, 0)
        if (leidos < 0) {
            codec.queueInputBuffer(indice, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(indice, 0, leidos, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /**
     * Mezcla a mono y baja a 16 kHz promediando cada ventana de entrada que cae
     * en una muestra de salida. Ese promedio hace de filtro paso bajo: sin el, al
     * bajar de 48 kHz el ruido agudo se doblaria sobre la voz.
     */
    private class Remuestreo(private val salida: OutputStream) {
        var escritas = 0L
            private set
        private var leidas = 0L
        private var suma = 0.0
        private var enVentana = 0
        private var ultima = 0.0
        private var frontera = 0.0

        fun anadir(datos: ByteBuffer, formato: MediaFormat) {
            val canales = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val paso = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble() / FRECUENCIA
            if (frontera == 0.0) frontera = paso
            val enFloat = formato.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                formato.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            val bytesPorMuestra = if (enFloat) 4 else 2
            val tramas = datos.remaining() / (bytesPorMuestra * canales)
            repeat(tramas) {
                var mono = 0.0
                repeat(canales) {
                    mono += if (enFloat) datos.float.toDouble() else datos.short / 32768.0
                }
                acumular(mono / canales, paso)
            }
        }

        private fun acumular(muestra: Double, paso: Double) {
            suma += muestra
            enVentana++
            leidas++
            // Al subir de frecuencia (paso < 1) una muestra de entrada da varias de salida.
            while (leidas >= frontera) {
                val valor = if (enVentana > 0) suma / enVentana else ultima
                escribir(valor)
                ultima = valor
                suma = 0.0
                enVentana = 0
                frontera += paso
            }
        }

        private fun escribir(valor: Double) {
            val entero = (valor * 32767).toInt().coerceIn(-32768, 32767)
            salida.write(entero and 0xFF)
            salida.write((entero shr 8) and 0xFF)
            escritas++
        }
    }
}
