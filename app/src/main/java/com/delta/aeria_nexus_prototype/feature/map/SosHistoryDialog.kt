package com.delta.aeria_nexus_prototype.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Lista de todos los SOS registrados, mas nuevo primero. Solo lectura: desde la app
 * no se puede borrar ninguno, por eso no hay ni boton ni gesto para hacerlo.
 */
@Composable
fun SosHistoryDialog(
    items: List<SosHistoryItem>,
    total: Int,
    fromServer: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = RojoCritico) },
        title = { Text("SOS history ($total)", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OrigenDeLaLista(fromServer = fromServer, loading = loading)
                if (items.isEmpty()) {
                    Text("No SOS recorded", color = TextoSecundario, fontSize = 14.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items, key = { it.key }) { FilaSos(it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("CLOSE")
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("REFRESH")
            }
        },
    )
}

@Composable
private fun OrigenDeLaLista(fromServer: Boolean, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (fromServer) "From AeriaOne" else "Server unreachable — this phone's record only",
            color = TextoTerciario,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FilaSos(item: SosHistoryItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Agent ${item.officer.ifBlank { "unknown" }}",
                color = TextoPrincipal,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(text = item.sourceLabel, color = TextoSecundario, fontSize = 12.sp)
        }
        Text(
            text = "${item.dateLabel} · ${item.startLabel}",
            color = TextoSecundario,
            fontSize = 13.sp,
        )
        // El estado lleva texto ademas de color, para leerlo con sol directo.
        Text(
            text = item.endLabel,
            color = if (item.isActive) RojoCritico else TextoSecundario,
            fontSize = 13.sp,
            fontWeight = if (item.isActive) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
