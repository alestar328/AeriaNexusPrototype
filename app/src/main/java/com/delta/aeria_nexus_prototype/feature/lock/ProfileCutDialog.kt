package com.delta.aeria_nexus_prototype.feature.lock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.identity.CorteDelPerfil
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * Aviso de que AeriaOne ha cortado el acceso del agente a mitad de turno.
 *
 * No bloquea nada: el agente sigue grabando y la evidencia se queda en el telefono.
 * Lo que tiene que saber es por que ha dejado de subir y a quien acudir, y eso no
 * puede quedarse en un log.
 *
 * Con el perfil revocado la baja es definitiva y comprobar otra vez no sirve: en su
 * lugar se ofrece retirar al agente del telefono para que lo use otro (§9.6).
 */
@Composable
fun ProfileCutDialog(
    corte: CorteDelPerfil,
    onCerrar: () -> Unit,
    onComprobar: () -> Unit,
    onRetirarAgente: () -> Unit,
) {
    var confirmandoRetirada by remember { mutableStateOf(false) }
    if (confirmandoRetirada) {
        ConfirmarRetirada(onRetirar = onRetirarAgente, onCancelar = { confirmandoRetirada = false })
        return
    }
    val esBaja = corte == CorteDelPerfil.REVOCADO
    AlertDialog(
        onDismissRequest = onCerrar,
        containerColor = Superficie,
        icon = { Icon(Icons.Filled.Block, contentDescription = null, tint = AmarilloAviso) },
        title = { Titulo(tituloDe(corte)) },
        text = { Cuerpo(explicacionDe(corte) + "\n\n" + queSeHaceConLaEvidencia(corte)) },
        confirmButton = {
            TextButton(onClick = onCerrar) { Text("OK", color = AzulClaro, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            if (esBaja) {
                TextButton(onClick = { confirmandoRetirada = true }) {
                    Text("REMOVE OFFICER", color = RojoSuave, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = onComprobar) { Text("CHECK AGAIN", color = TextoSecundario) }
            }
        },
    )
}

/** Borrar la credencial no tiene vuelta atras: se pregunta una vez, con lo que se pierde y lo que no. */
@Composable
private fun ConfirmarRetirada(onRetirar: () -> Unit, onCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancelar,
        containerColor = Superficie,
        icon = { Icon(Icons.Filled.PersonRemove, contentDescription = null, tint = RojoSuave) },
        title = { Titulo("REMOVE OFFICER?") },
        text = {
            Cuerpo(
                "Your credential and PIN will be deleted from this phone. This cannot be undone.\n\n" +
                    "The phone stays enrolled. Evidence not yet uploaded stays on it and will be " +
                    "uploaded under your name when the next officer signs in.",
            )
        },
        confirmButton = {
            TextButton(onClick = onRetirar) { Text("REMOVE", color = RojoSuave, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) { Text("CANCEL", color = TextoSecundario) }
        },
    )
}

@Composable
private fun Titulo(texto: String) {
    Text(
        text = texto,
        color = TextoPrincipal,
        fontSize = 18.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun Cuerpo(texto: String) {
    Text(text = texto, color = TextoSecundario, fontSize = 15.sp, lineHeight = 22.sp)
}

private fun tituloDe(corte: CorteDelPerfil): String = when (corte) {
    CorteDelPerfil.SUSPENDIDO -> "PROFILE SUSPENDED"
    CorteDelPerfil.REVOCADO -> "PROFILE REVOKED"
    CorteDelPerfil.IDENTIDAD_REVOCADA -> "ACCESS REVOKED"
    CorteDelPerfil.APP_DESFASADA -> "UPDATE REQUIRED"
    CorteDelPerfil.CUARENTENA -> "PHONE QUARANTINED"
    CorteDelPerfil.CERTIFICADO_CADUCADO -> "CREDENTIAL EXPIRED"
    CorteDelPerfil.CERTIFICADO_REEMPLAZADO -> "CREDENTIAL REPLACED"
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
    CorteDelPerfil.CUARENTENA ->
        "AeriaOne has quarantined this phone. Contact your supervisor."
    CorteDelPerfil.CERTIFICADO_CADUCADO ->
        "Your AeriaOne credential has expired. Contact your supervisor to enroll again."
    CorteDelPerfil.CERTIFICADO_REEMPLAZADO ->
        "Your AeriaOne credential was replaced and this phone no longer has the new one. " +
            "Contact your supervisor to enroll again."
}

private fun queSeHaceConLaEvidencia(corte: CorteDelPerfil): String = when {
    // La baja no se deshace: la evidencia sale con el siguiente agente, a nombre de quien la grabo.
    corte == CorteDelPerfil.REVOCADO ->
        "You can keep recording. Evidence stays on this phone and is uploaded under your " +
            "name once another officer is enrolled on it."
    corte == CorteDelPerfil.CUARENTENA ->
        "You can keep recording. Evidence keeps uploading until this session ends; after " +
            "that it stays on this phone."
    corte == CorteDelPerfil.APP_DESFASADA ->
        "You can keep recording and SOS still works. Evidence stays on this phone and is not " +
            "uploaded until the app is updated."
    // Sin sesion no hay SOS por el backend: no se promete lo que no hay.
    corte.conservaElSos ->
        "You can keep recording and SOS still works. Evidence stays on this phone and is not " +
            "uploaded until AeriaOne accepts your profile again."
    else ->
        "You can keep recording. Evidence stays on this phone and is not uploaded until " +
            "AeriaOne accepts it again."
}

@Preview(name = "Perfil suspendido")
@Composable
private fun ProfileCutDialogPreview() {
    AeriaNexusPrototypeTheme {
        ProfileCutDialog(corte = CorteDelPerfil.SUSPENDIDO, onCerrar = {}, onComprobar = {}, onRetirarAgente = {})
    }
}

@Preview(name = "Perfil revocado")
@Composable
private fun ProfileCutDialogRevocadoPreview() {
    AeriaNexusPrototypeTheme {
        ProfileCutDialog(corte = CorteDelPerfil.REVOCADO, onCerrar = {}, onComprobar = {}, onRetirarAgente = {})
    }
}
