package com.delta.aeria_nexus_prototype.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Pestanas principales de la barra de navegacion inferior. */
enum class MainTab(val label: String, val icon: ImageVector) {
    OPERATIONS("Operations", Icons.Filled.Bolt),
    MAP("Map", Icons.Filled.Map),
    INCIDENTS("Incidents", Icons.Filled.Description),
    PROFILE("Profile", Icons.Filled.Person),
}

/**
 * Estructura comun de pantalla: barra de estado superior (reloj, conexion,
 * dispositivos, indicador de grabacion) y navegacion inferior opcional.
 */
@Composable
fun AppScaffold(
    currentTab: MainTab?,
    onTabSelected: (MainTab) -> Unit,
    isRecording: Boolean = false,
    showNav: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = FondoBase,
        topBar = { StatusBar(isRecording = isRecording) },
        bottomBar = {
            if (showNav) {
                BottomNavBar(currentTab = currentTab, onTabSelected = onTabSelected)
            }
        },
        content = content,
    )
}

@Composable
private fun StatusBar(isRecording: Boolean) {
    // Reloj en vivo. El bucle se cancela solo al salir de composicion.
    var horaActual by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            horaActual = LocalTime.now()
            delay(1_000)
        }
    }

    // La barra de estado es global (no pertenece a ninguna pantalla), por eso
    // observa el repositorio directamente en lugar de pasar por un ViewModel.
    val bodycam = AppContainer.bodycamRepository
    val bodycamState by bodycam.state.collectAsStateWithLifecycle()

    // El permiso Bluetooth es de runtime desde Android 12; se pide al tocar el
    // icono de la bodycam y, si se concede, se conecta en el mismo gesto.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) bodycam.connect()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie)
            .statusBarsPadding()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = horaActual.format(HORA_CON_SEGUNDOS),
            color = TextoSecundario,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
        IndicatorDot(color = VerdeOk, label = "ONLINE")
        Spacer(Modifier.weight(1f))
        // Falcon Camera (bodycam): tocar conecta o desconecta el Bluetooth.
        DeviceIndicator(
            icon = Icons.Filled.Videocam,
            description = "Falcon Camera (bodycam)",
            tint = bodycamStateColor(bodycamState),
            onClick = {
                when {
                    // Conectado o reintentando: el toque apaga el enlace.
                    bodycamState == BodycamState.CONNECTED || bodycamState == BodycamState.CONNECTING ->
                        bodycam.disconnect()
                    !bodycam.hasBluetoothPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else -> bodycam.connect()
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        // Falcon Lens (gafas): integracion pendiente, siempre desconectado.
        // El set de Material no trae gafas; el visor Vrpano es lo mas parecido.
        DeviceIndicator(
            icon = Icons.Filled.Vrpano,
            description = "Falcon Lens (gafas)",
            tint = TextoTerciario,
            onClick = null,
        )
        if (isRecording) {
            Spacer(Modifier.width(12.dp))
            IndicatorDot(color = RojoSuave, label = "REC")
        }
    }
}

/** Color del icono de la bodycam segun el estado de la conexion Bluetooth. */
private fun bodycamStateColor(state: BodycamState): Color = when (state) {
    BodycamState.CONNECTED -> VerdeOk
    BodycamState.CONNECTING -> AmarilloAviso
    BodycamState.ERROR -> RojoSuave
    BodycamState.DISCONNECTED -> TextoTerciario
}

@Composable
private fun DeviceIndicator(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.size(20.dp)
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    )
}

@Composable
private fun IndicatorDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BottomNavBar(currentTab: MainTab?, onTabSelected: (MainTab) -> Unit) {
    NavigationBar(containerColor = Superficie) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = {
                    Text(
                        text = tab.label.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AzulClaro,
                    selectedTextColor = AzulClaro,
                    unselectedIconColor = TextoTerciario,
                    unselectedTextColor = TextoTerciario,
                    indicatorColor = Superficie,
                ),
            )
        }
    }
}

private val HORA_CON_SEGUNDOS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
