package com.delta.aeria_nexus_prototype.data.transcript

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * Transcribe el PCM que deja [DecodificadorDeAudio] con el modelo que va en el APK.
 *
 * El audio se pasa a Whisper en trozos de [MINUTOS_POR_TROZO] minutos: entero no
 * cabe en memoria si la grabacion es larga, y Whisper trabaja en ventanas de 30 s
 * de todos modos. El idioma se detecta por trozo y gana el que mas texto aporta.
 */
class MotorWhisper(private val context: Context) {

    data class Segmento(val inicio: Double, val fin: Double, val texto: String)

    data class Resultado(
        val idioma: String?,
        val probabilidadIdioma: Double?,
        val segmentos: List<Segmento>,
    )

    /** Copia el modelo del APK a disco si no esta ya: whisper.cpp abre por ruta. */
    fun prepararModelo(): File {
        val destino = File(context.noBackupFilesDir, "whisper/${BuildConfig.WHISPER_MODELO}.bin")
        val enApk = "whisper/${BuildConfig.WHISPER_MODELO}.bin"
        val tamano = context.assets.openFd(enApk).use { it.length }
        if (destino.isFile && destino.length() == tamano) return destino
        destino.parentFile?.mkdirs()
        val temporal = File(destino.parentFile, destino.name + ".part")
        context.assets.open(enApk).use { entrada -> temporal.outputStream().use { entrada.copyTo(it) } }
        destino.delete()
        temporal.renameTo(destino)
        Log.d(TAG, "modelo ${BuildConfig.WHISPER_MODELO} copiado a disco")
        return destino
    }

    /** Lanza excepcion si whisper falla: el que llama lo convierte en `status=failed`. */
    fun transcribir(pcm: File): Resultado {
        val contexto = WhisperNativo.abrir(prepararModelo().path)
        check(contexto != 0L) { "model load failed" }
        try {
            val idiomas = mutableMapOf<String, Pair<Int, Double>>()
            val segmentos = mutableListOf<Segmento>()
            RandomAccessFile(pcm, "r").use { fichero ->
                val total = fichero.length() / 2
                var desde = 0L
                while (desde < total) {
                    val muestras = minOf(MUESTRAS_POR_TROZO, total - desde).toInt()
                    val trozo = leerTrozo(fichero, desde, muestras)
                    val bytes = WhisperNativo.transcribir(contexto, trozo, hilos())
                        ?: error("whisper_full failed")
                    anadirTrozo(JSONObject(String(bytes, Charsets.UTF_8)), desde, segmentos, idiomas)
                    desde += muestras
                }
            }
            if (segmentos.isEmpty()) return Resultado(null, null, emptyList())
            val ganador = idiomas.maxByOrNull { it.value.first }
            return Resultado(ganador?.key, ganador?.value?.second, segmentos)
        } finally {
            WhisperNativo.cerrar(contexto)
        }
    }

    /** Lee PCM de 16 bits y lo pasa a float. Rellena con silencio hasta el minimo de Whisper. */
    private fun leerTrozo(fichero: RandomAccessFile, desde: Long, muestras: Int): FloatArray {
        val bytes = ByteArray(muestras * 2)
        fichero.seek(desde * 2)
        fichero.readFully(bytes)
        val enteros = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val trozo = FloatArray(max(muestras, MUESTRAS_MINIMAS))
        for (i in 0 until muestras) trozo[i] = enteros.get(i) / 32768f
        return trozo
    }

    /**
     * Pasa los segmentos de un trozo a segundos desde el inicio del fichero. El
     * contrato los quiere ordenados, sin solaparse y nunca vacios.
     */
    private fun anadirTrozo(
        json: JSONObject,
        desdeMuestra: Long,
        segmentos: MutableList<Segmento>,
        idiomas: MutableMap<String, Pair<Int, Double>>,
    ) {
        val desfase = desdeMuestra.toDouble() / DecodificadorDeAudio.FRECUENCIA
        val lista = json.getJSONArray("segments")
        var caracteres = 0
        for (i in 0 until lista.length()) {
            val crudo = lista.getJSONArray(i)
            val texto = crudo.getString(2).trim()
            if (texto.isEmpty()) continue
            val anterior = segmentos.lastOrNull()?.fin ?: 0.0
            val inicio = max(redondear(desfase + crudo.getLong(0) / 100.0), anterior)
            val fin = max(redondear(desfase + crudo.getLong(1) / 100.0), inicio)
            segmentos += Segmento(inicio, fin, texto)
            caracteres += texto.length
        }
        if (caracteres == 0 || json.isNull("language")) return
        val idioma = json.getString("language")
        val probabilidad = json.getDouble("probability")
        val previo = idiomas[idioma]
        idiomas[idioma] = (previo?.first ?: 0) + caracteres to max(previo?.second ?: 0.0, probabilidad)
    }

    private fun redondear(segundos: Double): Double = (segundos * 100).roundToLong() / 100.0

    // Mas de 4 hilos no acelera: los nucleos que sobran son los pequenos y frenan al resto.
    private fun hilos(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private companion object {
        const val TAG = "MotorWhisper"
        const val MINUTOS_POR_TROZO = 5
        const val MUESTRAS_POR_TROZO = MINUTOS_POR_TROZO * 60L * DecodificadorDeAudio.FRECUENCIA

        // Por debajo de 1 s whisper.cpp no transcribe nada; una nota de "si" dura menos.
        const val MUESTRAS_MINIMAS = DecodificadorDeAudio.FRECUENCIA * 3 / 2
    }
}
