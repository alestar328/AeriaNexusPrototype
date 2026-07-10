package com.delta.aeria_nexus_prototype.feature.activeincident

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.feature.activeincident.ActiveIncidentViewModel.Companion.formatSeconds
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.SectionLabel
import com.delta.aeria_nexus_prototype.ui.components.TextBadge
import com.delta.aeria_nexus_prototype.ui.components.VideoEvidenceCard
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.NaranjaPendiente
import com.delta.aeria_nexus_prototype.ui.theme.PurpuraTestigo
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Pantalla del incidente en curso: captura de video, foto, audio y testigos.
 * Es la pantalla principal de trabajo en campo.
 */
@Composable
fun ActiveIncidentScreen(
    viewModel: ActiveIncidentViewModel,
    onBackToOperations: () -> Unit,
    onIncidentEnded: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val incidente by viewModel.activeIncident.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Captura con el telefono cuando la bodycam no esta conectada: la app de
    // camara del sistema escribe directo en la carpeta local de evidencia.
    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { exito -> viewModel.onPhonePhotoResult(exito) }
    val recordVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { exito -> viewModel.onPhoneVideoResult(exito) }

    // La app declara CAMERA (livestream), por eso Android exige tenerlo
    // concedido antes de poder abrir la app de camara con estos intents. En
    // Android 9 o menor se suma el permiso de almacenamiento (album publico).
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        if (resultados.values.all { it }) {
            viewModel.preparePhonePhoto()?.let { takePhotoLauncher.launch(it) }
        }
    }
    val videoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        if (resultados.values.all { it }) {
            viewModel.preparePhoneVideo()?.let { recordVideoLauncher.launch(it) }
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados -> if (resultados.values.all { it }) viewModel.toggleAudioNote() }

    AppScaffold(
        currentTab = null,
        onTabSelected = onTabSelected,
        isRecording = incidente?.isRecording == true,
        showNav = false,
    ) { innerPadding ->
        val actual = incidente
        if (actual == null) {
            NoActiveIncidentMessage(
                modifier = Modifier.padding(innerPadding),
                onBack = onBackToOperations,
            )
            return@AppScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ActiveHeader(
                incident = actual,
                incidentSeconds = uiState.incidentSeconds,
                onBack = onBackToOperations,
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ultimoVideo = actual.evidence.lastOrNull { it.type == EvidenceType.VIDEO }
                if (actual.isRecording || ultimoVideo != null) {
                    VideoEvidenceCard(
                        isRecording = actual.isRecording,
                        isSealed = actual.reportSubmitted && ultimoVideo != null,
                        sealedLabel = "SEALED",
                        footerText = if (actual.isRecording) {
                            "Encrypted capture in progress"
                        } else {
                            ultimoVideo?.hash ?: "Encrypted"
                        },
                        devices = (ultimoVideo?.device ?: "FC + FL + AN").split(" + "),
                        durationText = if (actual.isRecording) {
                            formatSeconds(uiState.recordingSeconds)
                        } else {
                            ultimoVideo?.duration
                        },
                    )
                }

                CountersCard(evidenceCount = actual.evidenceCount, witnessCount = actual.witnessCount)

                ActionGrid(
                    isRecording = actual.isRecording,
                    recordingSeconds = uiState.recordingSeconds,
                    isAudioRecording = uiState.isAudioRecording,
                    audioSeconds = uiState.audioSeconds,
                    // Sin bodycam, video y foto se capturan con el telefono;
                    // con bodycam, los comandos Bluetooth disparan su camara.
                    onRecord = {
                        if (viewModel.usesPhoneCapture) {
                            videoPermissionLauncher.launch(capturePermissions(Manifest.permission.CAMERA))
                        } else {
                            viewModel.toggleRecording()
                        }
                    },
                    onPhoto = {
                        if (viewModel.usesPhoneCapture) {
                            photoPermissionLauncher.launch(capturePermissions(Manifest.permission.CAMERA))
                        } else {
                            viewModel.capturePhoto()
                        }
                    },
                    onAudio = {
                        if (uiState.isAudioRecording) {
                            viewModel.toggleAudioNote()
                        } else {
                            audioPermissionLauncher.launch(capturePermissions(Manifest.permission.RECORD_AUDIO))
                        }
                    },
                    onWitness = viewModel::generateWitnessQr,
                )

                if (actual.evidence.isNotEmpty()) {
                    EvidenceListCard(actual.evidence)
                }

                if (actual.timeline.isNotEmpty()) {
                    TimelineListCard(actual)
                }

                EndIncidentButton(
                    onClick = {
                        viewModel.endIncident()
                        onIncidentEnded()
                    },
                )
            }
        }
    }

    if (uiState.pendingEvidence != null) {
        ClassifySheet(
            onClassify = viewModel::classifyPendingEvidence,
            onSkip = viewModel::skipClassification,
        )
    }

    if (uiState.showWitnessQr) {
        WitnessQrDialog(
            secondsLeft = uiState.qrSecondsLeft,
            onDismiss = viewModel::dismissWitnessQr,
        )
    }
}

@Composable
private fun NoActiveIncidentMessage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No active incident found.", color = TextoSecundario, fontSize = 14.sp)
        Text(
            text = "Back to Operations",
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(onClick = onBack)
                .padding(8.dp),
            color = AzulClaro,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActiveHeader(incident: ActiveIncident, incidentSeconds: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextoPrincipal)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = incident.id,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(8.dp))
                TextBadge(text = "Active", color = VerdeOk, showDot = true)
            }
            Text(
                text = formatStartDate(incident.startedAtMillis),
                color = TextoTerciario,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatSeconds(incidentSeconds),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            Text(text = "Duration", color = TextoTerciario, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CountersCard(evidenceCount: Int, witnessCount: Int) {
    CardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            CounterCell("$evidenceCount", "Evidence", Modifier.weight(1f))
            CounterCell("$witnessCount", "Witness", Modifier.weight(1f))
            CounterCell("Local Only", "Sync", Modifier.weight(1f), smallValue = true)
        }
    }
}

@Composable
private fun CounterCell(value: String, label: String, modifier: Modifier = Modifier, smallValue: Boolean = false) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Color.White,
            fontSize = if (smallValue) 14.sp else 24.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = label.uppercase(),
            color = TextoTerciario,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ActionGrid(
    isRecording: Boolean,
    recordingSeconds: Int,
    isAudioRecording: Boolean,
    audioSeconds: Int,
    onRecord: () -> Unit,
    onPhoto: () -> Unit,
    onAudio: () -> Unit,
    onWitness: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptureButton(
                label = if (isRecording) "STOP" else "RECORD",
                sublabel = if (isRecording) formatSeconds(recordingSeconds) else null,
                icon = if (isRecording) Icons.Filled.Stop else Icons.Filled.Videocam,
                accentColor = RojoSuave,
                highlighted = isRecording,
                onClick = onRecord,
                modifier = Modifier.weight(1f),
            )
            CaptureButton(
                label = "PHOTO",
                sublabel = null,
                icon = Icons.Filled.CameraAlt,
                accentColor = AzulClaro,
                highlighted = false,
                onClick = onPhoto,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptureButton(
                label = if (isAudioRecording) "STOP" else "AUDIO",
                sublabel = if (isAudioRecording) formatSeconds(audioSeconds) else null,
                icon = Icons.Filled.Mic,
                accentColor = NaranjaPendiente,
                highlighted = isAudioRecording,
                onClick = onAudio,
                modifier = Modifier.weight(1f),
            )
            CaptureButton(
                label = "WITNESS",
                sublabel = null,
                icon = Icons.Filled.QrCode2,
                accentColor = PurpuraTestigo,
                highlighted = false,
                onClick = onWitness,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CaptureButton(
    label: String,
    sublabel: String?,
    icon: ImageVector,
    accentColor: Color,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fondo = if (highlighted) accentColor.copy(alpha = 0.25f) else Superficie
    val borde = if (highlighted) accentColor.copy(alpha = 0.5f) else BordeSutil

    Column(
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fondo)
            .border(1.dp, borde, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = accentColor, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        if (sublabel != null) {
            Text(
                text = sublabel,
                color = accentColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EvidenceListCard(evidence: List<EvidenceRecord>) {
    CardSurface {
        SectionLabel(
            text = "Evidence (${evidence.size})",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = BordeSutil)
        evidence.forEach { registro ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (registro.type) {
                        EvidenceType.VIDEO -> Icons.Filled.Videocam
                        EvidenceType.PHOTO -> Icons.Filled.CameraAlt
                        EvidenceType.AUDIO -> Icons.Filled.Mic
                        EvidenceType.WITNESS_UPLOAD -> Icons.Filled.QrCode2
                    },
                    contentDescription = null,
                    tint = TextoSecundario,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = registro.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = registro.time,
                            color = TextoTerciario,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (registro.duration != null) {
                            Text(text = registro.duration, color = TextoTerciario, fontSize = 10.sp)
                        }
                        if (registro.classification != null) {
                            Text(text = registro.classification.label, color = TextoSecundario, fontSize = 10.sp)
                        }
                    }
                }
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Encrypted",
                    tint = VerdeOk,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(6.dp))
                TextBadge(text = "Local", color = AzulClaro)
            }
        }
    }
}

@Composable
private fun TimelineListCard(incident: ActiveIncident) {
    CardSurface {
        SectionLabel(
            text = "Timeline",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = BordeSutil)
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Se muestra del mas reciente al mas antiguo, como en el prototipo.
            incident.timeline.reversed().forEach { entrada ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = entrada.time,
                        modifier = Modifier.width(44.dp),
                        color = TextoTerciario,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = entrada.event,
                        color = TextoPrincipal,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EndIncidentButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Superficie)
            .border(1.dp, RojoCritico.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "END INCIDENT",
            color = RojoSuave,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassifySheet(onClassify: (EvidenceClass) -> Unit, onSkip: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onSkip, containerColor = Superficie) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "CLASSIFY EVIDENCE",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(180.dp),
            ) {
                items(EvidenceClass.entries) { clase ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, BordeSutil, RoundedCornerShape(12.dp))
                            .clickable { onClassify(clase) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = clase.label,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Text(
                text = "Skip classification",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSkip)
                    .padding(vertical = 16.dp),
                color = TextoSecundario,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WitnessQrDialog(secondsLeft: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CardSurface {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "WITNESS UPLOAD",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(20.dp))
                QrPlaceholder()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Show this QR to the witness",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = NaranjaPendiente,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Expires in %02d:%02d".format(secondsLeft / 60, secondsLeft % 60),
                        color = NaranjaPendiente,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Cuadricula 7x7 aleatoria que simula un codigo QR en el prototipo. */
@Composable
private fun QrPlaceholder() {
    // remember evita regenerar el patron en cada recomposicion del contador.
    val celdas = remember { List(49) { Random.nextBoolean() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(7) { fila ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(7) { columna ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                if (celdas[fila * 7 + columna]) Color.Black else Color.White,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

private fun formatStartDate(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))

/** Permisos de captura: en Android 9 o menor se suma el de almacenamiento. */
private fun capturePermissions(main: String): Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        arrayOf(main)
    } else {
        arrayOf(main, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
