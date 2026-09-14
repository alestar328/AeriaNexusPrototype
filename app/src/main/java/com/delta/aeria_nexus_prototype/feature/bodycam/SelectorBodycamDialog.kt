package com.delta.aeria_nexus_prototype.feature.bodycam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.DispositivoCercano
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Lista de aparatos Bluetooth cercanos para elegir la bodycam de este telefono.
 * Tocar uno lo guarda y conecta en el mismo gesto.
 */
@Composable
fun SelectorBodycamDialog(
    buscando: Boolean,
    cercanas: List<DispositivoCercano>,
    onElegir: (DispositivoCercano) -> Unit,
    onBuscarOtraVez: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Superficie,
        title = {
            Text(
                text = "Choose your bodycam",
                color = TextoPrincipal,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (buscando) {
                    EstadoBuscando()
                } else if (cercanas.isEmpty()) {
                    Text(
                        text = "No devices found. Check that the bodycam is on and close to " +
                            "this phone. On Android 11 or older, Location must also be on.",
                        color = TextoSecundario,
                        fontSize = 14.sp,
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cercanas, key = { it.mac }) { dispositivo ->
                        FilaDispositivo(dispositivo = dispositivo, onClick = { onElegir(dispositivo) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBuscarOtraVez, enabled = !buscando) {
                Text(
                    text = "SEARCH AGAIN",
                    color = if (buscando) TextoDeshabilitado else AzulClaro,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextoSecundario, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun EstadoBuscando() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            color = AzulClaro,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = "Searching nearby devices…", color = TextoSecundario, fontSize = 14.sp)
    }
}

@Composable
private fun FilaDispositivo(dispositivo: DispositivoCercano, onClick: () -> Unit) {
    // Las bodycams se destacan porque cerca suele haber muchos otros aparatos
    // (auriculares, coches, relojes) y el agente no conoce el nombre de fabrica.
    val forma = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(forma)
            .background(if (dispositivo.pareceBodycam) AzulOscuroPanel else Superficie)
            .border(1.dp, if (dispositivo.pareceBodycam) AzulPrimario else BordeSutil, forma)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = dispositivo.nombre,
                color = TextoPrincipal,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = dispositivo.mac,
                color = TextoTerciario,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (dispositivo.pareceBodycam) {
            Text(
                text = "BODYCAM",
                color = AzulClaro,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Preview(name = "Con resultados", showBackground = true, backgroundColor = 0xFF080B12)
@Composable
private fun SelectorBodycamDialogPreview() {
    SelectorBodycamDialog(
        buscando = true,
        cercanas = listOf(
            DispositivoCercano(nombre = "DSJ-ZXAN9A1", mac = "40:45:DA:44:C8:9B", pareceBodycam = true),
            DispositivoCercano(nombre = "JBL Tune 510BT", mac = "A0:1B:29:3C:44:10", pareceBodycam = false),
        ),
        onElegir = {},
        onBuscarOtraVez = {},
        onDismiss = {},
    )
}
