package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Alta del terminal (workflow 12).
 *
 * Instalar la app no da acceso a nada: hace falta que AeriaOne reconozca este
 * telefono. Esta pantalla es todo lo que el agente ve de un proceso de 21 pasos,
 * y esta escrita para que se entienda que se envia y que se queda en el telefono.
 */
@Composable
fun EnrollmentScreen(viewModel: EnrollmentViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EnrollmentContent(uiState = uiState, onIniciar = viewModel::iniciar)
}

@Composable
private fun EnrollmentContent(uiState: EnrollmentUiState, onIniciar: () -> Unit) {
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
            Spacer(Modifier.height(24.dp))
            // Tras un fallo se sigue viendo la lista: el paso en rojo dice que fallo
            // y por que, y eso es lo que hay que dictar por radio a soporte.
            val hayProgreso = uiState.pasos.any { it.estado != PasoEstado.PENDIENTE }
            if (uiState.iniciada || hayProgreso) {
                ListaDePasos(uiState)
            } else {
                Presentacion(mensajeError = uiState.mensajeError)
            }
        }

        if (!uiState.iniciada) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onIniciar,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            ) {
                Text(
                    text = if (uiState.mensajeError != null) "TRY AGAIN" else "ENROLL THIS PHONE",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun Presentacion(mensajeError: String?) {
    Icon(
        Icons.Filled.PhonelinkSetup,
        contentDescription = null,
        tint = AmarilloAviso,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = "DEVICE NOT ENROLLED",
        color = TextoPrincipal,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "This phone has not been enrolled for duty use. Installing Aeria Nexus " +
            "does not by itself grant access to AeriaOne.",
        color = TextoSecundario,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        // El telefono es del agente. Decirlo antes de empezar, no en una politica
        // que nadie lee: es el "privacy boundary" del paso 19 del workflow.
        text = "Enrollment reads the phone model, its Android version and its security " +
            "features, and creates a key that never leaves this device. Your personal " +
            "apps, files and messages are outside AeriaOne.",
        color = TextoTerciario,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    if (mensajeError != null) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = mensajeError,
            color = RojoSuave,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ListaDePasos(uiState: EnrollmentUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = tituloDe(uiState),
            color = TextoPrincipal,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(16.dp))

        uiState.pasos.forEach { paso -> FilaPaso(paso) }
    }
}

private fun tituloDe(uiState: EnrollmentUiState): String = when {
    uiState.mensajeError != null -> "ENROLLMENT FAILED"
    uiState.fase == FaseAlta.TERMINAL -> "ENROLLING DEVICE"
    else -> "ENROLLING OFFICER"
}

@Preview(name = "Sin dar de alta", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun EnrollmentInicioPreview() {
    AeriaNexusPrototypeTheme {
        EnrollmentContent(uiState = EnrollmentUiState(), onIniciar = {})
    }
}

@Preview(name = "Alta del terminal", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 900)
@Composable
private fun EnrollmentTerminalPreview() {
    AeriaNexusPrototypeTheme {
        EnrollmentContent(
            uiState = EnrollmentUiState(
                iniciada = true,
                pasos = listOf(
                    PasoAlta("Device information", PasoEstado.HECHO, "Xiaomi Redmi Note 8 Pro · Android 11"),
                    PasoAlta("Security posture", PasoEstado.HECHO, "Keystore available · no tampering indicators found"),
                    PasoAlta("Key pair in secure hardware", PasoEstado.HECHO, "Trusted execution environment · attestation chain of 4, challenged by AeriaOne-challenge-service"),
                    PasoAlta("Certificate request", PasoEstado.HECHO, "PKCS#10 · ECDSA P-256 · SHA-256"),
                    PasoAlta("Submit to AeriaOne", PasoEstado.EN_CURSO),
                    PasoAlta("Device identity"),
                ),
            ),
            onIniciar = {},
        )
    }
}

@Preview(name = "Alta rechazada", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun EnrollmentFalloPreview() {
    AeriaNexusPrototypeTheme {
        EnrollmentContent(
            uiState = EnrollmentUiState(
                fase = FaseAlta.AGENTE,
                mensajeError = "AeriaOne answered 404: No hay ningun perfil dado de alta",
                pasos = listOf(
                    PasoAlta("Officer identity", PasoEstado.AVISO, "cmendez.aeriaone.com · provided locally, must exist in AeriaOne"),
                    PasoAlta("Officer key pair in secure hardware", PasoEstado.HECHO, "Trusted execution environment · separate from the device key"),
                    PasoAlta("Certificate request", PasoEstado.HECHO, "PKCS#10 · ECDSA P-256 · SHA-256"),
                    PasoAlta("Submit to AeriaOne", PasoEstado.FALLIDO, "AeriaOne answered 404: No hay ningun perfil dado de alta"),
                ),
            ),
            onIniciar = {},
        )
    }
}
