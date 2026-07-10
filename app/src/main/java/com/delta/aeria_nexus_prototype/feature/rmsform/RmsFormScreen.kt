package com.delta.aeria_nexus_prototype.feature.rmsform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.model.ReportEvidence
import com.delta.aeria_nexus_prototype.data.model.ReportEvidenceStatus
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.data.model.Suspect
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.SectionLabel
import com.delta.aeria_nexus_prototype.ui.components.SourceBadge
import com.delta.aeria_nexus_prototype.ui.components.confidenceColor
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.SuperficiePresionada
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/** Formulario RMS de 4 pasos con navegacion entre secciones y envio final. */
@Composable
fun RmsFormScreen(
    viewModel: RmsFormViewModel,
    onBack: () -> Unit,
    onSubmitted: (String) -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val incident = uiState.incident

    // Al confirmarse el envio se navega a la pantalla de exito.
    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted && incident != null) {
            onSubmitted(incident.id)
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
            FormHeader(
                caseNumber = incident.caseNumber,
                isSubmitting = uiState.isSubmitting,
                onBack = onBack,
                onSubmit = viewModel::submit,
            )
            StepBar(current = uiState.step, onStepSelected = viewModel::goToStep)
            HorizontalDivider(color = BordeSutil)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (uiState.step) {
                    RmsStep.INFORMATION -> InformationStep(
                        incident = incident,
                        uiState = uiState,
                        onToggleEdit = viewModel::toggleEditingNarrative,
                        onNarrativeChange = viewModel::onNarrativeChange,
                        onRegenerate = viewModel::regenerate,
                        onNext = { viewModel.goToStep(RmsStep.SUSPECTS) },
                    )
                    RmsStep.SUSPECTS -> SuspectsStep(
                        suspects = incident.suspects,
                        onBackStep = { viewModel.goToStep(RmsStep.INFORMATION) },
                        onNext = { viewModel.goToStep(RmsStep.SEIZED) },
                    )
                    RmsStep.SEIZED -> SeizedStep(
                        onBackStep = { viewModel.goToStep(RmsStep.SUSPECTS) },
                        onNext = { viewModel.goToStep(RmsStep.MEDIA) },
                    )
                    RmsStep.MEDIA -> MediaStep(
                        evidence = incident.evidence,
                        isSubmitting = uiState.isSubmitting,
                        onBackStep = { viewModel.goToStep(RmsStep.SEIZED) },
                        onSubmit = viewModel::submit,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormHeader(
    caseNumber: String,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
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
                text = caseNumber,
                color = TextoTerciario,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "RMS Form",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Button(
            onClick = onSubmit,
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            shape = RoundedCornerShape(8.dp),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isSubmitting) "Submitting..." else "Submit to RMS",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StepBar(current: RmsStep, onStepSelected: (RmsStep) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie.copy(alpha = 0.5f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RmsStep.entries.forEachIndexed { indice, paso ->
            val activo = paso == current
            val completado = paso.number < current.number
            val colorTexto = when {
                activo -> AzulPrimario
                completado -> VerdeOk
                else -> TextoTerciario
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (activo) AzulPrimario.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onStepSelected(paso) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            when {
                                activo -> AzulPrimario
                                completado -> VerdeOk.copy(alpha = 0.2f)
                                else -> SuperficiePresionada
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (completado) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Done",
                            tint = VerdeOk,
                            modifier = Modifier.size(12.dp),
                        )
                    } else {
                        Text(
                            text = "${paso.number}",
                            color = if (activo) Color.White else TextoTerciario,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = paso.label,
                    color = colorTexto,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (indice < RmsStep.entries.lastIndex) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextoTerciario,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun InformationStep(
    incident: ReportIncident,
    uiState: RmsFormUiState,
    onToggleEdit: () -> Unit,
    onNarrativeChange: (String) -> Unit,
    onRegenerate: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(Icons.Filled.Description, "Incident Information")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadOnlyField("Case Number", incident.caseNumber, Modifier.weight(1f))
            ReadOnlyField("Incident Code", incident.typeCode, Modifier.weight(1f))
        }
        FieldWithIcon("Reporting Officer", Icons.Filled.Person, "${incident.officer.name}  ·  ${incident.officer.badge}")
        FieldWithIcon("Date Created", Icons.Filled.CalendarToday, "${incident.date} · ${incident.startTime}")
        FieldWithIcon("Location", Icons.Filled.Place, incident.location)

        Column {
            FieldLabel("Officers Involved")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                incident.involvedOfficers.forEach { oficial ->
                    Row(
                        modifier = Modifier
                            .background(Superficie, RoundedCornerShape(8.dp))
                            .border(1.dp, BordeSutil, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(AzulPrimario.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = initialsOf(oficial.name),
                                color = AzulClaro,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = oficial.name,
                                color = TextoPrincipal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${oficial.badge} · ${oficial.rank}",
                                color = TextoTerciario,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }

        NarrativeBlock(
            incident = incident,
            uiState = uiState,
            onToggleEdit = onToggleEdit,
            onNarrativeChange = onNarrativeChange,
            onRegenerate = onRegenerate,
        )

        StepNavigation(nextLabel = "Next: Suspects", onNext = onNext)
    }
}

@Composable
private fun NarrativeBlock(
    incident: ReportIncident,
    uiState: RmsFormUiState,
    onToggleEdit: () -> Unit,
    onNarrativeChange: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Incident Description")
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AI Generated",
                modifier = Modifier
                    .background(AzulPrimario.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .border(1.dp, AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = AzulPrimario,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = if (uiState.isEditingNarrative) "Save narrative" else "Edit narrative",
                    tint = if (uiState.isEditingNarrative) AzulPrimario else TextoSecundario,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onRegenerate, enabled = !uiState.isRegenerating) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Regenerate narrative",
                    tint = TextoSecundario,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        when {
            uiState.isRegenerating -> {
                CardSurface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1f, 0.85f, 0.9f, 0.7f).forEach { ancho ->
                            Spacer(
                                Modifier
                                    .fillMaxWidth(ancho)
                                    .height(12.dp)
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                }
            }
            uiState.isEditingNarrative -> {
                OutlinedTextField(
                    value = uiState.narrativeText,
                    onValueChange = onNarrativeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TextoPrincipal,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Superficie,
                        unfocusedContainerColor = Superficie,
                        focusedBorderColor = AzulPrimario.copy(alpha = 0.4f),
                        unfocusedBorderColor = BordeSutil,
                    ),
                )
            }
            else -> {
                CardSurface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        incident.narrative.forEach { segmento ->
                            Text(
                                text = segmento.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        confidenceColor(segmento.confidence).copy(alpha = 0.08f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(6.dp),
                                color = TextoPrincipal,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuspectsStep(suspects: List<Suspect>, onBackStep: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(Icons.Filled.Person, "Suspects & Persons of Interest")

        if (suspects.isEmpty()) {
            EmptyStepBox(Icons.Filled.Person, "No suspects recorded for this incident")
        } else {
            suspects.forEach { sospechoso ->
                CardSurface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = sospechoso.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ReadOnlyField("Date of Birth", sospechoso.dob)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReadOnlyField("Gender", sospechoso.gender, Modifier.weight(1f))
                            ReadOnlyField("Height", sospechoso.height, Modifier.weight(1f))
                        }
                        Column {
                            FieldLabel("Charges")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                sospechoso.charges.forEach { cargo ->
                                    Text(
                                        text = cargo,
                                        modifier = Modifier
                                            .background(RojoSuave.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                            .border(1.dp, RojoSuave.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = RojoSuave,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        StepNavigation(nextLabel = "Next: Seized Items", onBackStep = onBackStep, onNext = onNext)
    }
}

@Composable
private fun SeizedStep(onBackStep: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(Icons.Filled.Shield, "Seized Property")
        EmptyStepBox(
            icon = Icons.Filled.Shield,
            message = "No seized property recorded",
            actionLabel = "+ Add seized item",
        )
        StepNavigation(nextLabel = "Next: Media", onBackStep = onBackStep, onNext = onNext)
    }
}

@Composable
private fun MediaStep(
    evidence: List<ReportEvidence>,
    isSubmitting: Boolean,
    onBackStep: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle(Icons.Filled.Description, "Audiovisual Evidence")

        evidence.forEach { pieza -> MediaEvidenceCard(pieza) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBackStep, shape = RoundedCornerShape(8.dp)) {
                Text("Back", color = TextoSecundario, fontSize = 13.sp)
            }
            Button(
                onClick = onSubmit,
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = VerdeOk.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("Submit to RMS", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun MediaEvidenceCard(evidence: ReportEvidence) {
    CardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(evidence.source)
                Spacer(Modifier.weight(1f))
                val esEnviada = evidence.status == ReportEvidenceStatus.SUBMITTED
                Text(
                    text = evidence.status.label,
                    modifier = Modifier
                        .background(
                            if (esEnviada) VerdeOk.copy(alpha = 0.1f) else SuperficiePresionada,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (esEnviada) VerdeOk else TextoSecundario,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Column {
                Text(
                    text = evidence.type,
                    color = TextoPrincipal,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(text = evidence.description, color = TextoTerciario, fontSize = 10.sp)
                Text(
                    text = "${evidence.clipStart} – ${evidence.clipEnd}",
                    color = TextoTerciario,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun StepTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = AzulPrimario, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        SectionLabel(title)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(bottom = 6.dp),
        color = TextoTerciario,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun ReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        FieldLabel(label)
        Text(
            text = value,
            modifier = Modifier
                .fillMaxWidth()
                .background(Superficie, RoundedCornerShape(8.dp))
                .border(1.dp, BordeSutil, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            color = TextoPrincipal,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun FieldWithIcon(label: String, icon: ImageVector, value: String) {
    Column {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Superficie, RoundedCornerShape(8.dp))
                .border(1.dp, BordeSutil, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = TextoSecundario, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = value, color = TextoPrincipal, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyStepBox(icon: ImageVector, message: String, actionLabel: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BordeSutil, RoundedCornerShape(12.dp))
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextoTerciario.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(text = message, color = TextoSecundario, fontSize = 14.sp)
        if (actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { }, shape = RoundedCornerShape(8.dp)) {
                Text(actionLabel, color = AzulPrimario, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StepNavigation(nextLabel: String, onNext: () -> Unit, onBackStep: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = if (onBackStep != null) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        if (onBackStep != null) {
            OutlinedButton(onClick = onBackStep, shape = RoundedCornerShape(8.dp)) {
                Text("Back", color = TextoSecundario, fontSize = 13.sp)
            }
        }
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(nextLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Iniciales del nombre para el avatar, por ejemplo "Officer Carlos Mendez" da "OC". */
private fun initialsOf(name: String): String =
    name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
