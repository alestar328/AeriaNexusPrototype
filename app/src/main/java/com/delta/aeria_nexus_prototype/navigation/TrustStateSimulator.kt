package com.delta.aeria_nexus_prototype.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.data.identity.TrustStatus
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Selector de estado de confianza para compilaciones debug.
 *
 * Sin backend no hay forma de que el telefono llegue por si solo a "instancia
 * revocada" o a "sesion caducada", y son justo las pantallas que hay que poder
 * ensenar. Esto las hace alcanzables en dos toques.
 *
 * Solo se monta si BuildConfig.DEBUG (ver [TrustGate]), asi que no llega al APK
 * de release. Aun asi cambia estado real, no una vista falsa: lo que se ve es
 * exactamente lo que vera el agente.
 */
@Composable
fun TrustStateSimulator(
    status: TrustStatus,
    onSeleccionar: (TrustState, TrustBlockReason?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var abierto by remember { mutableStateOf(false) }

    // Pestana estrecha en el borde: se ve, se alcanza y no tapa ningun control.
    Box(
        modifier = modifier
            .padding(start = 2.dp)
            .size(width = 20.dp, height = 64.dp)
            .background(AzulPrimario.copy(alpha = 0.35f), RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
            .clickable { abierto = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = "Simulador de estado de confianza",
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }

    if (abierto) {
        AlertDialog(
            onDismissRequest = { abierto = false },
            containerColor = Superficie,
            title = {
                Column {
                    Text(
                        text = "TRUST STATE",
                        color = TextoPrincipal,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        text = "debug only",
                        color = TextoTerciario,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Ahora: " + descripcionActual(status),
                        color = AzulClaro,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))

                    TrustState.entries.filter { it != TrustState.BLOCKED }.forEach { estado ->
                        FilaEstado(texto = estado.name, color = TextoPrincipal) {
                            onSeleccionar(estado, null)
                            abierto = false
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "BLOCKED — interruptores de corte (§13)",
                        color = TextoTerciario,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    TrustBlockReason.entries.forEach { motivo ->
                        FilaEstado(texto = motivo.name, color = RojoSuave) {
                            onSeleccionar(TrustState.BLOCKED, motivo)
                            abierto = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { abierto = false }) {
                    Text("CLOSE", color = TextoSecundario)
                }
            },
        )
    }
}

@Composable
private fun FilaEstado(texto: String, color: Color, onClick: () -> Unit) {
    Text(
        text = texto,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun descripcionActual(status: TrustStatus): String =
    status.blockReason?.let { "${status.state.name} / ${it.name}" } ?: status.state.name
