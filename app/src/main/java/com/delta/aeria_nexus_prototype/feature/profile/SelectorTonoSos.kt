package com.delta.aeria_nexus_prototype.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.TonoSos
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeMuySutil
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * Volumen del tono que confirma al agente que su SOS ha salido, en el telefono
 * y en la bodycam enlazada. Tres opciones fijas y a la vista, no un deslizador:
 * se elige una vez por turno y tiene que quedar claro de un vistazo cual esta puesta.
 */
@Composable
fun SelectorTonoSos(tono: TonoSos, onCambiar: (TonoSos) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SOS confirmation tone", color = TextoPrincipal, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Plays on this phone and on the bodycam when your SOS goes out.",
            color = TextoSecundario,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpcionTono("OFF", tono == TonoSos.OFF, { onCambiar(TonoSos.OFF) }, Modifier.weight(1f))
            OpcionTono("DISCREET", tono == TonoSos.LOW, { onCambiar(TonoSos.LOW) }, Modifier.weight(1f))
            OpcionTono("LOUD", tono == TonoSos.HIGH, { onCambiar(TonoSos.HIGH) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OpcionTono(texto: String, elegida: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (elegida) AzulPrimario else Color.Transparent)
            .border(1.dp, if (elegida) AzulPrimario else BordeMuySutil, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = texto,
            color = if (elegida) Color.White else TextoSecundario,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
