package com.delta.aeria_nexus_prototype.feature.incidentdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.SectionLabel
import com.delta.aeria_nexus_prototype.ui.components.StatusBadge
import com.delta.aeria_nexus_prototype.ui.components.SyncBadge
import com.delta.aeria_nexus_prototype.ui.components.VideoEvidenceCard
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeMuySutil
import com.delta.aeria_nexus_prototype.ui.theme.NaranjaPendiente
import com.delta.aeria_nexus_prototype.ui.theme.PurpuraTestigo
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Detalle de un incidente capturado en campo: evidencia, narrativa,
 * testigos y linea de tiempo. Pantalla de solo lectura.
 */
@Composable
fun IncidentDetailScreen(
    incident: OfficerIncident?,
    onBack: () -> Unit,
    onGenerateReport: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    AppScaffold(currentTab = null, onTabSelected = onTabSelected, showNav = false) { innerPadding ->
        if (incident == null) {
            NotFoundMessage(modifier = Modifier.padding(innerPadding), onBack = onBack)
            return@AppScaffold
        }

        // Contenido de tamano fijo y corto: el scroll simple es suficiente.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            DetailHeader(incident = incident, onBack = onBack)

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val video = incident.evidence.find { it.type == EvidenceType.VIDEO }
                if (video != null) {
                    VideoEvidenceCard(
                        isRecording = false,
                        isSealed = incident.status == IncidentStatus.SUBMITTED ||
                            incident.status == IncidentStatus.COMPLETED,
                        footerText = video.hash,
                        devices = (video.device ?: "AN").split(" + "),
                        durationText = video.duration,
                    )
                }

                InfoCard(incident)

                if (incident.narrative != null) {
                    CardSurface {
                        Column(Modifier.padding(16.dp)) {
                            SectionLabel("Officer Narrative")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = incident.narrative,
                                color = TextoPrincipal,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Evidence (${incident.evidenceCount})", Modifier.padding(bottom = 8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        incident.evidence.forEach { evidencia -> EvidenceCard(evidencia) }
                    }
                }

                val testigos = incident.evidence.filter { it.type == EvidenceType.WITNESS_UPLOAD }
                if (testigos.isNotEmpty()) {
                    Column {
                        SectionLabel("Witness Submissions (${testigos.size})", Modifier.padding(bottom = 8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            testigos.forEach { testigo -> WitnessCard(testigo) }
                        }
                    }
                }

                Column {
                    SectionLabel("Incident Timeline", Modifier.padding(bottom = 8.dp))
                    TimelineCard(incident.timeline)
                }

                ActionButton(status = incident.status, onGenerateReport = onGenerateReport)
            }
        }
    }
}

@Composable
private fun NotFoundMessage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Incident not found.", color = TextoSecundario, fontSize = 14.sp)
        Text(
            text = "Back to Incidents",
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
private fun DetailHeader(incident: OfficerIncident, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextoPrincipal,
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = incident.id,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(incident.status)
            }
            Text(text = incident.type, color = TextoTerciario, fontSize = 10.sp)
        }
    }
}

@Composable
private fun InfoCard(incident: OfficerIncident) {
    CardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoLine(Icons.Filled.Place, incident.location)
            InfoLine(
                Icons.Filled.Schedule,
                listOfNotNull(incident.date, incident.time, incident.duration).joinToString(" · "),
            )
            InfoLine(Icons.Filled.Shield, "${incident.officerName} · ${incident.officerNum}")
            HorizontalDivider(color = BordeMuySutil)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${incident.evidenceCount} Evidence",
                    color = TextoSecundario,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (incident.witnessCount > 0) {
                    Spacer(Modifier.width(12.dp))
                    val plural = if (incident.witnessCount > 1) "es" else ""
                    Text(
                        text = "${incident.witnessCount} Witness$plural",
                        color = TextoSecundario,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                SyncBadge(incident.sync)
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TextoTerciario, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = TextoPrincipal, fontSize = 14.sp)
    }
}

/** Icono y color representativos de cada tipo de evidencia. */
private fun evidenceVisual(type: EvidenceType): Pair<ImageVector, Color> = when (type) {
    EvidenceType.VIDEO -> Icons.Filled.Videocam to RojoSuave
    EvidenceType.PHOTO -> Icons.Filled.CameraAlt to AzulClaro
    EvidenceType.AUDIO -> Icons.Filled.Mic to NaranjaPendiente
    EvidenceType.WITNESS_UPLOAD -> Icons.Filled.QrCode2 to PurpuraTestigo
}

@Composable
private fun EvidenceCard(evidence: EvidenceRecord) {
    val (icono, color) = evidenceVisual(evidence.type)

    CardSurface {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = evidence.label,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = evidence.time,
                            color = TextoTerciario,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (evidence.duration != null) {
                            Text(text = evidence.duration, color = TextoTerciario, fontSize = 10.sp)
                        }
                        if (evidence.classification != null) {
                            Text(
                                text = evidence.classification.label,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = TextoSecundario,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    SyncBadge(evidence.sync)
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = VerdeOk,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(text = "Sealed", color = VerdeOk, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Tag,
                    contentDescription = "Hash",
                    tint = TextoDeshabilitado,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = evidence.hash,
                    color = TextoDeshabilitado,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun WitnessCard(witness: EvidenceRecord) {
    CardSurface {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(PurpuraTestigo.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = PurpuraTestigo,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = witness.label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = witness.time,
                    color = TextoTerciario,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Received",
                tint = VerdeOk,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Punto de color de la linea de tiempo segun el tipo de evento. */
private fun timelineDotColor(type: TimelineEntryType): Color = when (type) {
    TimelineEntryType.CREATED, TimelineEntryType.PHOTO -> AzulClaro
    TimelineEntryType.RECORDING_START -> RojoSuave
    TimelineEntryType.RECORDING_END -> RojoSuave.copy(alpha = 0.5f)
    TimelineEntryType.AUDIO -> NaranjaPendiente
    TimelineEntryType.WITNESS_QR, TimelineEntryType.WITNESS_UPLOAD -> PurpuraTestigo
    TimelineEntryType.ENDED -> TextoSecundario
    TimelineEntryType.SYSTEM -> TextoDeshabilitado
}

@Composable
private fun TimelineCard(timeline: List<TimelineEntry>) {
    CardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            timeline.forEach { entrada ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .size(8.dp)
                            .background(timelineDotColor(entrada.type), CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
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
private fun ActionButton(status: IncidentStatus, onGenerateReport: () -> Unit) {
    when (status) {
        IncidentStatus.DRAFT, IncidentStatus.NEEDS_REVIEW -> {
            Button(
                onClick = onGenerateReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "GENERATE RMS DRAFT",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        }
        IncidentStatus.COMPLETED -> {
            Button(
                onClick = onGenerateReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Superficie,
                    contentColor = TextoSecundario,
                ),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "VIEW RMS REPORT",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        }
        else -> Unit
    }
}
