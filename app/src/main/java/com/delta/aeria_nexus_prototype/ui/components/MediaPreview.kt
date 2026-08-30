package com.delta.aeria_nexus_prototype.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.NaranjaPendiente
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Vista previa de la evidencia capturada con el telefono, que vive cifrada en la
 * boveda. [sealedName] es el nombre del .fev.
 *
 * Con la boveda bloqueada no hay nada que ensenar y se muestra el candado; al
 * desbloquearla la evidencia se descifra a una copia temporal y se ve como
 * cualquier otra foto o video. La foto se abre a pantalla completa dentro de la
 * app; el video y el audio con el reproductor del sistema (sin librerias extra,
 * regla de app ligera).
 *
 * Es el unico componente de ui/components que consulta el AppContainer: descifrar
 * es lo que da contenido a esta vista, y pasarlo resuelto desde cada pantalla
 * obligaria a las cuatro que muestran evidencia a repetir el mismo codigo.
 */
@Composable
fun EvidenceMediaPreview(
    sealedName: String,
    type: EvidenceType,
    modifier: Modifier = Modifier,
) {
    if (type == EvidenceType.WITNESS_UPLOAD) return
    val desbloqueada by EvidenceVault.desbloqueada.collectAsStateWithLifecycle()

    // Se vuelve a intentar cuando cambia el estado de la boveda: al desbloquearla,
    // las vistas previas que estaban con candado se rellenan solas.
    val descifrado by produceState(Descifrado(), sealedName, desbloqueada) {
        value = Descifrado()
        if (desbloqueada) {
            value = Descifrado(uri = AppContainer.vaultRepository.open(sealedName), intentado = true)
        }
    }

    val uri = descifrado.uri
    when {
        !desbloqueada -> SealedRow("EVIDENCE SEALED — UNLOCK VAULT TO VIEW", modifier)
        !descifrado.intentado -> SealedRow("DECRYPTING…", modifier)
        uri == null -> SealedRow("NO KEY ON THIS DEVICE — OPEN IN NEXUS", modifier)
        type == EvidenceType.AUDIO -> AudioPlayRow(uri, modifier)
        else -> VisualPreview(uri, type, modifier)
    }
}

/**
 * Resultado del descifrado. Hace falta el [intentado] para no confundir "todavia
 * estoy descifrando" con "este fichero no se puede abrir aqui".
 */
private data class Descifrado(val uri: Uri? = null, val intentado: Boolean = false)

/** Estado de la evidencia que ahora mismo no se puede mostrar, con motivo visible. */
@Composable
private fun SealedRow(texto: String, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = TextoSecundario,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = texto,
            color = TextoSecundario,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

/** Miniatura tocable de una foto o video ya descifrado. */
@Composable
private fun VisualPreview(mediaUri: Uri, type: EvidenceType, modifier: Modifier) {
    val context = LocalContext.current
    var showPhotoViewer by remember { mutableStateOf(false) }

    // La miniatura se decodifica fuera del hilo principal una sola vez por uri.
    val thumbnail by produceState<Bitmap?>(initialValue = null, mediaUri) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(context, mediaUri, isVideo = type == EvidenceType.VIDEO)
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
            // Sin miniatura: aun cargando, o el .fev no supero la verificacion.
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

/**
 * Reproductor de la nota de audio DENTRO de la app.
 *
 * Se usa el MediaPlayer del framework y no una libreria de reproduccion (regla de
 * app ligera). Sacar la nota a la app de audio del sistema, como se hacia antes,
 * significaba entregarle a otra app la copia descifrada de una evidencia.
 */
@Composable
private fun AudioPlayRow(mediaUri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var player by remember(mediaUri) { mutableStateOf<MediaPlayer?>(null) }
    var sonando by remember(mediaUri) { mutableStateOf(false) }
    var posicionMillis by remember(mediaUri) { mutableIntStateOf(0) }
    var duracionMillis by remember(mediaUri) { mutableIntStateOf(0) }

    // La barra avanza mientras suena; el bucle se para solo al pausar o al salir.
    LaunchedEffect(sonando) {
        while (sonando) {
            posicionMillis = player?.currentPosition ?: 0
            delay(PROGRESO_MILLIS)
        }
    }

    // Sin esto el audio seguiria sonando al salir de la pantalla, y el fichero
    // descifrado quedaria abierto despues de bloquear la boveda.
    DisposableEffect(mediaUri) {
        onDispose { player?.release() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NaranjaPendiente.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                val actual = player
                when {
                    actual == null -> {
                        val nuevo = abrirReproductor(context, mediaUri) {
                            sonando = false
                            posicionMillis = 0
                        }
                        if (nuevo != null) {
                            duracionMillis = nuevo.duration
                            nuevo.start()
                            player = nuevo
                            sonando = true
                        }
                    }

                    actual.isPlaying -> {
                        actual.pause()
                        sonando = false
                    }

                    else -> {
                        actual.start()
                        sonando = true
                    }
                }
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (sonando) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (sonando) "Pause audio note" else "Play audio note",
                tint = NaranjaPendiente,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "AUDIO NOTE",
                color = NaranjaPendiente,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    if (duracionMillis > 0) posicionMillis.toFloat() / duracionMillis else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = NaranjaPendiente,
                trackColor = NaranjaPendiente.copy(alpha = 0.25f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = tiempoLegible(posicionMillis) + " / " + tiempoLegible(duracionMillis),
            color = NaranjaPendiente,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Prepara el MediaPlayer de la nota, o null si el fichero no se puede reproducir.
 * prepare() es sincrono a proposito: la nota ya esta descifrada en disco local y
 * la espera es inapreciable, mientras que prepareAsync obligaria a un estado mas.
 */
private fun abrirReproductor(context: Context, mediaUri: Uri, onFin: () -> Unit): MediaPlayer? {
    val reproductor = MediaPlayer()
    return try {
        reproductor.setDataSource(context, mediaUri)
        reproductor.prepare()
        reproductor.setOnCompletionListener {
            it.seekTo(0)
            onFin()
        }
        reproductor
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo reproducir la nota de audio", e)
        reproductor.release()
        null
    }
}

private fun tiempoLegible(millis: Int): String {
    val segundos = millis / 1000
    return "%d:%02d".format(segundos / 60, segundos % 60)
}

/** Foto a pantalla completa sobre fondo negro; se cierra con la X o tocando fuera. */
@Composable
private fun PhotoViewerDialog(mediaUri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fullImage by produceState<Bitmap?>(initialValue = null, mediaUri) {
        value = withContext(Dispatchers.IO) {
            decodeScaledImage(context, mediaUri, maxSide = FULL_IMAGE_MAX_SIDE)
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

/**
 * Abre el archivo con la app del sistema que sepa reproducirlo. La copia
 * descifrada se sirve por FileProvider, de ahi el permiso de lectura temporal.
 */
fun openWithSystemPlayer(context: Context, mediaUri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(mediaUri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No hay app para abrir $mimeType")
    }
}

/**
 * Miniatura de la foto o del primer frame del video; null si no se puede leer.
 *
 * No se usa ContentResolver.loadThumbnail: solo lo implementan los proveedores del
 * sistema como MediaStore, y la evidencia descifrada la sirve el FileProvider de
 * la propia app.
 */
private fun loadThumbnail(context: Context, uri: Uri, isVideo: Boolean): Bitmap? = try {
    if (isVideo) {
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

// Refresco de la barra de la nota de audio. Cinco veces por segundo se ve fluido
// sin despertar la recomposicion en cada frame.
private const val PROGRESO_MILLIS = 200L
