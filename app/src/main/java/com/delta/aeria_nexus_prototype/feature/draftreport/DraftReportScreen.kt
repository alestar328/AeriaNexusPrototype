package com.delta.aeria_nexus_prototype.feature.draftreport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.model.ConfidenceLevel
import com.delta.aeria_nexus_prototype.data.model.NarrativeSegment
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.data.model.Suspect
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.SectionLabel
import com.delta.aeria_nexus_prototype.ui.components.SourceBadge
import com.delta.aeria_nexus_prototype.ui.components.confidenceColor
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Borrador de reporte generado por IA. Cada parrafo se resalta segun la
 * confianza de la IA y al tocarlo se muestra su evidencia enlazada.
 */
@Composable
fun DraftReportScreen(
    viewModel: DraftReportViewModel,
    onBack: () -> Unit,
    onApproved: (String) -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val incident = uiState.incident

    // Al confirmarse la aprobacion se navega al formulario RMS.
    LaunchedEffect(uiState.approved) {
        if (uiState.approved && incident != null) {
            onApproved(incident.id)
        }
    }

    AppScaffold(currentTab = null, onTabSelected = onTabSelected, showNav = false) { innerPadding ->
        if (incident == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Incident not found.", color = TextoSecundario, fontSize = 14.sp)
            }
            return@AppScaffold
        }

        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            ReportHeader(
                incident = incident,
                isEditing = uiState.isEditing,
                isRegenerating = uiState.isRegenerating,
                isApproving = uiState.isApproving,
                onBack = onBack,
                onToggleEdit = viewModel::toggleEditing,
                onRegenerate = viewModel::regenerate,
                onApprove = viewModel::approve,
            )
            ConfidenceLegend(incident.narrative)
            HorizontalDivider(color = BordeSutil)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ReportInfoHeader(incident)

                Column {
                    SectionLabel("Incident Narrative", Modifier.padding(bottom = 4.dp))
                    Text(
                        text = "Tap a paragraph to see linked evidence",
                        color = TextoTerciario,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    if (uiState.isRegenerating) {
                        RegeneratingPlaceholder()
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            incident.narrative.forEach { segmento ->
                                NarrativeParagraph(
                                    segment = segmento,
                                    text = uiState.editedTexts[segmento.id] ?: segmento.text,
                                    isSelected = segmento.id == uiState.selectedSegmentId,
                                    isEditing = uiState.isEditing,
                                    onClick = { viewModel.toggleSegment(segmento.id) },
                                    onTextChange = { viewModel.onSegmentTextChange(segmento.id, it) },
                                )
                            }
                        }
                    }
                }

                if (incident.suspects.isNotEmpty()) {
                    Column {
                        SectionLabel("Persons Involved", Modifier.padding(bottom = 8.dp))
                        incident.suspects.forEach { sospechoso -> SuspectCard(sospechoso) }
                    }
                }
            }
        }
    }

    // Hoja inferior con la evidencia del parrafo seleccionado.
    val seleccionado = incident?.narrative?.find { it.id == uiState.selectedSegmentId }
    if (seleccionado != null) {
        LinkedEvidenceSheet(segment = seleccionado, onDismiss = viewModel::clearSelection)
    }
}

@Composable
private fun ReportHeader(
    incident: ReportIncident,
    isEditing: Boolean,
    isRegenerating: Boolean,
    isApproving: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onApprove: () -> Unit,
) {
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
            Text(
                text = incident.caseNumber,
                color = TextoTerciario,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "AI-Generated Report",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(onClick = onToggleEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = if (isEditing) "Stop editing" else "Edit report",
                tint = if (isEditing) AzulPrimario else TextoSecundario,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onRegenerate, enabled = !isRegenerating) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Regenerate report",
                tint = TextoSecundario,
                modifier = Modifier.size(18.dp),
            )
        }
        Button(
            onClick = onApprove,
            enabled = !isApproving,
            colors = ButtonDefaults.buttonColors(containerColor = VerdeOk.copy(alpha = 0.9f)),
            shape = RoundedCornerShape(8.dp),
        ) {
            if (isApproving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Approve", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConfidenceLegend(narrative: List<NarrativeSegment>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AI CONFIDENCE:",
            color = TextoTerciario,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
        )
        ConfidenceLevel.entries.forEach { nivel ->
            val cantidad = narrative.count { it.confidence == nivel }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$cantidad",
                    modifier = Modifier
                        .background(confidenceColor(nivel).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    color = confidenceColor(nivel),
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when (nivel) {
                        ConfidenceLevel.HIGH -> "High"
                        ConfidenceLevel.MEDIUM -> "Med"
                        ConfidenceLevel.LOW -> "Low"
                    },
                    color = confidenceColor(nivel),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ReportInfoHeader(incident: ReportIncident) {
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = AzulPrimario,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "POLICE REPORT — AI DRAFT",
                    color = AzulPrimario,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
            }
            LabeledValue("Case Number", incident.caseNumber, mono = true)
            LabeledValue("Incident Type", incident.type)
            LabeledValue("Date & Time", "${incident.date} · ${incident.startTime}")
            LabeledValue("Reporting Officer", "${incident.officer.name} · ${incident.officer.badge}")
            LabeledValue("Location", incident.location)
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label.uppercase(),
            color = TextoTerciario,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
        Text(
            text = value,
            color = TextoPrincipal,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun RegeneratingPlaceholder() {
    // Barras grises simulando texto mientras la IA regenera el reporte.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, 0.85f, 0.95f, 0.75f, 0.9f).forEach { ancho ->
            Spacer(
                Modifier
                    .fillMaxWidth(ancho)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun NarrativeParagraph(
    segment: NarrativeSegment,
    text: String,
    isSelected: Boolean,
    isEditing: Boolean,
    onClick: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val color = confidenceColor(segment.confidence)

    if (isEditing) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextoPrincipal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = color.copy(alpha = 0.08f),
                unfocusedContainerColor = color.copy(alpha = 0.08f),
                focusedBorderColor = color.copy(alpha = 0.4f),
                unfocusedBorderColor = color.copy(alpha = 0.2f),
            ),
        )
    } else {
        val borde = if (isSelected) color.copy(alpha = 0.6f) else Color.Transparent
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                .border(1.dp, borde, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(10.dp),
            color = TextoPrincipal,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun SuspectCard(suspect: Suspect) {
    CardSurface {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = suspect.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "DOB: ${suspect.dob}   ${suspect.gender}   ${suspect.height} · ${suspect.weight}",
                modifier = Modifier.padding(vertical = 6.dp),
                color = TextoSecundario,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suspect.charges.forEach { cargo ->
                    Text(
                        text = cargo,
                        modifier = Modifier
                            .background(RojoSuave.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .border(1.dp, RojoSuave.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        color = RojoSuave,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedEvidenceSheet(segment: NarrativeSegment, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Superficie) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionLabel("Linked Evidence")
            Spacer(Modifier.height(12.dp))

            // Nivel de confianza del parrafo, con icono ademas de color.
            val color = confidenceColor(segment.confidence)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (segment.confidence == ConfidenceLevel.HIGH) Icons.Filled.Check else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.confidence.label,
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))

            val referencia = segment.evidenceRef
            if (referencia != null) {
                CardSurface {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceBadge(referencia.source)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = referencia.clipStart,
                                color = TextoPrincipal,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "to",
                                tint = TextoSecundario,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = referencia.clipEnd,
                                color = TextoPrincipal,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        HorizontalDivider(color = BordeSutil)
                        Text(
                            text = "LINKED TRANSCRIPT",
                            color = TextoTerciario,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = "\"${referencia.transcriptSnippet}\"",
                            color = TextoPrincipal,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                Text(
                    text = "No linked evidence. Manual review recommended.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RojoSuave.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, RojoSuave.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    color = RojoSuave,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
