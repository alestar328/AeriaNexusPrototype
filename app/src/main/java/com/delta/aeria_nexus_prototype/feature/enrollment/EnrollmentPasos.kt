package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Como se dibuja el progreso del alta: una fila por paso y el panel de lo que
 * falta. Sale de EnrollmentScreen porque lo usan las dos fases, la del terminal
 * y la del agente, y porque la pantalla se paso de las 300 lineas del proyecto.
 */

@Composable
internal fun FilaPaso(paso: PasoAlta) {
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
internal fun MarcaDePaso(estado: PasoEstado) {
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
 * Lo que falta para terminar la fase, dicho tal cual. La CA y el canal con
 * AeriaOne son trabajo pendiente, y esta pantalla no lo disimula.
 */
@Composable
internal fun PanelEspera(uiState: EnrollmentUiState) {
    val esElTerminal = uiState.fase == FaseAlta.TERMINAL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = if (esElTerminal) {
                "WAITING FOR DEVICE CERTIFICATE"
            } else {
                "WAITING FOR OFFICER CERTIFICATE"
            },
            color = AmarilloAviso,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (esElTerminal) {
                "The key pair and the certificate request are ready on this phone. " +
                    "AeriaOne has to sign the request before the device can be used."
            } else {
                // Que sean dos claves distintas es la regla de no equivalencia del
                // modelo, y es lo unico de todo esto que el agente tiene que
                // entender: el telefono responde por si mismo y el agente por si mismo.
                "Your credential uses a second key, separate from the phone's own key " +
                    "and equally locked to this device. The phone is enrolled; you are not yet."
            },
            color = TextoSecundario,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        val identificador = if (esElTerminal) uiState.deviceId else uiState.userId
        identificador?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (esElTerminal) "DEVICE ID" else "OFFICER ID",
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

internal fun colorDe(estado: PasoEstado): Color = when (estado) {
    PasoEstado.HECHO -> VerdeOk
    PasoEstado.AVISO -> AmarilloAviso
    PasoEstado.FALLIDO -> RojoSuave
    PasoEstado.EN_CURSO -> AzulClaro
    PasoEstado.PENDIENTE -> TextoDeshabilitado
}

internal fun descripcionDe(estado: PasoEstado): String = when (estado) {
    PasoEstado.HECHO -> "Done"
    PasoEstado.AVISO -> "Done with a warning"
    PasoEstado.FALLIDO -> "Failed"
    else -> ""
}

