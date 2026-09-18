package com.delta.aeria_nexus_prototype.feature.enrollment

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Como se dibuja el progreso del alta: una fila por paso. Sale de EnrollmentScreen
 * porque lo usan las dos fases, la del terminal y la del agente, y porque la
 * pantalla se paso de las 300 lineas del proyecto.
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

