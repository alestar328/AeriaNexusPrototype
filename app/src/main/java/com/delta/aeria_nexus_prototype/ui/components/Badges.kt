package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.model.ConfidenceLevel
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.Priority
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.ui.theme.AmbarRevision
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.CianFalconCore
import com.delta.aeria_nexus_prototype.ui.theme.NaranjaPendiente
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Etiqueta pequena tipo pastilla. Base de todos los badges de la app:
 * el color transmite estado pero siempre acompanado del texto.
 */
@Composable
fun TextBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    showDot: Boolean = false,
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.20f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Box(Modifier.width(4.dp))
        }
        Text(
            text = text.uppercase(),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Color asociado a cada estado de incidente. */
fun statusColor(status: IncidentStatus): Color = when (status) {
    IncidentStatus.ACTIVE -> VerdeOk
    IncidentStatus.DRAFT -> AzulClaro
    IncidentStatus.NEEDS_REVIEW -> AmbarRevision
    IncidentStatus.SUBMITTED -> TextoSecundario
    IncidentStatus.COMPLETED -> TextoTerciario
}

/** Color asociado a cada estado de sincronizacion. */
fun syncColor(sync: SyncState): Color = when (sync) {
    SyncState.LOCAL_ONLY -> AzulClaro
    SyncState.PENDING_SYNC -> NaranjaPendiente
    SyncState.SYNCING -> AmarilloAviso
    SyncState.VERIFIED -> VerdeOk
    SyncState.FAILED -> RojoSuave
}

/** Color asociado a cada prioridad, usado en bordes y etiquetas. */
fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.LOW -> TextoDeshabilitadoPrioridad
    Priority.MEDIUM -> AmarilloAviso
    Priority.HIGH -> RojoSuave
    Priority.CRITICAL -> RojoSuave
}

// La prioridad baja usa un gris neutro propio para no competir visualmente.
private val TextoDeshabilitadoPrioridad = Color(0xFF334155)

/** Color asociado al nivel de confianza de la IA. */
fun confidenceColor(confidence: ConfidenceLevel): Color = when (confidence) {
    ConfidenceLevel.HIGH -> VerdeOk
    ConfidenceLevel.MEDIUM -> AmarilloAviso
    ConfidenceLevel.LOW -> RojoSuave
}

@Composable
fun StatusBadge(status: IncidentStatus, modifier: Modifier = Modifier) {
    TextBadge(text = status.label, color = statusColor(status), modifier = modifier, showDot = true)
}

@Composable
fun SyncBadge(sync: SyncState, modifier: Modifier = Modifier) {
    TextBadge(text = sync.label, color = syncColor(sync), modifier = modifier)
}

@Composable
fun PriorityBadge(priority: Priority, modifier: Modifier = Modifier) {
    TextBadge(text = priority.label, color = priorityColor(priority), modifier = modifier)
}

/** Insignia del dispositivo de captura: Falcon Lens azul, Falcon Core cian. */
@Composable
fun SourceBadge(source: EvidenceSource, modifier: Modifier = Modifier) {
    val color = if (source == EvidenceSource.FALCON_LENS) AzulClaro else CianFalconCore
    TextBadge(text = source.label, color = color, modifier = modifier, showDot = true)
}

/** Insignia corta de dispositivo (FC, FL o AN) usada en tarjetas de video. */
@Composable
fun DeviceBadge(device: String, modifier: Modifier = Modifier) {
    val color = when (device) {
        "FC" -> AzulClaro
        "FL" -> CianFalconCore
        else -> TextoSecundario
    }
    TextBadge(text = device, color = color, modifier = modifier)
}
