package com.delta.aeria_nexus_prototype.feature.livestream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AmbarRevision
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico

/**
 * Controles del emisor del SOS: su microfono (arranca abierto) y la voz de los
 * demas agentes que le contestan. Encima, el boton de cancelar.
 *
 * Los dos conmutadores se ven en ambar cuando algo esta cortado: es un estado
 * que el agente tiene que reconocer de reojo, porque con el micro cerrado nadie
 * le oye pedir ayuda y con los demas silenciados no oye que ya van.
 */
@Composable
fun ControlesDelEmisor(
    microCerrado: Boolean,
    entranteSilenciado: Boolean,
    onAlternarMicro: () -> Unit,
    onAlternarEntrante: () -> Unit,
    onCancelarSos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonConmutador(
                icono = if (microCerrado) Icons.Filled.MicOff else Icons.Filled.Mic,
                texto = if (microCerrado) "MIC OFF" else "MIC ON",
                cortado = microCerrado,
                onClick = onAlternarMicro,
                modifier = Modifier.weight(1f),
            )
            BotonConmutador(
                icono = if (entranteSilenciado) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                // Cortos a proposito: a media anchura un texto mas largo se parte en
                // dos lineas y deja de leerse de un vistazo.
                texto = if (entranteSilenciado) "OTHERS OFF" else "OTHERS ON",
                cortado = entranteSilenciado,
                onClick = onAlternarEntrante,
                modifier = Modifier.weight(1f),
            )
        }
        CancelSosButton(onClick = onCancelarSos)
    }
}

/** Receptor: silenciar en este telefono el directo que esta viendo. */
@Composable
fun BotonSilenciarDirecto(silenciado: Boolean, onClick: () -> Unit) {
    BotonConmutador(
        icono = if (silenciado) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        texto = if (silenciado) "MUTED" else "SOUND ON",
        cortado = silenciado,
        onClick = onClick,
    )
}

@Composable
private fun BotonConmutador(
    icono: ImageVector,
    texto: String,
    cortado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (cortado) AmbarRevision else Color.White
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(if (cortado) 2.dp else 1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(
            text = texto,
            color = color,
            maxLines = 1,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun CancelSosButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RojoCritico)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "CANCEL SOS",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}
