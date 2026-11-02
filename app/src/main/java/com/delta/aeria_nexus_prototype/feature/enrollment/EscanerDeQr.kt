package com.delta.aeria_nexus_prototype.feature.enrollment

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lector del QR de alta, a pantalla completa (iam-session.md §4.4).
 *
 * Solo se abre desde el asistente de alta: la app no registra ningun esquema de URL,
 * asi que un QR cualquiera escaneado con la camara del sistema no puede proponerle
 * otro servidor. Entrega el texto tal cual; quien lo interpreta es el ViewModel.
 */
@Composable
fun EscanerDeQr(onLeido: (String) -> Unit, onCancelar: () -> Unit) {
    val context = LocalContext.current
    var hayPermiso by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denegado by remember { mutableStateOf(false) }
    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hayPermiso = it
        denegado = !it
    }
    LaunchedEffect(Unit) {
        if (!hayPermiso) pedirPermiso.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onCancelar)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hayPermiso) VistaDeCamara(onLeido)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SCAN ENROLLMENT QR",
                    color = TextoPrincipal,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (denegado) {
                        "Camera access is needed to read the QR. Allow it in the phone settings."
                    } else {
                        "Point the camera at the QR your supervisor shows you in AeriaOne."
                    },
                    color = if (denegado) RojoSuave else TextoSecundario,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
            // Solo una guia para el agente: zxing busca el QR en todo el fotograma.
            Box(
                Modifier
                    .size(260.dp)
                    .border(3.dp, AzulPrimario, RoundedCornerShape(16.dp)),
            )
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("CANCEL", color = TextoPrincipal, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun VistaDeCamara(onLeido: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val alLeer by rememberUpdatedState(onLeido)
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // El QR lleva unos 350 caracteres: a la resolucion por defecto (640x480)
            // los modulos quedan demasiado finos si el QR esta en un monitor lejano.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val hilo = Executors.newSingleThreadExecutor()
        val principal = ContextCompat.getMainExecutor(context)
        val lector = LectorDeQr()
        val entregado = AtomicBoolean(false)
        controller.setImageAnalysisAnalyzer(hilo) { imagen ->
            val texto = imagen.use(lector::leer)
            if (texto != null && entregado.compareAndSet(false, true)) {
                principal.execute { alLeer(texto) }
            }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            hilo.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                // TextureView: dentro de Compose el SurfaceView se ve negro.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
    )
}

/** zxing sobre el plano de luminancia del fotograma: para un QR no hace falta el color. */
private class LectorDeQr {
    private val lector = QRCodeReader()
    private val pistas = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun leer(imagen: ImageProxy): String? {
        val plano = imagen.planes[0]
        val buffer = plano.buffer.duplicate().apply { rewind() }
        val datos = ByteArray(buffer.remaining()).also { buffer.get(it) }
        // El ancho de los datos es el rowStride: cada fila puede llevar relleno al final.
        val fuente = PlanarYUVLuminanceSource(
            datos, plano.rowStride, imagen.height, 0, 0, imagen.width, imagen.height, false,
        )
        return try {
            lector.decode(BinaryBitmap(HybridBinarizer(fuente)), pistas).text
        } catch (e: Exception) {
            // Lo normal: en este fotograma no hay QR, o esta borroso.
            null
        } finally {
            lector.reset()
        }
    }
}
