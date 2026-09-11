package com.delta.aeria_nexus_prototype.data.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Copia ligera de un video para el backend, que la procesa con un LLM: lado corto a
 * 720, 15 fps y bitrate bajo. Ver docs/BACKEND-PROXY-AND-SOS.md §1.
 *
 * La hace Media3 Transformer con el codificador de hardware del telefono. El original
 * solo se lee y nunca se toca: es la evidencia, y su hash es el de la cadena de
 * custodia. El audio pasa tal cual.
 */
class ProxyEncoder(private val context: Context) {

    /** Lo que se declara al subir la copia: su tamano real y su frecuencia. */
    data class Copia(val ladoCorto: Int, val fps: Int)

    /** Escribe en [destino] la copia de [original]. Null si no se pudo hacer. */
    suspend fun crear(original: File, destino: File): Copia? {
        val ladoCortoOriginal = ladoCortoDe(original) ?: return null
        val efectos = Effects(
            emptyList(),
            listOf(
                Presentation.createForShortSide(ladoCortoDelProxy(ladoCortoOriginal)),
                FrameDropEffect.createDefaultFrameDropEffect(FPS.toFloat()),
            ),
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(original)))
            .setEffects(efectos)
            .build()

        val inicio = System.currentTimeMillis()
        // Transformer se arranca y se escucha en un hilo con Looper; el trabajo pesado
        // lo hace el en los suyos, asi que el principal no se bloquea.
        val resultado = withContext(Dispatchers.Main) { exportar(item, destino) } ?: return null
        Log.d(
            TAG,
            "proxy ${resultado.width}x${resultado.height}, ${resultado.videoFrameCount} fotogramas, " +
                "${resultado.averageVideoBitrate / 1000} kbps, ${resultado.fileSizeBytes / 1024} KB " +
                "en ${System.currentTimeMillis() - inicio} ms",
        )
        return Copia(ladoCorto = minOf(resultado.width, resultado.height), fps = FPS)
    }

    private suspend fun exportar(item: EditedMediaItem, destino: File): ExportResult? =
        suspendCancellableCoroutine { continuacion ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(BITRATE).build(),
                        )
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        continuacion.resume(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.w(TAG, "no se pudo hacer el proxy: ${exportException.errorCodeName}", exportException)
                        continuacion.resume(null)
                    }
                })
                .build()
            transformer.start(item, destino.absolutePath)
            // cancel() tiene que llamarse en el hilo donde se arranco.
            continuacion.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { transformer.cancel() }
            }
        }

    private fun ladoCortoDe(video: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val ancho = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val alto = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (ancho == null || alto == null) null else minOf(ancho, alto)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no se pudo leer el tamano del video: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val TAG = "ProxyEncoder"
        const val LADO_CORTO = 720
        const val FPS = 15
        private const val BITRATE = 1_500_000

        /**
         * Nunca se agranda: un original de menos de 720 conserva su tamano y solo baja
         * fps y bitrate. Subir la resolucion no anade detalle y si anade bytes.
         */
        fun ladoCortoDelProxy(ladoCortoOriginal: Int): Int = minOf(LADO_CORTO, ladoCortoOriginal)
    }
}
