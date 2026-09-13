package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Almacen local de la evidencia capturada con el propio telefono cuando la
 * bodycam no esta conectada.
 *
 * Nada de esto pasa por la galeria: la captura se escribe en la carpeta privada
 * de la app, se cifra al cerrarla y el original en claro se borra. Lo unico que
 * queda en el dispositivo es el .fev, que solo se puede volver a abrir con la
 * contrasena de la boveda (ver EvidenceVault) o desde el servidor.
 *
 * Nombres descriptivos: tipo, agente y fecha; el agente fijo agent_007 se
 * reemplazara cuando exista login real.
 */
class LocalEvidenceRepository(private val context: Context) {

    /**
     * Destino de una captura: el archivo privado donde escribe la camara o el
     * grabador y el uri de FileProvider con el que se le entrega. [name] da
     * nombre tambien al .fev.
     */
    data class MediaTarget(val uri: Uri, val file: File, val name: String)

    /** Captura ya cerrada, con el resultado del cifrado (null si no se pudo cifrar). */
    data class SealedCapture(val target: MediaTarget, val sealed: EvidenceCrypto.Sealed?)

    fun createPhotoTarget(): MediaTarget? = createTarget("photo", "jpg")

    fun createVideoTarget(): MediaTarget? = createTarget("video", "mp4")

    /**
     * Destino para un fichero que llega de las gafas (ver GafasMediaRepository).
     *
     * Se separa del de la camara del telefono solo en el nombre, y a proposito: la
     * procedencia de una evidencia es parte de la evidencia, y el nombre del .fev
     * es lo unico que la lleva hasta que exista un campo propio en el manifest.
     * Todo lo demas —carpeta privada, cifrado al cerrar, borrado del claro— es
     * identico, que es justo lo que hace que traerse el video de las gafas no
     * necesite una via de custodia aparte.
     */
    fun createGafasVideoTarget(extension: String = "mp4"): MediaTarget? =
        createTarget("gafas", extension)

    private fun createAudioTarget(): MediaTarget? = createTarget("audio", "m4a")

    /**
     * Nombre unico para una captura nueva.
     *
     * Lleva milisegundos y, aun asi, comprueba que no exista: la descarga de las
     * gafas baja varios ficheros seguidos, y con resolucion de segundos dos videos
     * pequenos salian con el MISMO nombre. El segundo pisaba al primero y la
     * boveda acababa con dos filas apuntando al mismo fichero, lo que reventaba la
     * pantalla al pintar dos elementos con la misma clave. Hasta que hubo descargas
     * en lote esto no podia pasar: las capturas del telefono las hace una persona,
     * de una en una.
     */
    private fun createTarget(type: String, extension: String): MediaTarget? {
        val carpeta = capturesDir() ?: return null
        val fecha = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        var nombre = "${type}_${AGENT_ID}_$fecha.$extension"
        var archivo = File(carpeta, nombre)
        var repeticion = 1
        while (archivo.exists()) {
            nombre = "${type}_${AGENT_ID}_${fecha}_$repeticion.$extension"
            archivo = File(carpeta, nombre)
            repeticion++
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", archivo)
        return MediaTarget(uri, archivo, nombre)
    }

    // ── Cifrado de la evidencia (formato FEVD v1) ────────────────────────────
    // Punto de enganche del cifrado, igual que en la app de la bodycam: SIEMPRE
    // al cerrar el destino, nunca durante la captura. El contrato del formato es
    // docs/CRYPTO-FORMAT.md del repo BodyCamServer; las dos apps producen el
    // mismo fichero con el mismo codigo (data/crypto).

    /**
     * Cierra la captura: la cifra y borra el original en claro. Suspende porque el
     * cifrado hace disco y CPU y nunca puede correr en el hilo principal (una
     * grabacion de 20 min tarda unos 5 s).
     *
     * Si el cifrado falla el original se queda donde esta, sin cifrar, y el fallo
     * queda en el log: perder la evidencia seria peor que tenerla en claro dentro
     * de la carpeta privada de la app.
     */
    suspend fun seal(target: MediaTarget, conservarClaro: Boolean = false): EvidenceCrypto.Sealed? =
        withContext(Dispatchers.IO) {
            val carpeta = evidenceDir() ?: run {
                Log.w(TAG, "Sin carpeta privada para el .fev: la evidencia queda sin cifrar")
                return@withContext null
            }
            EvidenceCrypto.seal(
                plain = target.file,
                dest = File(carpeta, target.name + EvidenceCrypto.EXTENSION),
                borrarClaro = EvidenceCrypto.DELETE_PLAINTEXT && !conservarClaro,
            )
        }

    /**
     * Donde se escribe en claro el proxy de [original]: junto a el, en captures/, y
     * con un sufijo propio para que nunca se confunda con una captura.
     */
    fun createProxyFile(original: File): File? {
        val carpeta = capturesDir() ?: return null
        return File(carpeta, original.name.removeSuffix(".mp4") + PROXY_SUFFIX)
    }

    /**
     * Cifra un proxy y borra su claro. Va a proxies/ y no a evidence/ porque todo lo
     * que hay en evidence/ lo lista la boveda y lo sube como evidencia: un proxy
     * ahi apareceria duplicado. El proxy no es evidencia, es una copia para el
     * backend que desaparece en cuanto se entrega.
     */
    suspend fun sealProxy(plain: File): EvidenceCrypto.Sealed? = withContext(Dispatchers.IO) {
        val carpeta = privateDir(PROXIES_FOLDER) ?: return@withContext null
        EvidenceCrypto.seal(plain, File(carpeta, plain.name + EvidenceCrypto.EXTENSION), borrarClaro = true)
    }

    /**
     * Videos que ya estan cifrados pero siguen en claro en captures/: la app murio
     * mientras se hacia su proxy. El claro solo sobrevive al cifrado por eso, asi
     * que su .fev es la prueba de que falta el proxy y no la captura entera.
     */
    fun videosSinProxy(): List<File> {
        val capturas = capturesDir() ?: return emptyList()
        val evidencia = evidenceDir() ?: return emptyList()
        val ficheros = capturas.listFiles { f ->
            f.isFile && f.name.startsWith("video_") && !f.name.endsWith(PROXY_SUFFIX) &&
                File(evidencia, f.name + EvidenceCrypto.EXTENSION).isFile
        }
        return ficheros?.toList().orEmpty()
    }

    /** Proxies en claro que se quedaron a medio escribir. No valen: se rehacen. */
    fun proxiesSinTerminar(): List<File> {
        val ficheros = capturesDir()?.listFiles { f -> f.name.endsWith(PROXY_SUFFIX) }
        return ficheros?.toList().orEmpty()
    }

    /**
     * Carpeta donde la camara y el grabador escriben la captura en claro. Es
     * privada de la app y su contenido solo vive los segundos que van del cierre
     * de la captura al cifrado.
     */
    private fun capturesDir(): File? = privateDir(CAPTURES_FOLDER)

    /** Carpeta de los .fev. Privada de la app, nunca la galeria. */
    private fun evidenceDir(): File? = privateDir(EVIDENCE_FOLDER)

    private fun privateDir(nombre: String): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val carpeta = File(base, nombre)
        return if (carpeta.isDirectory || carpeta.mkdirs()) carpeta else null
    }

    /** Descarta una captura cancelada o fallida. */
    fun discard(target: MediaTarget) {
        if (target.file.exists() && !target.file.delete()) {
            Log.w(TAG, "No se pudo descartar la captura")
        }
    }

    /**
     * Duracion legible de un video o audio recien capturado, o null si no se
     * puede leer. Hay que llamarla antes de [seal]: despues del cifrado el
     * original ya no existe.
     */
    fun mediaDuration(target: MediaTarget): String? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, target.uri)
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        retriever.release()
        millis?.let {
            val segundos = (it / 1000).toInt()
            "${segundos / 60}m ${segundos % 60}s"
        }
    } catch (e: Exception) {
        null
    }

    // ── Nota de audio con el microfono del telefono ──────────────────────────

    private var recorder: MediaRecorder? = null
    private var audioTarget: MediaTarget? = null

    // Scope de aplicacion, igual que en EvidenceUploader: cerrar una nota de audio
    // no puede depender de que siga viva la pantalla que la empezo.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Empieza a grabar audio. Requiere el permiso RECORD_AUDIO ya concedido. */
    fun startAudioRecording(): Boolean {
        if (recorder != null) return true
        val destino = createAudioTarget() ?: return false
        return try {
            // El constructor sin context quedo obsoleto en Android 12.
            val nuevo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            nuevo.setAudioSource(MediaRecorder.AudioSource.MIC)
            nuevo.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            nuevo.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            nuevo.setOutputFile(destino.file.absolutePath)
            nuevo.prepare()
            nuevo.start()
            recorder = nuevo
            audioTarget = destino
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la grabacion de audio", e)
            recorder?.release()
            recorder = null
            discard(destino)
            false
        }
    }

    /** Detiene la grabacion y la cifra. Es el camino normal de una nota de audio. */
    suspend fun stopAudioRecordingSealed(): SealedCapture? {
        val destino = stopAudioRecorder() ?: return null
        return SealedCapture(destino, seal(destino))
    }

    /**
     * Detiene la grabacion y la cifra en segundo plano. Es el camino de cuando la
     * pantalla se destruye grabando: ahi ya no hay ViewModel donde suspender, pero
     * la nota tampoco puede quedarse en claro.
     */
    fun stopAudioRecording() {
        val destino = stopAudioRecorder() ?: return
        scope.launch { seal(destino) }
    }

    /** Detiene y libera el grabador. Devuelve el destino aun sin cifrar. */
    private fun stopAudioRecorder(): MediaTarget? {
        val activo = recorder ?: return null
        val destino = audioTarget
        recorder = null
        audioTarget = null
        return try {
            activo.stop()
            activo.release()
            destino
        } catch (e: Exception) {
            // stop() lanza si la grabacion fue demasiado corta: se descarta.
            Log.w(TAG, "Grabacion de audio descartada: ${e.message}")
            activo.release()
            destino?.let(::discard)
            null
        }
    }

    companion object {
        private const val TAG = "LocalEvidence"

        // Carpetas dentro del almacenamiento privado de la app
        // (Android/data/<paquete>/files). Fuera de la galeria y, desde Android 11,
        // fuera del alcance del explorador de archivos y de otras apps.
        private const val CAPTURES_FOLDER = "captures"
        private const val EVIDENCE_FOLDER = "evidence"

        /** Proxies cifrados a la espera de subirse. La lee tambien EvidenceUploader. */
        const val PROXIES_FOLDER = "proxies"
        private const val PROXY_SUFFIX = "_proxy.mp4"

        // Sin sistema de login todavia: agente fijo para nombrar la evidencia.
        private const val AGENT_ID = "agent_007"
    }
}
