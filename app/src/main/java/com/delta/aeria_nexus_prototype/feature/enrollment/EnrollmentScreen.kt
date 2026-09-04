package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

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
            if (uiState.iniciada || uiState.esperandoCertificado) {
                ListaDePasos(uiState)
            } else {
                Presentacion(mensajeError = uiState.mensajeError)
            }
        }

        if (!uiState.iniciada && !uiState.esperandoCertificado) {
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
                    text = "ENROLL THIS PHONE",
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
            text = if (uiState.esperandoCertificado) "ENROLLMENT SUBMITTED" else "ENROLLING DEVICE",
            color = TextoPrincipal,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(16.dp))

        uiState.pasos.forEach { paso -> FilaPaso(paso) }

        if (uiState.esperandoCertificado) {
            Spacer(Modifier.height(24.dp))
            PanelEspera(deviceId = uiState.deviceId)
        }
    }
}

@Composable
private fun FilaPaso(paso: PasoAlta) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        MarcaDePaso(paso.estado)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = paso.titulo,
                color = if (paso.estado == PasoEstado.PENDIENTE) TextoDeshabilitado else TextoPrincipal,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            paso.detalle?.let { detalle ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detalle,
                    color = colorDe(paso.estado),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/** El estado nunca va solo en color: cada uno tiene su forma. */
@Composable
private fun MarcaDePaso(estado: PasoEstado) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (estado) {
            PasoEstado.EN_CURSO -> CircularProgressIndicator(
                color = AzulClaro,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )

            PasoEstado.PENDIENTE -> Box(
                Modifier
                    .size(14.dp)
                    .border(1.5.dp, TextoDeshabilitado, CircleShape),
            )

            else -> Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(colorDe(estado), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (estado) {
                        PasoEstado.AVISO -> Icons.Filled.PriorityHigh
                        PasoEstado.FALLIDO -> Icons.Filled.Close
                        else -> Icons.Filled.Check
                    },
                    contentDescription = descripcionDe(estado),
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Lo que falta para terminar el alta, dicho tal cual. La CA y el canal con
 * AeriaOne son trabajo pendiente, y esta pantalla no lo disimula.
 */
@Composable
private fun PanelEspera(deviceId: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "WAITING FOR DEVICE CERTIFICATE",
            color = AmarilloAviso,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The key pair and the certificate request are ready on this phone. " +
                "AeriaOne has to sign the request before the device can be used.",
            color = TextoSecundario,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        deviceId?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "DEVICE ID",
                color = TextoTerciario,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun colorDe(estado: PasoEstado): Color = when (estado) {
    PasoEstado.HECHO -> VerdeOk
    PasoEstado.AVISO -> AmarilloAviso
    PasoEstado.FALLIDO -> RojoSuave
    PasoEstado.EN_CURSO -> AzulClaro
    PasoEstado.PENDIENTE -> TextoDeshabilitado
}

private fun descripcionDe(estado: PasoEstado): String = when (estado) {
    PasoEstado.HECHO -> "Done"
    PasoEstado.AVISO -> "Done with a warning"
    PasoEstado.FALLIDO -> "Failed"
    else -> ""
}

@Preview(name = "Sin dar de alta", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun EnrollmentInicioPreview() {
    AeriaNexusPrototypeTheme {
        EnrollmentContent(uiState = EnrollmentUiState(), onIniciar = {})
    }
}

@Preview(name = "Alta terminada", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 900)
@Composable
private fun EnrollmentEsperaPreview() {
    AeriaNexusPrototypeTheme {
        EnrollmentContent(
            uiState = EnrollmentUiState(
                iniciada = true,
                esperandoCertificado = true,
                deviceId = "DEV-92A71C",
                pasos = listOf(
                    PasoAlta("Device information", PasoEstado.HECHO, "Xiaomi Redmi Note 8 Pro · Android 11"),
                    PasoAlta("Security posture", PasoEstado.HECHO, "Keystore available · no tampering indicators found"),
                    PasoAlta("Device identity", PasoEstado.HECHO, "DEV-92A71C"),
                    PasoAlta("Key pair in secure hardware", PasoEstado.HECHO, "Trusted execution environment · attestation chain of 4"),
                    PasoAlta("Certificate request", PasoEstado.HECHO, "PKCS#10 · ECDSA P-256 · SHA-256"),
                    PasoAlta("Submit to AeriaOne", PasoEstado.AVISO, "Held on device — no AeriaOne backend yet"),
                ),
            ),
            onIniciar = {},
        )
    }
}
