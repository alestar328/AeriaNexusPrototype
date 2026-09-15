package com.delta.aeria_nexus_prototype.feature.gafas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import com.delta.aeria_nexus_prototype.data.GafasCandidatas
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Elegir las gafas de este telefono entre lo que ya esta emparejado.
 *
 * No hay boton de buscar, al contrario que con la bodycam: las gafas no se anuncian
 * por radio, asi que una busqueda no las encontraria. Se emparejan en los ajustes de
 * Android y aqui se elige. Por eso el dialogo lleva a esos ajustes.
 */
@Composable
fun SelectorGafasDialog(
    candidatas: List<GafasCandidatas>,
    onElegir: (GafasCandidatas) -> Unit,
    onAbrirAjustesBluetooth: () -> Unit,
    onReleer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Superficie,
        title = {
            Text(
                text = "Choose your FalconOne",
                color = TextoPrincipal,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (candidatas.none { it.parecenGafas }) {
                        "No FalconOne glasses are paired with this phone. Turn them on, pair them in " +
                            "Android Bluetooth settings like headphones, and come back."
                    } else {
                        "Paired with this phone. The glasses must be paired in Android settings first."
                    },
                    color = TextoSecundario,
                    fontSize = 14.sp,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(candidatas, key = { it.mac }) { gafas ->
                        FilaCandidata(gafas = gafas, onClick = { onElegir(gafas) })
                    }
                }
                TextButton(
                    onClick = onAbrirAjustesBluetooth,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = "OPEN BLUETOOTH SETTINGS", color = AzulClaro, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReleer, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "REFRESH", color = AzulClaro, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "CANCEL", color = TextoSecundario, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun FilaCandidata(gafas: GafasCandidatas, onClick: () -> Unit) {
    // Destacadas porque en la lista salen tambien auriculares, coches y relojes.
    val forma = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(forma)
            .background(if (gafas.parecenGafas) AzulOscuroPanel else Superficie)
            .border(1.dp, if (gafas.parecenGafas) AzulPrimario else BordeSutil, forma)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = gafas.nombre, color = TextoPrincipal, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text = gafas.mac, color = TextoTerciario, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        if (gafas.parecenGafas) {
            Text(
                text = "FALCONONE",
                color = AzulClaro,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Preview(name = "Con gafas emparejadas", showBackground = true, backgroundColor = 0xFF080B12)
@Composable
private fun SelectorGafasDialogPreview() {
    SelectorGafasDialog(
        candidatas = listOf(
            GafasCandidatas(nombre = "BleeqUp-Ranger-901FC", mac = "F0:74:E4:79:7C:B1", parecenGafas = true),
            GafasCandidatas(nombre = "JBL Tune 510BT", mac = "A0:1B:29:3C:44:10", parecenGafas = false),
        ),
        onElegir = {},
        onAbrirAjustesBluetooth = {},
        onReleer = {},
        onDismiss = {},
    )
}
