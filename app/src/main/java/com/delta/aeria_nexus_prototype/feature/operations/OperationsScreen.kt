package com.delta.aeria_nexus_prototype.feature.operations

import android.Manifest
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteFin
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteInicio
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteMedio
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla principal de operaciones: crear incidente, continuar el activo,
 * accesos de radio y llamada, y boton de emergencia.
 */
@Composable
fun OperationsScreen(
    viewModel: OperationsViewModel,
    onOpenActiveIncident: (String) -> Unit,
    onOpenSosLivestream: () -> Unit,
    onOpenBodycamControl: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val activeIncident by viewModel.activeIncident.collectAsStateWithLifecycle()
    val sosActive by viewModel.sosActive.collectAsStateWithLifecycle()

    // El livestream necesita camara y microfono. Se piden al tocar EMERGENCY y
    // el SOS se emite aunque se nieguen: la alerta llega igual, solo sin video.
    val sosPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.activateSos()
        onOpenSosLivestream()
    }

    AppScaffold(
        currentTab = MainTab.OPERATIONS,
        onTabSelected = onTabSelected,
        isRecording = activeIncident?.isRecording == true,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BrandHeader()

            // Las dos tarjetas grandes se reparten con weight el alto que
            // realmente queda en este telefono, en lugar de usar alturas
            // fijas que desbordaban en pantallas bajas. Por debajo del
            // umbral se compactan ademas los contenidos internos.
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val compacto = maxHeight < 500.dp
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    NewIncidentButton(
                        compacto = compacto,
                        onClick = { onOpenActiveIncident(viewModel.createIncident()) },
                        modifier = Modifier.weight(1f),
                    )
                    ContinueIncidentButton(
                        compacto = compacto,
                        incidentId = activeIncident?.id,
                        startedAtMillis = activeIncident?.startedAtMillis,
                        onClick = { activeIncident?.let { onOpenActiveIncident(it.id) } },
                        modifier = Modifier.weight(1f),
                    )
                    BodycamControlButton(compacto = compacto, onClick = onOpenBodycamControl)
                 /**   Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Botones de radio y llamada: decorativos en el prototipo.
                        RadioActionButton(
                            icon = Icons.Filled.Mic,
                            description = "Push to talk",
                            compacto = compacto,
                            modifier = Modifier.weight(1f),
                        )
                        RadioActionButton(
                            icon = Icons.Filled.Phone,
                            description = "Call dispatch",
                            compacto = compacto,
                            modifier = Modifier.weight(1f),
                        )
                    }**/
                }
            }

            Spacer(Modifier.height(10.dp))
            EmergencyButton(
                sosActive = sosActive,
                onClick = {
                    if (sosActive) {
                        viewModel.cancelSos()
                    } else {
                        sosPermissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Text(
        text = "AERIA NEXUS",
        color = TextoTerciario,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 3.sp,
    )
    Text(
        text = "OPERATIONS",
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun NewIncidentButton(
    compacto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(AzulGradienteInicio, AzulGradienteMedio, AzulGradienteFin),
                ),
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (compacto) 40.dp else 56.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compacto) 26.dp else 36.dp),
            )
        }
        Spacer(Modifier.height(if (compacto) 6.dp else 12.dp))
        Text(
            text = "NEW INCIDENT",
            color = Color.White,
            fontSize = if (compacto) 15.sp else 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun ContinueIncidentButton(
    compacto: Boolean,
    incidentId: String?,
    startedAtMillis: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hayActivo = incidentId != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (hayActivo) 1f else 0.5f)
            .clip(RoundedCornerShape(24.dp))
            .background(Superficie)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            .clickable(enabled = hayActivo, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // En compacto el icono se omite: esta tarjeta lleva hasta tres lineas
        // de texto y con el icono no cabe en el alto que le toca por weight.
        if (!compacto) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = null,
                    tint = TextoSecundario,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = "CONTINUE ACTIVE INCIDENT",
            color = TextoTerciario,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        if (incidentId != null && startedAtMillis != null) {
            Text(
                text = incidentId,
                color = AzulClaro,
                fontSize = if (compacto) 16.sp else 18.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Started " + formatStartTime(startedAtMillis),
                color = TextoTerciario,
                fontSize = 11.sp,
            )
        } else {
            Text(
                text = "No active incident",
                color = TextoDeshabilitado,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Acceso al controlador remoto de la bodycam (livestream, foto, grabacion). */
@Composable
private fun BodycamControlButton(compacto: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn y no height: si el usuario agranda la fuente del
            // sistema, el boton crece en lugar de recortar el texto.
            .heightIn(min = if (compacto) 52.dp else 64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AzulOscuroPanel)
            .border(1.dp, AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Videocam,
            contentDescription = null,
            tint = AzulClaro,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "BODYCAM CONTROL",
            color = AzulClaro,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun RadioActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    compacto: Boolean,
    modifier: Modifier = Modifier,
) {
    // Altura propia en lugar de aspectRatio: atada al ancho, la fila crecia
    // hasta ~120.dp y era una de las causas del apinamiento vertical.
    Box(
        modifier = modifier
            .heightIn(min = if (compacto) 64.dp else 96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AzulOscuroPanel)
            .border(1.dp, AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (compacto) 48.dp else 64.dp)
                .background(AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .border(1.dp, AzulClaro.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = AzulClaro,
                modifier = Modifier.size(if (compacto) 28.dp else 36.dp),
            )
        }
    }
}

/**
 * Boton SOS de la red tactica. Un toque emite la alerta a todos los
 * dispositivos del canal; mientras esta activa, el boton parpadea y un
 * segundo toque la cancela en los receptores.
 */
@Composable
private fun EmergencyButton(sosActive: Boolean, onClick: () -> Unit) {
    // El parpadeo hace inconfundible que la emergencia propia sigue emitiendo.
    val parpadeo by rememberInfiniteTransition(label = "sosParpadeo").animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sosParpadeo",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .alpha(if (sosActive) parpadeo else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(RojoCritico, Color(0xFFDC2626))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (sosActive) "SOS ACTIVE — TAP TO CANCEL" else "EMERGENCY",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (sosActive) 1.sp else 3.sp,
        )
    }
}

private fun formatStartTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
