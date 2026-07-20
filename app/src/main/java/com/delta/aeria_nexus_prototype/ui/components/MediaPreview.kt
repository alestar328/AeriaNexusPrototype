package com.delta.aeria_nexus_prototype.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.NaranjaPendiente
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vista previa de la evidencia capturada con el telefono (album localIncidents).
 * La foto se abre a pantalla completa dentro de la app; el video y el audio se
 * abren con el reproductor del sistema (sin librerias extra, regla de app ligera).
 */
@Composable
fun EvidenceMediaPreview(
    mediaUri: String,
    type: EvidenceType,
    modifier: Modifier = Modifier,
) {
    when (type) {
        EvidenceType.PHOTO, EvidenceType.VIDEO -> VisualPreview(mediaUri, type, modifier)
        EvidenceType.AUDIO -> AudioPlayRow(mediaUri, modifier)
        else -> Unit
    }
}

/** Miniatura tocable de una foto o video. */
@Composable
private fun VisualPreview(mediaUri: String, type: EvidenceType, modifier: Modifier) {
    val context = LocalContext.current
    var showPhotoViewer by remember { mutableStateOf(false) }

    // La miniatura se decodifica fuera del hilo principal una sola vez por uri.
    val thumbnail by produceState<Bitmap?>(initialValue = null, mediaUri) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(context, Uri.parse(mediaUri), isVideo = type == EvidenceType.VIDEO)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .clickable {
                if (type == EvidenceType.PHOTO) showPhotoViewer = true
                else openWithSystemPlayer(context, mediaUri, "video/mp4")
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (type == EvidenceType.PHOTO) "Photo evidence" else "Video evidence",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Sin miniatura (cargando o archivo borrado de la galeria).
            Icon(
                Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = TextoTerciario,
                modifier = Modifier.size(28.dp),
            )
        }
        if (type == EvidenceType.VIDEO) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    if (showPhotoViewer) {
        PhotoViewerDialog(mediaUri = mediaUri, onDismiss = { showPhotoViewer = false })
    }
}

/** Fila tocable para reproducir una nota de audio con el reproductor del sistema. */
@Composable
private fun AudioPlayRow(mediaUri: String, modifier: Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NaranjaPendiente.copy(alpha = 0.12f))
            .clickable { openWithSystemPlayer(context, mediaUri, "audio/mp4") }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = NaranjaPendiente,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "PLAY AUDIO NOTE",
            color = NaranjaPendiente,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

/** Foto a pantalla completa sobre fondo negro; se cierra con la X o tocando fuera. */
@Composable
private fun PhotoViewerDialog(mediaUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fullImage by produceState<Bitmap?>(initialValue = null, mediaUri) {
        value = withContext(Dispatchers.IO) {
            decodeScaledImage(context, Uri.parse(mediaUri), maxSide = FULL_IMAGE_MAX_SIDE)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            val bitmap = fullImage
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo evidence",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = "Loading…",
                    modifier = Modifier.align(Alignment.Center),
                    color = AzulClaro,
                    fontSize = 12.sp,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** Abre el archivo con la app del sistema que sepa reproducirlo. */
private fun openWithSystemPlayer(context: Context, mediaUri: String, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(mediaUri), mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No hay app para abrir $mimeType")
    }
}

/** Miniatura de la foto o del primer frame del video; null si no se puede leer. */
private fun loadThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_SIDE, THUMBNAIL_SIDE), null)
    } else if (isVideo) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val frame = retriever.frameAtTime
        retriever.release()
        frame
    } else {
        decodeScaledImage(context, uri, maxSide = THUMBNAIL_SIDE)
    }
} catch (e: Exception) {
    Log.w(TAG, "No se pudo cargar la miniatura", e)
    null
}

/**
 * Decodifica una imagen reducida para no cargar en memoria la resolucion
 * completa de la camara: primera pasada solo mide, la segunda decodifica
 * con inSampleSize hasta que el lado mayor quepa en maxSide.
 */
private fun decodeScaledImage(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxSide) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    bitmap?.let { aplicarOrientacionExif(context, uri, it) }
} catch (e: Exception) {
    Log.w(TAG, "No se pudo decodificar la imagen", e)
    null
}

/**
 * BitmapFactory ignora la etiqueta de orientacion EXIF que graba la camara,
 * por eso las fotos en vertical se veian giradas 90 grados. Aqui se lee esa
 * etiqueta y se rota el bitmap para mostrarlo derecho.
 */
private fun aplicarOrientacionExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientacion = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la orientacion EXIF", e)
        ExifInterface.ORIENTATION_NORMAL
    }
    val grados = when (orientacion) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (grados == 0f) return bitmap
    val matriz = Matrix().apply { postRotate(grados) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matriz, true)
}

private const val TAG = "MediaPreview"
private const val THUMBNAIL_SIDE = 640
private const val FULL_IMAGE_MAX_SIDE = 2048
