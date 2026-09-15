package com.delta.aeria_nexus_prototype.feature.pinchange

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Cambio de PIN (workflow 5). Mismo teclado y mismos puntos que el alta y el
 * desbloqueo: un PIN se elige como se va a teclear.
 */
@Composable
fun ChangePinScreen(viewModel: ChangePinViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChangePinContent(
        uiState = uiState,
        onDigito = viewModel::escribirDigito,
        onBorrar = viewModel::borrarDigito,
        onBack = onBack,
    )
}

@Composable
private fun ChangePinContent(
    uiState: ChangePinUiState,
    onDigito: (Char) -> Unit,
    onBorrar: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoBase)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uiState.paso == PasoCambioPin.HECHO) {
            PinCambiado(onBack)
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "STEP ${uiState.paso.ordinal + 1} OF 3",
                color = TextoTerciario,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (uiState.paso) {
                    PasoCambioPin.ACTUAL -> "ENTER CURRENT PIN"
                    PasoCambioPin.NUEVO -> "CHOOSE NEW PIN"
                    else -> "CONFIRM NEW PIN"
                },
                color = TextoPrincipal,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (uiState.paso) {
                    PasoCambioPin.ACTUAL ->
                        "Wrong attempts count toward the same limit as unlocking the app."
                    PasoCambioPin.NUEVO -> "Six digits. It stays on this phone."
                    else -> "Enter the new PIN once more."
                },
                color = TextoSecundario,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }

        PinDots(
            longitud = uiState.digitos.length,
            enFallo = uiState.error != null,
            confirmado = uiState.paso == PasoCambioPin.CONFIRMAR,
        )
        // Altura fija: el teclado no salta cuando aparece o desaparece el mensaje.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            uiState.error?.let {
                Text(
                    text = it,
                    color = RojoSuave,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        TecladoPin(habilitado = !uiState.trabajando, onDigito = onDigito, onBorrar = onBorrar)
    }
}

@Composable
private fun PinCambiado(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = VerdeOk,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "PIN CHANGED",
            color = TextoPrincipal,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Use the new PIN from the next unlock.",
            color = TextoSecundario,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(AzulPrimario, RoundedCornerShape(16.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "DONE", color = TextoPrincipal, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Preview(name = "PIN actual con error", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun ChangePinErrorPreview() {
    AeriaNexusPrototypeTheme {
        ChangePinContent(
            uiState = ChangePinUiState(error = "Wrong PIN. 4 attempts left."),
            onDigito = {},
            onBorrar = {},
            onBack = {},
        )
    }
}

@Preview(name = "PIN cambiado", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun ChangePinDonePreview() {
    AeriaNexusPrototypeTheme {
        ChangePinContent(
            uiState = ChangePinUiState(paso = PasoCambioPin.HECHO),
            onDigito = {},
            onBorrar = {},
            onBack = {},
        )
    }
}
