package com.delta.aeria_nexus_prototype.feature.gafas

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.R
import com.delta.aeria_nexus_prototype.data.GafasControlState
import com.delta.aeria_nexus_prototype.data.GafasState
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.DeviceActionButton
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Controlador remoto de las gafas BleeqUp Ranger: grabacion y foto por BLE.
 *
 * A diferencia de la bodycam, aqui no hay livestream ni SOS: las gafas graban en
 * su propia tarjeta y no entran al canal de Agora. El video se trae despues por
 * su punto de acceso WiFi, y solo con la grabacion parada.
 */
@Composable
fun GafasControlScreen(
    viewModel: GafasControlViewModel,
    onBack: () -> Unit,
    onTabSelected: (MainTab) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // El canal de mando es BLE: sin BLUETOOTH_CONNECT no se abre ningun GATT.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> if (concedido) viewModel.alConcederBluetooth() }

    // Se conecta al entrar y no con un boton: quien abre esta pantalla ya ha
    // dicho lo que quiere, y pedirle otro toque solo retrasa la grabacion.
    LaunchedEffect(Unit) {
        if (viewModel.tienePermisoBluetooth()) {
            viewModel.conectar()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    AppScaffold(
        currentTab = null,
        onTabSelected = onTabSelected,
        isRecording = uiState.grabando,
        showNav = false,
    ) { innerPadding ->
        GafasControlContent(
            uiState = uiState,
            onBack = onBack,
            onReintentar = viewModel::conectar,
            onDesconectar = viewModel::desconectar,
            onAlternarGrabacion = viewModel::alternarGrabacion,
            onHacerFoto = viewModel::hacerFoto,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun GafasControlContent(
    uiState: GafasControlUiState,
    onBack: () -> Unit,
    onReintentar: () -> Unit,
    onDesconectar: () -> Unit,
    onAlternarGrabacion: () -> Unit,
    onHacerFoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cabecera(onBack = onBack)
        TarjetaDeEstado(uiState = uiState)

        if (uiState.aviso != null) {
            BandaDeAviso(texto = uiState.aviso)
        }

        Spacer(Modifier.weight(1f))

        if (uiState.control == GafasControlState.LISTO) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceActionButton(
                    label = "FOTO",
                    sublabel = "Se guarda en las gafas",
                    icon = Icons.Filled.CameraAlt,
                    accentColor = AzulClaro,
                    onClick = onHacerFoto,
                    modifier = Modifier.weight(1f),
                )
                DeviceActionButton(
                    label = if (uiState.grabando) "PARAR" else "GRABAR",
                    sublabel = if (uiState.grabando) {
                        "Sin descargas mientras graba"
                    } else {
                        "Local, sin aviso a nadie"
                    },
                    icon = Icons.Filled.Videocam,
                    accentColor = if (uiState.grabando) RojoCritico else VerdeOk,
                    onClick = onAlternarGrabacion,
                    modifier = Modifier.weight(1f),
                )
            }
            BotonDesconectar(onClick = onDesconectar)
        } else {
            PanelSinMando(uiState = uiState, onReintentar = onReintentar)
        }
    }
}

@Composable
private fun Cabecera(onBack: () -> Unit) {
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
        Column {
            Text(
                text = "FALCON LENS",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = "Gafas BleeqUp Ranger — mando a distancia",
                color = TextoTerciario,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Los dos enlaces con las gafas se pintan por separado a proposito: el de audio
 * puede estar puesto y el de mando no, y el agente tiene que poder distinguir
 * "no las llevo encima" de "las llevo pero no me obedecen".
 */
@Composable
private fun TarjetaDeEstado(uiState: GafasControlUiState) {
    val (colorMando, textoMando) = when (uiState.control) {
        GafasControlState.LISTO -> VerdeOk to "MANDO LISTO"
        GafasControlState.CONECTANDO -> AmarilloAviso to "CONECTANDO…"
        GafasControlState.ERROR -> RojoCritico to "SIN MANDO"
        GafasControlState.DESCONECTADO -> TextoTerciario to "DESCONECTADO"
    }

    CardSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(colorMando, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = textoMando,
                    color = colorMando,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.icon_eyeglasses),
                    contentDescription = "Enlace Bluetooth de las gafas",
                    tint = if (uiState.enlace == GafasState.CONNECTED) VerdeOk else TextoTerciario,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (uiState.resolucion != null || uiState.espacioLibre != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = listOfNotNull(
                        uiState.resolucion?.let { "Video $it" },
                        uiState.espacioLibre?.let { "$it libres" },
                    ).joinToString("  ·  "),
                    color = TextoSecundario,
                    fontSize = 13.sp,
                )
            }
            if (uiState.grabando) {
                Spacer(Modifier.height(8.dp))
                // El SDK no sabe decir si las gafas estan grabando: esto es la
                // ultima orden dada, y el boton fisico de las gafas la puede
                // haber cambiado sin que la app se entere. Se dice, no se oculta.
                Text(
                    text = "Grabando desde esta app. Si se usa el boton de las gafas, " +
                        "el telefono no se entera.",
                    color = AmarilloAviso,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun BandaDeAviso(texto: String) {
    Text(
        text = texto,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AzulOscuroPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = AzulClaro,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Sin canal de mando no hay nada que tocar: solo se explica y se reintenta. */
@Composable
private fun PanelSinMando(uiState: GafasControlUiState, onReintentar: () -> Unit) {
    val conectando = uiState.control == GafasControlState.CONECTANDO
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when {
                conectando -> "Abriendo el canal de mando con las gafas…"
                uiState.enlace != GafasState.CONNECTED ->
                    "Las gafas no estan enlazadas con el telefono. Ponselas, enciendelas " +
                        "y espera a que se conecten solas."
                uiState.control == GafasControlState.ERROR ->
                    "Las gafas estan enlazadas pero no responden a las ordenes. " +
                        "Apagalas y vuelve a encenderlas antes de reintentar."
                else -> "Conecta para grabar y hacer fotos desde este telefono."
            },
            color = TextoSecundario,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (conectando) Superficie else AzulPrimario)
                .border(1.dp, AzulPrimario.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable(enabled = !conectando, onClick = onReintentar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (conectando) "CONECTANDO…" else "CONECTAR GAFAS",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun BotonDesconectar(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SOLTAR EL MANDO",
            color = TextoSecundario,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF080B12)
@Composable
private fun GafasControlGrabandoPreview() {
    GafasControlContent(
        uiState = GafasControlUiState(
            enlace = GafasState.CONNECTED,
            control = GafasControlState.LISTO,
            grabando = true,
            resolucion = "1920x1080",
            espacioLibre = "21.4 GB",
        ),
        onBack = {},
        onReintentar = {},
        onDesconectar = {},
        onAlternarGrabacion = {},
        onHacerFoto = {},
    )
}
