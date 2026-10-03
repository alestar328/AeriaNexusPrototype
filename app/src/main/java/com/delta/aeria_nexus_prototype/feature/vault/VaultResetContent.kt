package com.delta.aeria_nexus_prototype.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.PinDots
import com.delta.aeria_nexus_prototype.ui.components.TecladoPin
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * Reinicio de la boveda cuando se olvida la contrasena. Dos pasos: el aviso de lo
 * que se pierde y el PIN del agente, que es quien lo autoriza. La contrasena nueva
 * se crea despues con el formulario de siempre.
 */
@Composable
internal fun VaultResetContent(
    paso: PasoReinicio,
    digitos: String,
    trabajando: Boolean,
    mensajeError: String?,
    onContinuar: () -> Unit,
    onCancelar: () -> Unit,
    onDigito: (Char) -> Unit,
    onBorrar: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (paso) {
            PasoReinicio.AVISO -> AvisoDeReinicio(onContinuar = onContinuar)
            PasoReinicio.PIN -> PinDeReinicio(
                digitos = digitos,
                trabajando = trabajando,
                mensajeError = mensajeError,
                onDigito = onDigito,
                onBorrar = onBorrar,
            )
        }
        Text(
            text = "CANCEL",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = !trabajando, onClick = onCancelar)
                .padding(vertical = 14.dp),
            color = TextoSecundario,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AvisoDeReinicio(onContinuar: () -> Unit) {
    CardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = AmarilloAviso,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "RESET VAULT",
                    color = TextoPrincipal,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                text = "A forgotten password cannot be recovered. Resetting lets you " +
                    "create a new one, but the evidence already stored on this phone " +
                    "can no longer be opened here.",
                color = TextoSecundario,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Text(
                text = "Nothing is deleted: the copy sent to Nexus is not affected, and " +
                    "evidence still waiting to upload will upload as usual.",
                color = TextoSecundario,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Button(
                onClick = onContinuar,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RojoCritico),
            ) {
                Text(
                    text = "RESET WITH MY PIN",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun PinDeReinicio(
    digitos: String,
    trabajando: Boolean,
    mensajeError: String?,
    onDigito: (Char) -> Unit,
    onBorrar: () -> Unit,
) {
    Text(
        text = "ENTER YOUR PIN",
        color = TextoPrincipal,
        fontSize = 20.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
    )
    Text(
        text = "Wrong attempts count toward the same limit as unlocking the app.",
        color = TextoSecundario,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
    )
    PinDots(longitud = digitos.length, enFallo = mensajeError != null)
    // Altura minima fija: el teclado no salta cuando aparece o desaparece el mensaje.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (mensajeError != null) {
            Text(
                text = mensajeError,
                color = RojoSuave,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
    TecladoPin(habilitado = !trabajando, onDigito = onDigito, onBorrar = onBorrar)
}

@Preview(name = "Aviso de reinicio", showBackground = true, backgroundColor = 0xFF080B12)
@Composable
private fun VaultResetAvisoPreview() {
    AeriaNexusPrototypeTheme {
        VaultResetContent(
            paso = PasoReinicio.AVISO,
            digitos = "",
            trabajando = false,
            mensajeError = null,
            onContinuar = {},
            onCancelar = {},
            onDigito = {},
            onBorrar = {},
        )
    }
}

@Preview(name = "PIN de reinicio con error", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 700)
@Composable
private fun VaultResetPinPreview() {
    AeriaNexusPrototypeTheme {
        VaultResetContent(
            paso = PasoReinicio.PIN,
            digitos = "",
            trabajando = false,
            mensajeError = "Wrong PIN. 4 attempts left.",
            onContinuar = {},
            onCancelar = {},
            onDigito = {},
            onBorrar = {},
        )
    }
}
