package com.delta.aeria_nexus_prototype.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico

/**
 * Aviso al tocar el marcador de un agente en SOS: "SOS active - View livestream".
 *
 * Si el agente emite a la vez desde su telefono y desde su bodycam se ofrecen
 * los dos directos, porque enfocan cosas distintas (lo que el agente apunta con
 * el telefono y lo que tiene delante del pecho) y no hay forma de saber cual le
 * sirve mas al companero que acude.
 */
@Composable
fun SosLivestreamDialog(
    agente: RemoteAgentMarker,
    onOpenLivestream: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ambos = agente.sosTelefonoUid != null && agente.sosBodycamUid != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = RojoCritico) },
        title = { Text("SOS active", color = RojoCritico, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                agente.sosTelefonoUid?.let { uid ->
                    BotonDirecto(
                        texto = if (ambos) "VIEW PHONE LIVESTREAM" else "VIEW LIVESTREAM",
                        onClick = { onOpenLivestream(uid) },
                    )
                }
                agente.sosBodycamUid?.let { uid ->
                    BotonDirecto(
                        texto = if (ambos) "VIEW BODYCAM LIVESTREAM" else "VIEW LIVESTREAM",
                        onClick = { onOpenLivestream(uid) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("CLOSE")
            }
        },
    )
}

@Composable
private fun BotonDirecto(texto: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = RojoCritico, contentColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Text(texto, fontWeight = FontWeight.Bold)
    }
}
