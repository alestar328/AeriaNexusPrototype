package com.delta.aeria_nexus_prototype.feature.livestream

import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla de livestream del SOS, a pantalla completa sobre fondo negro.
 * Emisor: previsualiza su propia camara mientras se publica al canal, con el
 * boton para cancelar el SOS. Receptor: ve en vivo la camara del emisor y un
 * aviso de "Signal cut" si este corta la senal.
 */
@Composable
fun LivestreamScreen(
    viewModel: LivestreamViewModel,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Si el emisor cancela su SOS desde otra pantalla, esta ya no tiene sentido.
    LaunchedEffect(uiState.sosActive) {
        if (viewModel.isBroadcaster && !uiState.sosActive) onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { contexto ->
                TextureView(contexto).also { viewModel.attachVideo(it) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        TopBar(isBroadcaster = viewModel.isBroadcaster, onClose = onClose)

        if (viewModel.isBroadcaster) {
            CancelSosButton(
                onClick = viewModel::cancelSos,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            )
        }

        if (uiState.signalCutAtMillis != null) {
            SignalCutNotice(
                cutAtMillis = uiState.signalCutAtMillis ?: 0L,
                latitude = uiState.signalCutLatitude,
                longitude = uiState.signalCutLongitude,
                onClose = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TopBar(isBroadcaster: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        LiveBadge(text = if (isBroadcaster) "SOS BROADCASTING" else "LIVE — EMERGENCY")
    }
}

/** Distintivo rojo parpadeante: deja claro que la transmision esta en vivo. */
@Composable
private fun LiveBadge(text: String) {
    val parpadeo by rememberInfiniteTransition(label = "liveParpadeo").animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveParpadeo",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(parpadeo)
                .background(RojoCritico, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun CancelSosButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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

@Composable
private fun SignalCutNotice(
    cutAtMillis: Long,
    latitude: Double?,
    longitude: Double?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hora = Instant.ofEpochMilli(cutAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val posicion = if (latitude != null && longitude != null) {
        "%.5f, %.5f".format(latitude, longitude)
    } else {
        "unknown"
    }

    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SIGNAL CUT",
            color = RojoCritico,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The agent cut the signal at $hora\nLast location: $posicion",
            color = TextoSecundario,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClose)
                .padding(horizontal = 28.dp, vertical = 10.dp),
        ) {
            Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
