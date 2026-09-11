package com.delta.aeria_nexus_prototype.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.VaultRepository
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.EvidenceMediaPreview
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceEntity
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Boveda de evidencia: la unica forma de volver a ver en el telefono una foto,
 * un video o una nota de audio capturados con la app. Todo lo que hay aqui esta
 * cifrado en la carpeta privada; nada aparece en la galeria.
 */
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppScaffold(currentTab = null, onTabSelected = onTabSelected, showNav = false) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VaultHeader(
                desbloqueada = uiState.desbloqueada,
                onBack = onBack,
                onBloquear = viewModel::bloquear,
            )
            when {
                !uiState.configurada -> PasswordForm(
                    descripcion = "Create the password that will protect the evidence " +
                        "captured with this phone. It is never stored: if you forget it, " +
                        "the evidence can only be recovered from Nexus.",
                    accion = "CREATE VAULT",
                    conConfirmacion = true,
                    trabajando = uiState.trabajando,
                    mensajeError = uiState.mensajeError,
                    onConfirmar = viewModel::crearBoveda,
                )

                !uiState.desbloqueada -> PasswordForm(
                    descripcion = "Enter your vault password to review the evidence stored on this device.",
                    accion = "UNLOCK",
                    conConfirmacion = false,
                    trabajando = uiState.trabajando,
                    mensajeError = uiState.mensajeError,
                    onConfirmar = { contrasena, _ -> viewModel.desbloquear(contrasena) },
                )

                else -> EvidenceList(
                    evidencias = uiState.evidencias,
                    pendientes = uiState.sinCategorizar,
                    onCategorizar = viewModel::pedirCategorizacion,
                )
            }
        }
    }

    uiState.categorizando?.let { fila ->
        CategorizeDialog(
            fila = fila,
            incidentes = uiState.incidentes,
            trabajando = uiState.trabajando,
            onDismiss = viewModel::cancelarCategorizacion,
            onConfirm = viewModel::categorizar,
        )
    }
}

@Composable
private fun VaultHeader(desbloqueada: Boolean, onBack: () -> Unit, onBloquear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextoSecundario)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "AERIA NEXUS",
                color = TextoTerciario,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Text(
                text = "EVIDENCE VAULT",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )
        }
        // El candado no es solo decorativo: dice si ahora mismo la evidencia se
        // puede ver, y es el boton para volver a cerrarla.
        val color = if (desbloqueada) VerdeOk else TextoTerciario
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = desbloqueada, onClick = onBloquear)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (desbloqueada) Icons.Filled.LockOpen else Icons.Filled.Lock,
                contentDescription = if (desbloqueada) "Lock vault" else null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (desbloqueada) "LOCK" else "SEALED",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * Formulario de contrasena, con un segundo campo de confirmacion cuando la
 * boveda se esta creando. Es el mismo en los dos casos: cambian el texto y la
 * accion, no el comportamiento.
 */
@Composable
private fun PasswordForm(
    descripcion: String,
    accion: String,
    conConfirmacion: Boolean,
    trabajando: Boolean,
    mensajeError: String?,
    onConfirmar: (String, String) -> Unit,
) {
    var contrasena by remember { mutableStateOf("") }
    var repetida by remember { mutableStateOf("") }

    CardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = descripcion, color = TextoSecundario, fontSize = 14.sp, lineHeight = 21.sp)

            PasswordField(
                value = contrasena,
                placeholder = "Vault password",
                onValueChange = { contrasena = it },
            )
            if (conConfirmacion) {
                PasswordField(
                    value = repetida,
                    placeholder = "Repeat password",
                    onValueChange = { repetida = it },
                )
            }

            if (mensajeError != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = RojoSuave,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = mensajeError, color = RojoSuave, fontSize = 13.sp)
                }
            }

            Button(
                onClick = { onConfirmar(contrasena, repetida) },
                enabled = !trabajando && contrasena.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AzulPrimario,
                    disabledContainerColor = Superficie,
                ),
            ) {
                if (trabajando) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text(text = accion, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = TextoDeshabilitado) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Superficie,
            unfocusedContainerColor = Superficie,
            focusedBorderColor = AzulPrimario.copy(alpha = 0.5f),
            unfocusedBorderColor = BordeSutil,
            focusedTextColor = TextoPrincipal,
            unfocusedTextColor = TextoPrincipal,
        ),
    )
}

@Composable
private fun EvidenceList(
    evidencias: List<VaultRepository.VaultItem>,
    pendientes: List<RawEvidenceEntity>,
    onCategorizar: (RawEvidenceEntity) -> Unit,
) {
    if (evidencias.isEmpty() && pendientes.isEmpty()) {
        Text(
            text = "No evidence captured with this phone yet.",
            color = TextoSecundario,
            fontSize = 14.sp,
        )
        return
    }
    // Lo importado va ARRIBA y en su propio apartado: es lo unico que le pide algo
    // al agente. El resto de la boveda ya esta en su incidente y solo se consulta.
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendientes.isNotEmpty()) {
            item { Apartado("Pending categorization") }
            items(pendientes, key = { it.fileName }) { fila ->
                RawEvidenceRow(fila = fila, onCategorizar = { onCategorizar(fila) })
            }
            item { Apartado("Vault") }
        }
        items(evidencias, key = { it.name }) { evidencia -> EvidenceRow(evidencia) }
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
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Pieza importada de un periferico que aun no pertenece a ningun incidente.
 *
 * Muestra la fecha de GRABACION, no la de descarga, y dice cuando el aparato no
 * la dio en vez de rellenarla con la otra: es el dato que hace util a una prueba.
 */
@Composable
private fun RawEvidenceRow(fila: RawEvidenceEntity, onCategorizar: () -> Unit) {
    CardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = AmarilloAviso,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (fila.recordedAtMillis > 0) {
                        FORMATO_GRABACION.format(java.util.Date(fila.recordedAtMillis))
                    } else {
                        "No recording date"
                    },
                    color = TextoPrincipal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${fila.source.label}  ·  ${fila.originalName}",
                    color = TextoTerciario,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            Text(
                text = "CATEGORIZE",
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .border(1.dp, BordeSutil, RoundedCornerShape(6.dp))
                    .clickable(onClick = onCategorizar)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = AmarilloAviso,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val FORMATO_GRABACION = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)

/** Fila de la lista; al tocarla se descifra y se muestra la evidencia. */
@Composable
private fun EvidenceRow(evidencia: VaultRepository.VaultItem) {
    var abierta by remember { mutableStateOf(false) }
    CardSurface {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = evidencia.openable) { abierta = !abierta },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (evidencia.type) {
                        EvidenceType.PHOTO -> Icons.Filled.PhotoCamera
                        EvidenceType.AUDIO -> Icons.Filled.Mic
                        else -> Icons.Filled.Videocam
                    },
                    contentDescription = null,
                    tint = AzulClaro,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = evidencia.capturedAt,
                        color = TextoPrincipal,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${evidencia.name}  ·  ${tamanoLegible(evidencia.bytes)}",
                        color = TextoTerciario,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Text(
                    text = when {
                        !evidencia.openable -> "NO KEY"
                        abierta -> "HIDE"
                        else -> "OPEN"
                    },
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                        .border(1.dp, BordeSutil, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (evidencia.openable) AzulClaro else TextoTerciario,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (abierta) {
                EvidenceMediaPreview(
                    sealedName = evidencia.name,
                    type = evidencia.type,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

private fun tamanoLegible(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / 1024} KB"
}
