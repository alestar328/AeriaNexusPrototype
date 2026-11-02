package com.delta.aeria_nexus_prototype.feature.credencial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AmbarRevision
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/** Alto de la banda con sus margenes, para que la del PTT se ponga debajo. */
val ALTO_BANDA_GRACIA: Dp = 64.dp

/**
 * La credencial caduco con una grabacion en marcha (workflow 53).
 *
 * El agente tiene que saberlo AHORA y no al parar: cuando pare, el telefono se
 * queda fuera de servicio, y enterarse entonces le pillaria en plena intervencion
 * sin poder volver a grabar. Por eso es una banda fija mientras dure la gracia y
 * no un dialogo: no le quita la camara ni el boton de parar.
 */
@Composable
fun CredencialEnGraciaOverlay(visible: Boolean, margenSuperior: Dp = 0.dp) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = margenSuperior)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .height(ALTO_BANDA_GRACIA - 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A1F05))
                    .border(1.dp, AmbarRevision, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = AmbarRevision,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp),
                )
                Column {
                    Text(
                        text = "CREDENTIAL EXPIRED — KEEP RECORDING",
                        color = AmbarRevision,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "The phone goes out of service when the recording stops",
                        color = TextoSecundario,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
