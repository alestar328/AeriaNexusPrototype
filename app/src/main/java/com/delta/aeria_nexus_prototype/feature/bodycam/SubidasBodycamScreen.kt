package com.delta.aeria_nexus_prototype.feature.bodycam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.SubidaDeEvidencia
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Subidas de evidencia de la bodycam: que ha llegado, que sigue en cola y que se
 * ha cancelado, con el boton de cancelar y el de devolver a la cola.
 *
 * Cancelar **no borra el video**: lo saca de la cola de reintentos de la unidad.
 * El dialogo de confirmacion lo dice con esas palabras a proposito — si diera a
 * entender que borra, nadie se atreveria a usarlo.
 */
@Composable
fun SubidasBodycamScreen(
    viewModel: SubidasBodycamViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    uiState.confirmando?.let { id ->
        AlertDialog(
            onDismissRequest = viewModel::cerrarConfirmacion,
            containerColor = Superficie,
            title = { Text("Cancel upload?", color = Color.White) },
            text = {
                Text(
                    "$id stops uploading and leaves the retry queue.\n\n" +
                        "The video stays on the camera. You can queue it again later " +
                        "and it will resume where it left off.",
                    color = TextoSecundario,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmarCancelacion) {
                    Text("CANCEL UPLOAD", color = RojoCritico, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cerrarConfirmacion) {
                    Text("KEEP UPLOADING", color = TextoSecundario)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cabecera(onBack = onBack, cargando = uiState.cargando, onRefrescar = viewModel::refrescar)

        uiState.feedback?.let { mensaje ->
            CardSurface(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = mensaje,
                    color = AzulClaro,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        when {
            !uiState.conectada -> Aviso("Bodycam not connected. Connect it to see its uploads.")
            uiState.subidas.isEmpty() && uiState.cargando -> Aviso("Asking the camera…")
            uiState.subidas.isEmpty() -> Aviso("No evidence on this camera.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.subidas, key = { it.id }) { subida ->
                    FilaDeSubida(
                        subida = subida,
                        onCancelar = { viewModel.pedirConfirmacion(subida.id) },
                        onReanudar = { viewModel.reanudar(subida.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Cabecera(onBack: () -> Unit, cargando: Boolean, onRefrescar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Superficie)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "UPLOADS",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = "Evidence waiting to reach Nexus",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
        }
        if (cargando) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AzulClaro)
        } else {
            TextButton(onClick = onRefrescar) {
                Text("REFRESH", color = AzulClaro, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = texto,
            color = TextoSecundario,
            fontSize = 13.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun FilaDeSubida(
    subida: SubidaDeEvidencia,
    onCancelar: () -> Unit,
    onReanudar: () -> Unit,
) {
    // Tres estados y no dos: cancelado no es lo mismo que pendiente, y mezclarlos
    // haria pensar que la evidencia se perdio cuando sigue en la camara.
    val (color, etiqueta) = when {
        subida.entregada -> VerdeOk to "DELIVERED"
        subida.cancelada -> AmarilloAviso to "CANCELLED"
        else -> AzulClaro to "PENDING"
    }

    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subida.id,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(2.dp))
                Box(
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(text = etiqueta, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            when {
                // Lo entregado no ofrece nada: ya esta en Nexus y no hay vuelta atras.
                subida.entregada -> Unit
                subida.cancelada -> TextButton(onClick = onReanudar) {
                    Text("QUEUE AGAIN", color = AzulClaro, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                else -> TextButton(onClick = onCancelar) {
                    Text("CANCEL", color = RojoCritico, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
