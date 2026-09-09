package com.delta.aeria_nexus_prototype.feature.ptt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

// Azul del sistema, nunca los rojos del SOS: quien vea esto de reojo tiene que
// distinguir al instante una transmision de radio de una emergencia.
private val FondoAviso = Color(0xFF0F2352)
private val BordeAviso = Color(0x333B82F6)

/**
 * Aviso de que un companero esta comunicando por el PTT, sea desde su bodycam
 * (boton F2) o desde su telefono (boton PTT de Operations).
 *
 * Deliberadamente NO es el popup del SOS. El PTT es trafico rutinario y en un
 * turno movido saltaria decenas de veces: si usara una alarma modal con sirena,
 * los agentes se acostumbrarian a descartarla y acabarian descartando tambien el
 * SOS de verdad. Por eso es una banda superior, sin sonido, que no bloquea nada,
 * no se puede descartar a mano y desaparece sola cuando el companero suelta el
 * PTT.
 *
 * El audio NO depende de esta capa: la escucha la abre el repositorio — de forma
 * permanente para la bodycam (AgoraRepository.escucharBodycam) y al recibir el
 * anuncio "ptt_on" para el PTT de un telefono — asi que la voz se oye aunque esta
 * pantalla no llegue a montarse.
 */
@Composable
fun PttAvisoOverlay(viewModel: PttAvisoViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = uiState.hablando,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FondoAviso)
                    .border(1.dp, BordeAviso, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PuntoEnAntena()
                Icon(
                    Icons.Filled.RecordVoiceOver,
                    contentDescription = null,
                    tint = AzulClaro,
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp)
                        .size(20.dp),
                )
                Column {
                    Text(
                        text = uiState.titulo,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Canal abierto — se escucha por el altavoz",
                        color = TextoSecundario,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** Punto que late mientras hay voz en el canal. Es el unico movimiento del aviso. */
@Composable
private fun PuntoEnAntena() {
    val transicion = rememberInfiniteTransition(label = "ptt")
    val opacidad by transicion.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "latido",
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .alpha(opacidad)
            .clip(CircleShape)
            .background(AzulClaro),
    )
}
