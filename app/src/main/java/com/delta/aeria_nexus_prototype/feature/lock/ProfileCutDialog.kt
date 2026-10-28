package com.delta.aeria_nexus_prototype.feature.lock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.identity.CorteDelPerfil
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * Aviso de que AeriaOne ha cortado el acceso del agente a mitad de turno.
 *
 * No bloquea nada: el agente sigue grabando y la evidencia se queda en el telefono.
 * Lo que tiene que saber es por que ha dejado de subir y a quien acudir, y eso no
 * puede quedarse en un log.
 */
@Composable
fun ProfileCutDialog(corte: CorteDelPerfil, onCerrar: () -> Unit, onComprobar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = Superficie,
        icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = AmarilloAviso) },
        title = {
            Text(
                text = tituloDe(corte),
                color = TextoPrincipal,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        },
        text = {
            Text(
                text = explicacionDe(corte) + "\n\n" +
                    "You can keep recording and SOS still works. Evidence stays on this phone " +
                    "and is not uploaded until " +
                    if (corte == CorteDelPerfil.APP_DESFASADA) "the app is updated." else "AeriaOne accepts your profile again.",
                color = TextoSecundario,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onCerrar) { Text("OK", color = AzulClaro, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onComprobar) { Text("CHECK AGAIN", color = TextoSecundario) }
        },
    )
}

private fun tituloDe(corte: CorteDelPerfil): String = when (corte) {
    CorteDelPerfil.SUSPENDIDO -> "PROFILE SUSPENDED"
    CorteDelPerfil.REVOCADO -> "PROFILE REVOKED"
    CorteDelPerfil.IDENTIDAD_REVOCADA -> "ACCESS REVOKED"
    CorteDelPerfil.APP_DESFASADA -> "UPDATE REQUIRED"
}

private fun explicacionDe(corte: CorteDelPerfil): String = when (corte) {
    CorteDelPerfil.SUSPENDIDO ->
        "An administrator has suspended your AeriaOne profile. Contact your administrator."
    CorteDelPerfil.REVOCADO ->
        "Your AeriaOne profile has been revoked. Contact your administrator."
    CorteDelPerfil.IDENTIDAD_REVOCADA ->
        "AeriaOne has revoked this phone or its credentials. Contact your administrator."
    CorteDelPerfil.APP_DESFASADA ->
        "This version of the app is no longer accepted by AeriaOne. Install the latest version."
}

@Preview(name = "Perfil suspendido")
@Composable
private fun ProfileCutDialogPreview() {
    AeriaNexusPrototypeTheme {
        ProfileCutDialog(corte = CorteDelPerfil.SUSPENDIDO, onCerrar = {}, onComprobar = {})
    }
}
