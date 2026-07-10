package com.delta.aeria_nexus_prototype.feature.incidentlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.components.StatusBadge
import com.delta.aeria_nexus_prototype.ui.components.SyncBadge
import com.delta.aeria_nexus_prototype.ui.components.priorityColor
import com.delta.aeria_nexus_prototype.ui.theme.AmbarRevision
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/** Lista de incidentes del agente con busqueda y filtros por estado. */
@Composable
fun IncidentListScreen(
    viewModel: IncidentListViewModel,
    onOpenIncident: (String) -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppScaffold(currentTab = MainTab.INCIDENTS, onTabSelected = onTabSelected) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListHeader(needsReviewCount = uiState.needsReviewCount)
            SearchField(query = uiState.query, onQueryChange = viewModel::onQueryChange)
            Spacer(Modifier.height(12.dp))
            FilterTabs(selected = uiState.filter, onFilterChange = viewModel::onFilterChange)
            Spacer(Modifier.height(16.dp))

            if (uiState.incidents.isEmpty()) {
                EmptyListMessage()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(uiState.incidents, key = { it.id }) { incidente ->
                        IncidentCard(incident = incidente, onClick = { onOpenIncident(incidente.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(needsReviewCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "AERIA NEXUS",
                color = TextoTerciario,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Text(
                text = "INCIDENTS",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
        }
        if (needsReviewCount > 0) {
            Row(
                modifier = Modifier
                    .background(AmbarRevision.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .border(1.dp, AmbarRevision.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = AmbarRevision,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$needsReviewCount need review",
                    color = AmbarRevision,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search incidents...", color = TextoDeshabilitado) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TextoTerciario)
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Superficie,
            unfocusedContainerColor = Superficie,
            focusedBorderColor = AzulPrimario.copy(alpha = 0.5f),
            unfocusedBorderColor = BordeSutil,
        ),
    )
}

@Composable
private fun FilterTabs(selected: StatusFilter, onFilterChange: (StatusFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(12.dp))
            .border(1.dp, BordeSutil, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusFilter.entries.forEach { filtro ->
            val activo = filtro == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (activo) AzulPrimario else Color.Transparent)
                    .clickable { onFilterChange(filtro) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = filtro.label,
                    color = if (activo) Color.White else TextoTerciario,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyListMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = TextoDeshabilitado,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No incidents found",
            color = TextoTerciario,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IncidentCard(incident: OfficerIncident, onClick: () -> Unit) {
    // Borde izquierdo de color segun prioridad, como en el prototipo web.
    // IntrinsicSize.Min permite que la franja lateral llene la altura real de la tarjeta.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .background(Superficie)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { },
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(priorityColor(incident.priority)),
        )
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = incident.id,
                    color = TextoSecundario,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(incident.status)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextoDeshabilitado,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = incident.type,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            IconInfoRow(icon = Icons.Filled.Place, text = incident.location)
            IconInfoRow(
                icon = Icons.Filled.Schedule,
                text = listOfNotNull(incident.date, incident.time, incident.duration).joinToString(" · "),
            )
            IconInfoRow(icon = Icons.Filled.Shield, text = "${incident.officerName} · ${incident.officerNum}")
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountChip("${incident.evidenceCount} Evidence")
                if (incident.witnessCount > 0) {
                    val plural = if (incident.witnessCount > 1) "es" else ""
                    CountChip("${incident.witnessCount} Witness$plural")
                }
                SyncBadge(incident.sync)
            }
        }
    }
}

@Composable
private fun IconInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TextoTerciario, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = TextoSecundario, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CountChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = TextoSecundario,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
