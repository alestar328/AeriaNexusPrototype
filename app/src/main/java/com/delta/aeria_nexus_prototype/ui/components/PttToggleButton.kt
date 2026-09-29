package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AmbarRevision
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * PTT del propio telefono: una pulsacion abre el canal y otra lo cierra. Lo
 * usan Operations y el directo del SOS, para que el gesto sea el mismo en los dos.
 *
 * Es un conmutador desde el 2026-09-15, como el de la bodycam: el agente no
 * tiene que tener la mano en la pantalla mientras habla. El precio es el fallo
 * clasico de la radio, dejarse el microfono abierto, y por eso el boton late
 * mientras transmite y los tonos de abrir y cerrar son distintos.
 *
 * El indicador se enciende con lo que el repositorio confirma haber abierto, no
 * con la pulsacion: un boton que dice ON AIR sin que salga voz es peor que uno
 * que no responde, porque el agente cree que le estan oyendo.
 *
 * [pisadoPor] no es null cuando otro agente habla a la vez: el boton pasa a ambar,
 * con icono de aviso y el nombre de quien pisa. Acompana al triple pitido de
 * PttTones.pisando(); el color solo no bastaria con sol en la pantalla.
 */
@Composable
fun PttToggleButton(
    activo: Boolean,
    pisadoPor: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // En Operations el boton va sobre el panel; en el directo, sobre el video.
    fondoEnReposo: Color = AzulOscuroPanel,
) {
    val pisado = activo && pisadoPor != null
    val colorAcento = when {
        pisado -> AmbarRevision
        activo -> AzulClaro
        else -> AzulPrimario.copy(alpha = 0.2f)
    }
    val colorFondo = when {
        pisado -> AmbarRevision.copy(alpha = 0.18f)
        activo -> AzulPrimario.copy(alpha = 0.35f)
        else -> fondoEnReposo
    }

    // Late mientras se transmite, como el SOS: tiene que verse de reojo.
    val latido by rememberInfiniteTransition(label = "pttLatido").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pttLatido",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorFondo)
            .border(if (pisado) 2.dp else 1.dp, colorAcento, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (pisado) Icons.Filled.Warning else Icons.Filled.Mic,
                contentDescription = if (pisado) "Another officer is talking" else "Push to talk",
                tint = if (pisado) AmbarRevision else AzulClaro,
                modifier = Modifier
                    .size(26.dp)
                    .alpha(if (activo) latido else 1f),
            )
            Column {
                Text(
                    text = if (activo) "ON AIR — TAP TO STOP" else "PTT — TAP TO TALK",
                    color = if (activo) colorAcento else TextoSecundario,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                if (pisado) {
                    Text(
                        text = "$pisadoPor ALSO ON AIR",
                        color = AmbarRevision,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

