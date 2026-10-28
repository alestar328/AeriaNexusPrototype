package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.delta.aeria_nexus_prototype.data.AppContainer

/**
 * Avisa de que la base local no se pudo abrir y se aparto. Va encima de todo, haya
 * sesion o no, y no se cierra tocando fuera: el agente tiene que confirmarlo.
 */
@Composable
fun AvisoBaseEnCuarentena() {
    val hayAviso by AppContainer.avisoBaseEnCuarentena.collectAsState()
    if (!hayAviso) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Local incident data unavailable") },
        text = {
            Text(
                "This phone could not open its stored incidents, so the app started empty. " +
                    "The old data was set aside, not deleted. Captured evidence is not affected " +
                    "and will still be uploaded. Tell your supervisor.",
            )
        },
        confirmButton = {
            TextButton(onClick = { AppContainer.confirmarAvisoBaseEnCuarentena(context) }) {
                Text("UNDERSTOOD")
            }
        },
    )
}
