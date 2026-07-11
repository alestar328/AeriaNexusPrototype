package com.delta.aeria_nexus_prototype.feature.bodycam

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Controlador remoto de la bodycam. Livestream (con semantica de SOS: alerta
 * a todas las unidades), foto y grabacion local, mas conexion Bluetooth.
 */
@Composable
fun BodycamControllerScreen(
    viewModel: BodycamControllerViewModel,
    onOpenLivestream: () -> Unit,
    onOpenViewfinder: () -> Unit,
    onBack: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // El visor se abre solo cuando la bodycam confirmo el stream pedido aqui.
    LaunchedEffect(uiState.openViewer) {
        if (uiState.openViewer) {
            viewModel.onViewerOpened()
            onOpenLivestream()
        }
    }

    // El permiso Bluetooth es de runtime desde Android 12; se pide al tocar
    // CONNECT y, si se concede, se conecta en el mismo gesto.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> if (concedido) viewModel.connect() }

    AppScaffold(
        currentTab = null,
        onTabSelected = onTabSelected,
        isRecording = uiState.isRecording,
        showNav = false,
    ) { innerPadding ->
        BodycamControllerContent(
            uiState = uiState,
            onBack = onBack,
            onConnect = {
                if (!viewModel.hasBluetoothPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    viewModel.connect()
                }
            },
            onDisconnect = viewModel::disconnect,
            onToggleLivestream = viewModel::toggleLivestream,
            onTogglePhoto = viewModel::takePhoto,
            onToggleRecording = viewModel::toggleRecording,
            onOpenLivestream = onOpenLivestream,
            onOpenViewfinder = onOpenViewfinder,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun BodycamControllerContent(
    uiState: BodycamControllerUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleLivestream: () -> Unit,
    onTogglePhoto: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenLivestream: () -> Unit,
    onOpenViewfinder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conectada = uiState.connectionState == BodycamState.CONNECTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ControllerHeader(onBack = onBack)
        StatusCard(uiState = uiState)

        if (uiState.commandFeedback != null) {
            FeedbackBanner(text = uiState.commandFeedback)
        }

        Spacer(Modifier.weight(1f))

        if (conectada) {
            if (uiState.isStreaming) {
                SecondaryPanelButton(
                    text = "VIEW LIVE FEED",
                    icon = Icons.Filled.Videocam,
                    onClick = onOpenLivestream,
                )
            } else {
                // Visor para la foto a distancia: la camara es exclusiva, por
                // eso no se ofrece mientras la bodycam transmite en vivo.
                SecondaryPanelButton(
                    text = "REMOTE VIEWFINDER",
                    icon = Icons.Filled.CenterFocusStrong,
                    onClick = onOpenViewfinder,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceActionButton(
                    label = "PHOTO",
                    sublabel = "Saved on bodycam",
                    icon = Icons.Filled.CameraAlt,
                    accentColor = AzulClaro,
                    onClick = onTogglePhoto,
                    modifier = Modifier.weight(1f),
                )
                DeviceActionButton(
                    label = if (uiState.isRecording) "STOP REC" else "RECORD",
                    sublabel = if (uiState.isRecording) "Recording…" else "Local only, no SOS",
                    icon = Icons.Filled.Videocam,
                    accentColor = if (uiState.isRecording) RojoCritico else VerdeOk,
                    onClick = onToggleRecording,
                    modifier = Modifier.weight(1f),
                )
            }
            LivestreamButton(isStreaming = uiState.isStreaming, onClick = onToggleLivestream)
            DisconnectButton(onClick = onDisconnect)
        } else {
            ConnectPanel(state = uiState.connectionState, onConnect = onConnect, onCancel = onDisconnect)
        }
    }
}

@Composable
private fun ControllerHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Superficie)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "BODYCAM",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = "Falcon Camera W1 — remote control",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatusCard(uiState: BodycamControllerUiState) {
    val (colorEstado, textoEstado) = when (uiState.connectionState) {
        BodycamState.CONNECTED -> VerdeOk to "CONNECTED"
        BodycamState.CONNECTING -> AmarilloAviso to "CONNECTING…"
        BodycamState.ERROR -> RojoCritico to "CONNECTION ERROR"
        BodycamState.DISCONNECTED -> TextoTerciario to "DISCONNECTED"
    }

    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(colorEstado, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = textoEstado,
                color = colorEstado,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            if (uiState.connectionState == BodycamState.CONNECTED) {
                Text(
                    text = "BATTERY ${uiState.batteryPercent}%",
                    color = if (uiState.batteryPercent <= 20) RojoCritico else TextoSecundario,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FeedbackBanner(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AzulOscuroPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = AzulClaro,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Accion principal de la pantalla. Encender el livestream equivale al boton
 * fisico SOS de la bodycam: todas las unidades reciben la emergencia, por eso
 * el boton es rojo y lo dice de forma explicita.
 */
@Composable
private fun LivestreamButton(isStreaming: Boolean, onClick: () -> Unit) {
    // El parpadeo hace inconfundible que la bodycam sigue transmitiendo.
    val parpadeo by rememberInfiniteTransition(label = "streamParpadeo").animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "streamParpadeo",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .alpha(if (isStreaming) parpadeo else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(RojoCritico, Color(0xFFDC2626))))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Sensors,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isStreaming) "LIVE — TAP TO STOP" else "LIVESTREAM — SOS",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isStreaming) {
                "Every unit is receiving this emergency"
            } else {
                "Bodycam goes live and alerts every unit"
            },
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
    }
}

/**
 * Boton secundario del panel: abre el visor del livestream (uid 9001) o el
 * visor remoto de foto, segun el estado de la bodycam.
 */
@Composable
private fun SecondaryPanelButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AzulOscuroPanel)
            .border(1.dp, AzulPrimario.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AzulClaro,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = AzulClaro,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Boton de foto o grabacion: acciones locales de la bodycam, sin SOS. */
@Composable
private fun DeviceActionButton(
    label: String,
    sublabel: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Superficie)
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Text(
            text = sublabel,
            color = TextoTerciario,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DisconnectButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "DISCONNECT BODYCAM",
            color = TextoSecundario,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

/** Estado sin enlace: un unico boton grande para conectar (o cancelar). */
@Composable
private fun ConnectPanel(
    state: BodycamState,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when (state) {
                BodycamState.CONNECTING -> "Searching for the bodycam…"
                BodycamState.ERROR -> "Could not reach the bodycam. Check that it is on, with Bluetooth enabled and within range."
                else -> "Connect to the bodycam to control livestream, photo and recording from this phone."
            },
            color = TextoSecundario,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        val conectando = state == BodycamState.CONNECTING
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (conectando) Superficie else AzulPrimario)
                .clickable { if (conectando) onCancel() else onConnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (conectando) "CANCEL" else "CONNECT BODYCAM",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
        }
    }
}
