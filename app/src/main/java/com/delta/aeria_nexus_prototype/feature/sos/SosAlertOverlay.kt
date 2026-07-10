package com.delta.aeria_nexus_prototype.feature.sos

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.R
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Mismos tonos del popup de emergencia de Falcon One.
private val FondoAlerta = Color(0xFF2A0E0E)
private val RojoAlerta = Color(0xFFE53935)

/**
 * Capa global de alertas SOS: se monta una sola vez sobre el NavHost para que
 * la emergencia de otro agente aparezca sin importar la pantalla actual.
 * [onAcceptLivestream] abre el livestream del agente emisor (por su uid).
 */
@Composable
fun SosAlertOverlay(
    viewModel: SosAlertViewModel,
    onAcceptLivestream: (Int) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    uiState.activeAlert?.let { alerta ->
        SirenLoop()
        AlertDialog(
            onDismissRequest = {},
            containerColor = FondoAlerta,
            shape = RoundedCornerShape(18.dp),
            icon = {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = AmarilloAviso,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Text("Emergency", color = Color.White, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Column {
                    Text(
                        text = "Officer ${alerta.officer.ifEmpty { "(unknown)" }} — status: Emergency",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Started: ${formatTime(alerta.startedAtMillis)}",
                        color = TextoSecundario,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Location: ${formatCoords(alerta.latitude, alerta.longitude)}",
                        color = TextoSecundario,
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Cerrar la alerta detiene la sirena; despues se abre el
                        // livestream del emisor para ver lo que esta enfocando.
                        viewModel.dismissAlert()
                        onAcceptLivestream(alerta.uid)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RojoAlerta),
                ) {
                    Text("Accept & view live", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAlert) {
                    Text("Dismiss", color = Color.White.copy(alpha = 0.55f))
                }
            },
        )
    }

    uiState.signalCut?.let { corte ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSignalCut,
            containerColor = FondoAlerta,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Signal cut", color = Color.White, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Text(
                    text = "Officer ${corte.officer.ifEmpty { "(unknown)" }} cut the signal at " +
                        "${formatTime(corte.timestampMillis)}\n" +
                        "Last location: ${formatCoords(corte.latitude, corte.longitude)}",
                    color = TextoSecundario,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSignalCut) {
                    Text("Close", color = Color.White)
                }
            },
        )
    }
}

/**
 * Sirena en bucle mientras este composable este en pantalla. Se detiene y
 * libera sola cuando la alerta se cierra (el composable sale de composicion).
 */
@Composable
private fun SirenLoop() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val reproductor = MediaPlayer.create(context, R.raw.emergency)?.apply {
            isLooping = true
            start()
        }
        onDispose {
            reproductor?.stop()
            reproductor?.release()
        }
    }
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun formatCoords(latitude: Double?, longitude: Double?): String =
    if (latitude != null && longitude != null) {
        "%.5f, %.5f".format(latitude, longitude)
    } else {
        "unknown"
    }
