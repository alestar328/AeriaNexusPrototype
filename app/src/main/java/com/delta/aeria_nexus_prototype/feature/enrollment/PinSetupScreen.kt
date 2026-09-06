package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.components.PinDots
import com.delta.aeria_nexus_prototype.ui.components.TecladoPin
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Ultimo paso del alta: el agente elige su PIN (workflow 4, paso 13).
 *
 * Es lo unico de todo el proceso que pone el agente. Todo lo anterior —claves,
 * peticiones, certificados— ocurre sin que tenga nada que decidir; aqui si, y por
 * eso la pantalla explica para que sirve el PIN antes de pedirlo.
 *
 * El teclado va fuera del scroll y la ficha dentro, igual que en la pantalla de
 * bloqueo: en un movil estrecho lo que cede es la explicacion, nunca las teclas.
 */
@Composable
fun PinSetupScreen(viewModel: PinSetupViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PinSetupContent(
        uiState = uiState,
        onDigito = viewModel::escribirDigito,
        onBorrar = viewModel::borrarDigito,
    )
}

@Composable
private fun PinSetupContent(
    uiState: PinSetupUiState,
    onDigito: (Char) -> Unit,
    onBorrar: () -> Unit,
) {
    val confirmando = uiState.confirmando
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoBase)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "AERIA NEXUS",
            color = TextoTerciario,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (confirmando) "CONFIRM YOUR PIN" else "SET YOUR PIN",
                color = TextoPrincipal,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (confirmando) {
                    "Enter it once more. It is asked twice because a PIN cannot be recovered."
                } else {
                    "Your credential is issued. This PIN is what authorizes its use at the " +
                        "start of each duty. It stays on this phone and is never sent to AeriaOne."
                },
                color = TextoSecundario,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            uiState.userId?.let { agente ->
                Spacer(Modifier.height(20.dp))
                FichaDelAgente(agente)
            }
        }

        PinDots(
            longitud = uiState.digitos.length,
            enFallo = uiState.error != null,
            confirmado = confirmando,
        )
        AvisoDelPin(uiState)
        TecladoPin(habilitado = true, onDigito = onDigito, onBorrar = onBorrar)
    }
}

@Composable
private fun FichaDelAgente(agente: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "ENROLLED OFFICER",
            color = TextoTerciario,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = agente,
            color = Color.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Altura fija para que el teclado no salte cuando aparece o desaparece el mensaje. */
@Composable
private fun AvisoDelPin(estado: PinSetupUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val texto = estado.error ?: if (estado.confirmando) "Repeat the same six digits" else null
        if (texto != null) {
            Text(
                text = texto,
                color = if (estado.error != null) RojoSuave else TextoSecundario,
                fontSize = 14.sp,
                fontWeight = if (estado.error != null) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(name = "Crear PIN", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun PinSetupPreview() {
    AeriaNexusPrototypeTheme {
        PinSetupContent(
            uiState = PinSetupUiState(userId = "cmendez.aeriaone.com", digitos = "004"),
            onDigito = {},
            onBorrar = {},
        )
    }
}

@Preview(name = "PIN no coincide", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun PinSetupErrorPreview() {
    AeriaNexusPrototypeTheme {
        PinSetupContent(
            uiState = PinSetupUiState(
                userId = "cmendez.aeriaone.com",
                error = "PINs did not match. Start again.",
            ),
            onDigito = {},
            onBorrar = {},
        )
    }
}
