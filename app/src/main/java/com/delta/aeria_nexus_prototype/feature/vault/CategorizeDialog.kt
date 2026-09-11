package com.delta.aeria_nexus_prototype.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceEntity
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Categorizacion de una pieza importada: clasificacion e incidente.
 *
 * Las dos decisiones son del agente y ninguna se rellena sola. El incidente,
 * porque un video que llega cuando su grabacion ya termino no se puede cruzar por
 * hora sin conjeturar; la clasificacion, porque es la que viaja al RMS.
 *
 * Se puede **crear un incidente nuevo** desde aqui: es el caso normal cuando el
 * agente graba con las gafas algo que no abrio como incidente en el telefono.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorizeDialog(
    fila: RawEvidenceEntity,
    incidentes: List<OfficerIncident>,
    trabajando: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (EvidenceClass, String, String?) -> Unit,
) {
    var clasificacion by remember { mutableStateOf<EvidenceClass?>(null) }
    // null = incidente nuevo. Es el valor de partida a proposito: lo mas comun es
    // que el material de un periferico no tenga aun incidente al que ir.
    var incidenteElegido by remember { mutableStateOf<String?>(null) }
    var etiqueta by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Superficie,
        title = {
            Text(
                text = "Categorize evidence",
                color = TextoPrincipal,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Procedencia(fila)

                Apartado("Classification")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EvidenceClass.entries.forEach { opcion ->
                        Chip(
                            texto = opcion.label,
                            seleccionado = clasificacion == opcion,
                            onClick = { clasificacion = opcion },
                        )
                    }
                }

                Apartado("Incident")
                Chip(
                    texto = "New incident",
                    seleccionado = incidenteElegido == null,
                    onClick = { incidenteElegido = null },
                )
                Column(
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    incidentes.forEach { incidente ->
                        Chip(
                            texto = "${incidente.id}  ·  ${incidente.type}  ·  ${incidente.date}",
                            seleccionado = incidenteElegido == incidente.id,
                            onClick = { incidenteElegido = incidente.id },
                        )
                    }
                }

                Apartado("Label (optional)")
                CampoEtiqueta(valor = etiqueta, onChange = { etiqueta = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = clasificacion != null && !trabajando,
                onClick = { clasificacion?.let { onConfirm(it, etiqueta, incidenteElegido) } },
            ) {
                Text(
                    text = if (trabajando) "SAVING…" else "CATEGORIZE",
                    color = if (clasificacion != null) AzulClaro else TextoDeshabilitado,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = TextoSecundario, fontSize = 13.sp)
            }
        },
    )
}

/**
 * De donde salio y cuando se grabo. La fecha de grabacion se muestra aparte de la
 * de descarga, y si el aparato no la dio se dice: poner la de descarga en su lugar
 * seria inventarse el dato que hace util a una prueba.
 */
@Composable
private fun Procedencia(fila: RawEvidenceEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = fila.source.label,
            color = AzulClaro,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (fila.recordedAtMillis > 0) {
                "Recorded ${FORMATO.format(Date(fila.recordedAtMillis))}"
            } else {
                "No recording date reported by the device"
            },
            color = TextoSecundario,
            fontSize = 12.sp,
        )
        Text(
            text = fila.originalName,
            color = TextoTerciario,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun Apartado(titulo: String) {
    Text(
        text = titulo.uppercase(),
        color = TextoTerciario,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun Chip(texto: String, seleccionado: Boolean, onClick: () -> Unit) {
    Text(
        text = texto,
        modifier = Modifier
            .background(
                if (seleccionado) AzulPrimario.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (seleccionado) AzulClaro else BordeSutil,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        color = if (seleccionado) TextoPrincipal else TextoSecundario,
        fontSize = 12.sp,
    )
}

@Composable
private fun CampoEtiqueta(valor: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = valor,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = TextoPrincipal, fontSize = 13.sp),
        cursorBrush = SolidColor(AzulClaro),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .border(1.dp, BordeSutil, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        decorationBox = { campo ->
            if (valor.isEmpty()) {
                Text(
                    text = "Device and file name if left empty",
                    color = TextoDeshabilitado,
                    fontSize = 13.sp,
                )
            }
            campo()
        },
    )
}

private val FORMATO = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
