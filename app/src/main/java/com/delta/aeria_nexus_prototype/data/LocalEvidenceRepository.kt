package com.delta.aeria_nexus_prototype.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.delta.aeria_nexus_prototype.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Almacen local temporal de la evidencia capturada con el propio telefono
 * cuando la bodycam no esta conectada. Los archivos van al almacenamiento
 * PUBLICO via MediaStore para que la galeria los muestre como el album
 * "localIncidents" (fotos y videos en DCIM/localIncidents, audio en
 * Music/localIncidents). Nombres descriptivos: tipo, agente y fecha; el
 * agente fijo agent_007 se reemplazara cuando exista login real.
 */
class LocalEvidenceRepository(private val context: Context) {

    /**
     * Destino de una captura: el uri donde escribe la camara o el grabador y,
     * solo en Android 9 o menor, el archivo fisico para indexarlo a mano.
     */
    data class MediaTarget(val uri: Uri, val legacyFile: File?)

    /** Crea el destino de una foto (DCIM/localIncidents, jpg). */
    fun createPhotoTarget(): MediaTarget? =
        createTarget("photo", "jpg", "image/jpeg", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, DCIM_FOLDER, pending = false)

    /** Crea el destino de un video (DCIM/localIncidents, mp4). */
    fun createVideoTarget(): MediaTarget? =
        createTarget("video", "mp4", "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, DCIM_FOLDER, pending = false)

    private fun createAudioTarget(): MediaTarget? =
        createTarget("audio", "m4a", "audio/mp4", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MUSIC_FOLDER, pending = true)

    private fun createTarget(
        type: String,
        extension: String,
        mimeType: String,
        collection: Uri,
        relativePath: String,
        // IS_PENDING oculta el archivo hasta publicarlo, pero solo la propia app
        // puede escribir un item pendiente: la app de camara (paquete ajeno)
        // falla y devuelve cancelado. Por eso foto/video van sin pending y el
        // audio (grabado por esta app) si se oculta hasta terminar.
        pending: Boolean,
    ): MediaTarget? {
        val fecha = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val nombre = "${type}_${AGENT_ID}_$fecha.$extension"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, valores) ?: return null
            return MediaTarget(uri, legacyFile = null)
        }

        // Android 9 o menor: archivo directo en la carpeta publica (requiere
        // WRITE_EXTERNAL_STORAGE) servido a la camara via FileProvider.
        @Suppress("DEPRECATION")
        val carpeta = File(Environment.getExternalStoragePublicDirectory(relativePath.substringBefore('/')), FOLDER_NAME)
        if (!carpeta.exists() && !carpeta.mkdirs()) return null
        val archivo = File(carpeta, nombre)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", archivo)
        return MediaTarget(uri, archivo)
    }

    /** Confirma la captura: la hace visible en la galeria. */
    fun publish(target: MediaTarget) {
        if (target.legacyFile != null) {
            // El escaner del sistema indexa el archivo para que la galeria lo vea.
            MediaScannerConnection.scanFile(context, arrayOf(target.legacyFile.absolutePath), null, null)
            return
        }
        val valores = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try {
            context.contentResolver.update(target.uri, valores, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo publicar la captura", e)
        }
    }

    /** Descarta una captura cancelada o fallida. */
    fun discard(target: MediaTarget) {
        try {
            if (target.legacyFile != null) target.legacyFile.delete()
            else context.contentResolver.delete(target.uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descartar la captura", e)
        }
    }

    /** Duracion legible de un video o audio guardado, o null si no se puede leer. */
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
    private var audioDescriptor: ParcelFileDescriptor? = null

    /** Empieza a grabar audio. Requiere el permiso RECORD_AUDIO ya concedido. */
    fun startAudioRecording(): Boolean {
        if (recorder != null) return true
        val destino = createAudioTarget() ?: return false
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(destino.uri, "w")
                ?: throw IllegalStateException("Uri de audio sin descriptor")
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
            nuevo.setOutputFile(descriptor.fileDescriptor)
            nuevo.prepare()
            nuevo.start()
            recorder = nuevo
            audioTarget = destino
            audioDescriptor = descriptor
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la grabacion de audio", e)
            recorder?.release()
            recorder = null
            audioDescriptor?.close()
            audioDescriptor = null
            discard(destino)
            false
        }
    }

    /** Detiene la grabacion y devuelve el destino, o null si fallo o no habia. */
    fun stopAudioRecording(): MediaTarget? {
        val activo = recorder ?: return null
        val destino = audioTarget
        recorder = null
        audioTarget = null
        return try {
            activo.stop()
            activo.release()
            audioDescriptor?.close()
            audioDescriptor = null
            destino?.also(::publish)
        } catch (e: Exception) {
            // stop() lanza si la grabacion fue demasiado corta: se descarta.
            Log.w(TAG, "Grabacion de audio descartada: ${e.message}")
            activo.release()
            audioDescriptor?.close()
            audioDescriptor = null
            destino?.let(::discard)
            null
        }
    }

    companion object {
        private const val TAG = "LocalEvidence"
        private const val FOLDER_NAME = "localIncidents"
        private const val DCIM_FOLDER = "DCIM/$FOLDER_NAME"
        private const val MUSIC_FOLDER = "Music/$FOLDER_NAME"

        // Sin sistema de login todavia: agente fijo para nombrar la evidencia.
        private const val AGENT_ID = "agent_007"
    }
}
