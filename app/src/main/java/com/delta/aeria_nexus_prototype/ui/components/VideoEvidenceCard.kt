package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.BordeMuySutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tarjeta de evidencia de video con forma de onda simulada. Se usa tanto en
 * el incidente activo (grabando en vivo) como en el detalle de incidente.
 */
@Composable
fun VideoEvidenceCard(
    isRecording: Boolean,
    isSealed: Boolean,
    footerText: String,
    devices: List<String>,
    modifier: Modifier = Modifier,
    durationText: String? = null,
    sealedLabel: String = "SEALED RECORD",
) {
    val bordeColor = if (isRecording) RojoCritico.copy(alpha = 0.4f) else BordeMuySutil

    Surface(
        modifier = modifier,
        color = Superficie,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, bordeColor),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = "Video evidence preview" },
                contentAlignment = Alignment.Center,
            ) {
                WaveformBars(color = if (isRecording) RojoSuave else AzulClaro)

                // Icono central: cuadrado rojo si graba, camara si no.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isRecording) RojoCritico.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f),
                            CircleShape,
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRecording) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(RojoSuave, RoundedCornerShape(3.dp)),
                        )
                    } else {
                        Icon(Icons.Filled.Videocam, contentDescription = null, tint = TextoSecundario)
                    }
                }

                if (isRecording) {
                    LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                }

                if (isSealed) {
                    SealedOverlay(sealedLabel)
                }

                if (durationText != null) {
                    Text(
                        text = durationText,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            HorizontalDivider(color = BordeMuySutil)

            // Pie: candado, hash o estado de cifrado y dispositivos activos.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Encrypted",
                    tint = VerdeOk,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = footerText,
                    modifier = Modifier.weight(1f),
                    color = TextoSecundario,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { device -> DeviceBadge(device) }
                }
            }
        }
    }
}

/** Barras de onda estaticas, mismas alturas pseudoaleatorias que el prototipo web. */
@Composable
private fun WaveformBars(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val totalBarras = 48
        val anchoBarra = size.width / (totalBarras * 1.5f)
        val espacio = anchoBarra / 2
        for (i in 0 until totalBarras) {
            val fraccionAltura = (20f + sin(i * 0.7f) * 18f + cos(i * 1.3f) * 12f) / 100f
            val alturaBarra = size.height * fraccionAltura
            drawRoundRect(
                color = color.copy(alpha = 0.3f),
                topLeft = Offset(
                    x = i * (anchoBarra + espacio),
                    y = (size.height - alturaBarra) / 2,
                ),
                size = Size(anchoBarra, alturaBarra),
                cornerRadius = CornerRadius(anchoBarra / 2),
            )
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(RojoCritico, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(Color.White, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "LIVE",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun SealedOverlay(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = VerdeOk,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = VerdeOk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Text(
            text = "Evidence locked after submission",
            color = TextoSecundario,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
