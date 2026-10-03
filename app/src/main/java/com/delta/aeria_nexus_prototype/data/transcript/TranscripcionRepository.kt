package com.delta.aeria_nexus_prototype.data.transcript

import android.media.MediaMetadataRetriever
import android.util.Log
import com.delta.aeria_nexus_prototype.data.LocalEvidenceRepository
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.upload.EvidenceUploader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Transcribe cada grabacion del telefono al cerrarla y la entrega como cuarta
 * pieza (kind=transcript). Contrato: docs/TRANSCRIPT-FORMAT.md de BodyCamServer.
 *
 * Va en dos pasos porque el claro no puede esperar a Whisper:
 *   1. [preparar] saca el audio a un PCM en segundos, mientras el claro sigue
 *      vivo. A partir de ahi quien lo tenia ya lo puede borrar.
 *   2. Una cola de uno en uno transcribe ese PCM, que puede llevar minutos.
 *
 * El trabajo pendiente vive en disco (PCM + datos), asi que si la app muere a
 * mitad se repite entero al arrancar: el contrato no admite transcripciones a medias.
 */
class TranscripcionRepository(
    private val motor: MotorWhisper,
    private val evidencia: LocalEvidenceRepository,
    private val uploader: EvidenceUploader,
) {

    /** Lo que identifica a la grabacion fuera de la transcripcion: para subirla. */
    data class Evidencia(
        val sha256Plain: String,
        val evidenceId: String?,
        val incidentId: String?,
        val grabadaPor: String,
        /** Null si no se sabe la hora de inicio: se saca del propio fichero. */
        val grabadaEnMillis: Long?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // De una en una: dos a la vez se reparten los mismos nucleos y acaban las dos tarde.
    private val turno = Mutex()

    // Nombres base de las transcripciones ya cerradas, para que la pantalla sepa cuando dejar de esperar.
    private val terminadas = MutableStateFlow(emptySet<String>())

    /**
     * Decodifica el audio de [claro] y encola su transcripcion. Devuelve el nombre
     * base con el que esperarla, o null si no se pudo ni empezar. [claro] se puede
     * borrar en cuanto vuelve.
     */
    suspend fun preparar(claro: File, origen: Evidencia): String? = withContext(Dispatchers.IO) {
        val carpeta = evidencia.transcriptsPendingDir() ?: return@withContext null
        val base = claro.nameWithoutExtension
        val pcm = File(carpeta, "$base.pcm")
        val empezado = System.currentTimeMillis()
        val duracion = duracionEnSegundos(claro)
        val trabajo = JSONObject()
            .put("sha256_plain", origen.sha256Plain)
            .put("recorded_by", origen.grabadaPor)
            .put("recorded_at_ms", origen.grabadaEnMillis ?: horaDeInicio(claro, duracion))
            .put("media_type", if (claro.extension.equals("m4a", ignoreCase = true)) "audio" else "video")
        origen.evidenceId?.let { trabajo.put("evidence_id", it) }
        origen.incidentId?.let { trabajo.put("incident_id", it) }
        duracion?.let { trabajo.put("duration", it) }

        when (val decodificado = DecodificadorDeAudio.decodificar(claro, pcm)) {
            is DecodificadorDeAudio.Resultado.Correcto -> if (duracion == null) {
                trabajo.put("duration", decodificado.muestras.toDouble() / DecodificadorDeAudio.FRECUENCIA)
            }
            DecodificadorDeAudio.Resultado.SinAudio -> trabajo.put("error", "no audio track")
            is DecodificadorDeAudio.Resultado.Fallo -> trabajo.put("error", decodificado.motivo)
        }
        trabajo.put("decode_seconds", (System.currentTimeMillis() - empezado) / 1000.0)
        // Escrito al final y de golpe: su existencia dice que el PCM esta completo.
        val temporal = File(carpeta, "$base.job.tmp")
        temporal.writeText(trabajo.toString(2))
        temporal.renameTo(File(carpeta, "$base.job.json"))
        encolar(base)
        base
    }

    /** Espera a que termine la transcripcion de [base] como mucho [topeMillis]. */
    suspend fun esperar(base: String, topeMillis: Long): Boolean =
        withTimeoutOrNull(topeMillis) { terminadas.first { base in it } } != null

    /**
     * Retoma al arrancar lo que quedo a medias: los trabajos ya en disco y las notas
     * de audio y videos de las gafas que siguen en claro con su .fev hecho. Los
     * videos del telefono los trae ProxyRepository, que es quien conserva su claro.
     *
     * La limpieza va antes de devolver, y por eso esto se llama antes que
     * ProxyRepository.reanudar: si no, podria borrar el PCM que aquel esta escribiendo.
     */
    fun reanudar() {
        val carpeta = evidencia.transcriptsPendingDir() ?: return
        val trabajos = mutableListOf<String>()
        carpeta.listFiles().orEmpty().forEach { fichero ->
            val base = fichero.name.substringBefore('.')
            val tieneTrabajo = File(carpeta, "$base.job.json").isFile
            when {
                fichero.name.endsWith(".job.json") -> trabajos += base
                // PCM sin datos = la app murio decodificando. Lo demas, claros a medio cifrar.
                fichero.name.endsWith(".pcm") && tieneTrabajo -> Unit
                else -> fichero.delete()
            }
        }
        trabajos.forEach(::encolar)
        scope.launch {
            listOf("audio_" to "phone", "gafas_" to "falconone").forEach { (prefijo, grabadaPor) ->
                evidencia.cifradasEnClaro(prefijo).forEach { claro ->
                    prepararSiFalta(claro, grabadaPor)
                    if (!claro.delete()) Log.w(TAG, "no se pudo borrar el claro ya cifrado ${claro.name}")
                }
            }
        }
    }

    /**
     * Encola la transcripcion de un claro que sobrevivio a su cifrado, si nadie lo
     * ha hecho ya. No sabe a que incidente pertenece: el backend lo enlaza por hash.
     */
    suspend fun prepararSiFalta(claro: File, grabadaPor: String) {
        if (!esAudiovisual(claro)) return
        val base = claro.nameWithoutExtension
        val yaHecha = evidencia.transcriptsDir()
            ?.let { File(it, "$base.transcript.json${EvidenceCrypto.EXTENSION}").isFile } == true
        val pendiente = evidencia.transcriptsPendingDir()?.let { File(it, "$base.job.json").isFile } == true
        if (yaHecha || pendiente) return
        Log.d(TAG, "rescatando la transcripcion de ${claro.name}")
        val origen = Evidencia(
            sha256Plain = EvidenceCrypto.sha256(claro),
            evidenceId = null,
            incidentId = null,
            grabadaPor = grabadaPor,
            grabadaEnMillis = null,
        )
        preparar(claro, origen)
    }

    private fun encolar(base: String) {
        scope.launch { turno.withLock { procesar(base) } }
    }

    private suspend fun procesar(base: String) {
        val carpeta = evidencia.transcriptsPendingDir() ?: return
        val ficheroTrabajo = File(carpeta, "$base.job.json")
        val trabajo = runCatching { JSONObject(ficheroTrabajo.readText()) }.getOrNull() ?: return
        val pcm = File(carpeta, "$base.pcm")
        val empezado = System.currentTimeMillis()
        val salida = transcribir(trabajo, pcm)
        val segundos = trabajo.optDouble("decode_seconds", 0.0) + (System.currentTimeMillis() - empezado) / 1000.0
        val json = TranscripcionJson.construir(origenDe(trabajo), salida.estado, salida.error, salida.resultado, segundos)

        val claro = File(carpeta, "$base.transcript.json")
        claro.writeText(json.toString(), Charsets.UTF_8)
        val sellada = evidencia.sealTranscript(claro)
        if (sellada == null) {
            // Se deja el trabajo: al arrancar se repite entero y se vuelve a intentar cifrar.
            Log.e(TAG, "transcripcion de $base sin cifrar — se reintenta al arrancar")
            claro.delete()
        } else {
            uploader.enqueueTranscript(sellada, datosDeSubida(trabajo, salida))
            pcm.delete()
            ficheroTrabajo.delete()
            Log.d(TAG, "$base transcrito: ${salida.estado.valor} (${salida.resultado?.idioma}) en ${segundos.toInt()} s")
        }
        terminadas.update { it + base }
    }

    private class Salida(
        val estado: TranscripcionJson.Estado,
        val error: String? = null,
        val resultado: MotorWhisper.Resultado? = null,
    )

    private fun transcribir(trabajo: JSONObject, pcm: File): Salida {
        trabajo.optString("error").takeIf { it.isNotEmpty() }?.let {
            return Salida(TranscripcionJson.Estado.FAILED, it)
        }
        WhisperNativo.motivoNoDisponible?.let { return Salida(TranscripcionJson.Estado.FAILED, it) }
        return try {
            val resultado = motor.transcribir(pcm)
            val estado = if (resultado.segmentos.isEmpty()) TranscripcionJson.Estado.NO_SPEECH
                         else TranscripcionJson.Estado.COMPLETE
            Salida(estado, resultado = resultado)
        } catch (e: Exception) {
            Salida(TranscripcionJson.Estado.FAILED, e.message ?: e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            Salida(TranscripcionJson.Estado.FAILED, "out of memory")
        }
    }

    private fun origenDe(trabajo: JSONObject) = TranscripcionJson.Origen(
        sha256Plain = trabajo.getString("sha256_plain"),
        incidentId = trabajo.optString("incident_id").ifEmpty { null },
        duracion = if (trabajo.has("duration")) Math.round(trabajo.getDouble("duration") * 100) / 100.0 else null,
        grabadaEnMillis = trabajo.getLong("recorded_at_ms"),
        grabadaPor = trabajo.getString("recorded_by"),
    )

    private fun datosDeSubida(trabajo: JSONObject, salida: Salida) = EvidenceUploader.DatosDeTranscripcion(
        transcriptOf = trabajo.getString("sha256_plain"),
        idioma = salida.resultado?.idioma,
        estado = salida.estado.valor,
        evidenceId = trabajo.optString("evidence_id").ifEmpty { null },
        incidentId = trabajo.optString("incident_id").ifEmpty { null },
        mediaType = trabajo.getString("media_type"),
    )

    private fun esAudiovisual(fichero: File): Boolean =
        fichero.extension.lowercase() in setOf("mp4", "m4a", "mov", "3gp")

    private fun duracionEnSegundos(fichero: File): Double? = conMetadatos(fichero) { lector ->
        lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0)
    }

    /**
     * Hora del primer fotograma cuando quien llama no la sabe: la `creation_time`
     * del MP4 y, si no la trae, el cierre del fichero menos su duracion. En las
     * gafas es su reloj, sin sincronizar: el contrato la da por aproximada.
     */
    private fun horaDeInicio(fichero: File, duracion: Double?): Long {
        val creacion = conMetadatos(fichero) { lector ->
            lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::leerFechaMp4)
        }
        return creacion ?: (fichero.lastModified() - ((duracion ?: 0.0) * 1000).toLong())
    }

    // El MP4 la da como 20260913T101530.000Z; sin fecha escribe 1904, que se descarta.
    private fun leerFechaMp4(texto: String): Long? {
        val formato = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
        formato.timeZone = TimeZone.getTimeZone("UTC")
        val millis = runCatching { formato.parse(texto)?.time }.getOrNull() ?: return null
        return millis.takeIf { it > ANO_2000_MILLIS }
    }

    private fun <T> conMetadatos(fichero: File, leer: (MediaMetadataRetriever) -> T?): T? {
        val lector = MediaMetadataRetriever()
        return try {
            lector.setDataSource(fichero.path)
            leer(lector)
        } catch (e: Exception) {
            null
        } finally {
            lector.release()
        }
    }

    private companion object {
        const val TAG = "Transcripcion"
        const val ANO_2000_MILLIS = 946_684_800_000L
    }
}
