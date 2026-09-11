package com.delta.aeria_nexus_prototype.feature.activeincident

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delta.aeria_nexus_prototype.feature.activeincident.ActiveIncidentViewModel.Companion.formatSeconds
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import java.io.File
import kotlinx.coroutines.delay

private const val TAG = "PhoneCamera"

/**
 * Camara del telefono para grabar video, a pantalla completa sobre el incidente.
 *
 * Graba con CameraX a 1080p, y si el telefono no la tiene, a la calidad mas cercana
 * por debajo: la app de camara del sistema no deja pedir resolucion ni fps. CameraX
 * se ata al ciclo de vida de la pantalla, asi que vive aqui; el ViewModel solo se
 * entera de lo que pasa: empieza, se cierra el fichero, se suelta la camara.
 */
@Composable
fun PhoneCameraOverlay(
    videoFile: File,
    isRecording: Boolean,
    recordingSeconds: Int,
    autoStart: Boolean,
    stopRequested: Boolean,
    onRecordingStarted: () -> Unit,
    onRecordingFinalized: (hayVideo: Boolean) -> Unit,
    onClose: () -> Unit,
    onSos: () -> Unit,
    onCameraReleased: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            videoCaptureQualitySelector = QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
            )
        }
    }
    var grabacion by remember { mutableStateOf<Recording?>(null) }
    // Los avisos de CameraX llegan despues, a veces con la pantalla ya fuera: tienen
    // que llamar a la lambda vigente, no a la de cuando empezo la grabacion.
    val alEmpezar by rememberUpdatedState(onRecordingStarted)
    val alCerrarse by rememberUpdatedState(onRecordingFinalized)
    val alSoltar by rememberUpdatedState(onCameraReleased)

    fun empezar() {
        if (grabacion != null) return
        grabacion = iniciarGrabacion(
            context = context,
            controller = controller,
            destino = videoFile,
            onStart = { alEmpezar() },
            onFinalize = { hayVideo ->
                grabacion = null
                alCerrarse(hayVideo)
            },
        )
    }

    fun pararOCerrar() {
        val enCurso = grabacion
        if (enCurso != null) enCurso.stop() else onClose()
    }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            // Si la pantalla se va grabando, el fichero se cierra: lo grabado es evidencia.
            grabacion?.stop()
            controller.unbind()
            alSoltar()
        }
    }
    LaunchedEffect(autoStart) {
        if (!autoStart) return@LaunchedEffect
        // CameraX se inicializa en segundo plano, y grabar antes de que termine falla.
        while (!controller.initializationFuture.isDone) delay(CAMERA_POLL_MILLIS)
        empezar()
    }
    LaunchedEffect(stopRequested) {
        if (stopRequested) grabacion?.stop()
    }
    BackHandler(onBack = ::pararOCerrar)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView y no SurfaceView: dentro de Compose el SurfaceView se
                    // pinta en una capa detras de la ventana y se ve negro.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    // FIT y no FILL: el controlador recorta la GRABACION a lo que se ve
                    // en la vista previa. Con FILL, a pantalla completa, el video salia
                    // a 886x1920 en vez de 1080x1920 (medido en el Samsung el
                    // 2026-09-11). Con FIT se graba el fotograma entero y la vista
                    // previa lo muestra con bandas: lo que se ve es lo que queda.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        CameraControls(
            isRecording = isRecording,
            recordingSeconds = recordingSeconds,
            onRecord = { if (grabacion != null) grabacion?.stop() else empezar() },
            onClose = ::pararOCerrar,
            onSos = onSos,
        )
    }
}

// El audio solo se pide con el permiso concedido, que se comprueba justo encima.
@SuppressLint("MissingPermission")
private fun iniciarGrabacion(
    context: Context,
    controller: LifecycleCameraController,
    destino: File,
    onStart: () -> Unit,
    onFinalize: (hayVideo: Boolean) -> Unit,
): Recording? {
    val tieneMicrofono = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    // Sin microfono se graba igual: mejor un video mudo que ninguno.
    val audio = if (tieneMicrofono) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED
    return try {
        controller.startRecording(
            FileOutputOptions.Builder(destino).build(),
            audio,
            ContextCompat.getMainExecutor(context),
        ) { evento ->
            when (evento) {
                is VideoRecordEvent.Start -> onStart()
                is VideoRecordEvent.Finalize -> {
                    // Varios errores (camara perdida, sin espacio) dejan un fichero valido:
                    // lo que decide es si hay datos, no el codigo de error.
                    if (evento.hasError()) Log.w(TAG, "grabacion cerrada con error ${evento.error}")
                    onFinalize(destino.length() > 0)
                }
            }
        }
    } catch (e: IllegalStateException) {
        Log.w(TAG, "no se pudo empezar a grabar: ${e.message}")
        null
    }
}

@Composable
private fun CameraControls(
    isRecording: Boolean,
    recordingSeconds: Int,
    onRecord: () -> Unit,
    onClose: () -> Unit,
    onSos: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Filled.Close,
                    // Grabando, cerrar no descarta nada: para y guarda.
                    contentDescription = if (isRecording) "Stop and save" else "Close camera",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            if (isRecording) RecIndicator(recordingSeconds)
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { SosButton(onClick = onSos) }
            RecordButton(isRecording = isRecording, onClick = onRecord)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecIndicator(recordingSeconds: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(RojoCritico, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "REC ${formatSeconds(recordingSeconds)}",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val descripcion = if (isRecording) "Stop recording" else "Start recording"
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = descripcion
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // Circulo rojo para grabar, cuadrado rojo para parar: la forma cambia
        // ademas del estado, que al sol el color solo no se distingue.
        val forma = if (isRecording) RoundedCornerShape(6.dp) else CircleShape
        Box(
            Modifier
                .size(if (isRecording) 32.dp else 60.dp)
                .background(RojoCritico, forma),
        )
    }
}

@Composable
private fun SosButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RojoCritico)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "SOS",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}

private const val CAMERA_POLL_MILLIS = 100L

@Preview(name = "Camara lista", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun CameraControlsListaPreview() {
    AeriaNexusPrototypeTheme {
        CameraControls(isRecording = false, recordingSeconds = 0, onRecord = {}, onClose = {}, onSos = {})
    }
}

@Preview(name = "Grabando", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun CameraControlsGrabandoPreview() {
    AeriaNexusPrototypeTheme {
        CameraControls(isRecording = true, recordingSeconds = 83, onRecord = {}, onClose = {}, onSos = {})
    }
}
