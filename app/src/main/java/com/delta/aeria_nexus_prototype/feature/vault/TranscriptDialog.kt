package com.delta.aeria_nexus_prototype.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.delta.aeria_nexus_prototype.data.transcript.MotorWhisper
import com.delta.aeria_nexus_prototype.data.transcript.TranscripcionJson
import com.delta.aeria_nexus_prototype.feature.activeincident.ActiveIncidentViewModel.Companion.formatSeconds
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Lectura de una transcripcion. Lo primero que se ve es de que aparato viene:
 * en la boveda conviven las del telefono, las de las gafas y las de la bodycam,
 * y una transcripcion automatica puede equivocarse, sobre todo la de la bodycam.
 */
@Composable
fun TranscriptDialog(transcripcion: TranscripcionJson.Leida, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CardSurface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Cabecera(transcripcion)
                Cuerpo(transcripcion, Modifier.weight(1f, fill = false))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text("CLOSE", color = AzulClaro, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Cabecera(transcripcion: TranscripcionJson.Leida) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EtiquetaDeOrigen(transcripcion.grabadaPor)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "TRANSCRIPT",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }
        Text(
            text = listOfNotNull(
                transcripcion.incidentId,
                transcripcion.grabadaEn?.replace('T', ' ')?.removeSuffix("Z")?.plus(" UTC"),
                transcripcion.idioma?.uppercase(),
            ).joinToString("  ·  "),
            color = TextoTerciario,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun EtiquetaDeOrigen(grabadaPor: String) {
    val (texto, color) = when (grabadaPor) {
        "bodycam" -> "BODYCAM" to AmarilloAviso
        "falconone" -> "FALCONONE" to VerdeOk
        else -> "PHONE" to AzulClaro
    }
    Text(
        text = texto,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Cuerpo(transcripcion: TranscripcionJson.Leida, modifier: Modifier) {
    when {
        transcripcion.estado == TranscripcionJson.Estado.FAILED.valor -> Text(
            text = "Transcription failed: ${transcripcion.error ?: "unknown reason"}",
            color = RojoSuave,
            fontSize = 14.sp,
        )
        transcripcion.segmentos.isEmpty() -> Text(
            text = "No speech detected in this recording.",
            color = TextoSecundario,
            fontSize = 14.sp,
        )
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(transcripcion.segmentos) { segmento -> Segmento(segmento) }
        }
    }
}

@Composable
private fun Segmento(segmento: MotorWhisper.Segmento) {
    Row {
        Text(
            text = formatSeconds(segmento.inicio.toInt()),
            color = TextoTerciario,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp),
        )
        Text(text = segmento.texto, color = TextoPrincipal, fontSize = 15.sp, lineHeight = 21.sp)
    }
}
