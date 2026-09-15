package com.delta.aeria_nexus_prototype.feature.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.audit.IntegridadDelDiario
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

@Composable
fun AuditLogScreen(viewModel: AuditLogViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AuditLogContent(uiState = uiState, onBack = onBack, onRecargar = viewModel::recargar)
}

@Composable
private fun AuditLogContent(uiState: AuditLogUiState, onBack: () -> Unit, onRecargar: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoBase),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextoPrincipal)
            }
            Text(
                text = "AUDIT LOG",
                modifier = Modifier.weight(1f),
                color = TextoPrincipal,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            IconButton(onClick = onRecargar, enabled = !uiState.cargando) {
                Icon(Icons.Filled.Refresh, contentDescription = "Verify again", tint = TextoSecundario)
            }
        }

        when {
            uiState.cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AzulClaro)
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { uiState.integridad?.let { EstadoDeLaCadena(it) } }
                if (uiState.filas.isEmpty()) {
                    item {
                        Text("No events recorded yet.", color = TextoTerciario, fontSize = 14.sp)
                    }
                }
                items(uiState.filas, key = { it.orden }) { fila -> FilaEvento(fila) }
                item {
                    Text(
                        text = "Events are chained with a key that never leaves this phone: changing, " +
                            "removing or reordering any of them breaks the chain. Cutting the end of " +
                            "the log can only be detected by the server once the log is uploaded.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = TextoTerciario,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

/** Color, icono y texto: el estado de la cadena no se comunica solo con color. */
@Composable
private fun EstadoDeLaCadena(integridad: IntegridadDelDiario) {
    val intacta = integridad.intacta
    val color = if (intacta) VerdeOk else RojoSuave
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (intacta) Icons.Filled.VerifiedUser else Icons.Filled.GppBad,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = if (intacta) "CHAIN INTACT" else "CHAIN BROKEN AT EVENT #${integridad.rotaEn}",
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (intacta) {
                    "${integridad.eventos} events verified"
                } else {
                    "${integridad.motivo}. ${integridad.eventos} events before it are valid."
                },
                color = TextoSecundario,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun FilaEvento(fila: FilaDeAuditoria) {
    CardSurface(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${fila.orden}",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = fila.titulo,
                    modifier = Modifier.weight(1f),
                    color = TextoPrincipal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = fila.hora, color = TextoTerciario, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            if (fila.detalle.isNotEmpty()) {
                Text(
                    text = fila.detalle,
                    modifier = Modifier.padding(top = 4.dp),
                    color = TextoSecundario,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = fila.familia + (fila.sesion?.let { "  ·  session $it" } ?: ""),
                modifier = Modifier.padding(top = 4.dp),
                color = AzulClaro,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Preview(name = "Diario intacto", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 700)
@Composable
private fun AuditLogPreview() {
    AeriaNexusPrototypeTheme {
        AuditLogContent(
            uiState = AuditLogUiState(
                cargando = false,
                integridad = IntegridadDelDiario(eventos = 3),
                filas = listOf(
                    FilaDeAuditoria(3, "15/09 14:25:35", "Evidence opened", "EVIDENCE", "evidence: IMG_1.jpg.fev  ·  result: SHOWN", "a1b2c3d4"),
                    FilaDeAuditoria(2, "15/09 14:25:20", "Session opened", "SESSION", "origin: LOCAL_ONLY", "a1b2c3d4"),
                    FilaDeAuditoria(1, "15/09 14:23:55", "Wrong PIN", "SESSION", "via: unlock  ·  attempts_left: 4", null),
                ),
            ),
            onBack = {},
            onRecargar = {},
        )
    }
}

@Preview(name = "Diario roto", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 300)
@Composable
private fun AuditLogBrokenPreview() {
    AeriaNexusPrototypeTheme {
        EstadoDeLaCadena(IntegridadDelDiario(eventos = 11, rotaEn = 12, motivo = "content was modified"))
    }
}
