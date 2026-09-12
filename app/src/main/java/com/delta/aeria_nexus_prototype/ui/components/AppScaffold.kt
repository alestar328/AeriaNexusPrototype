package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.R
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.data.EnlaceAutenticado
import com.delta.aeria_nexus_prototype.data.GafasState
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
    val bodycamState by AppContainer.bodycamRepository.state.collectAsStateWithLifecycle()
    val enlaceBodycam by AppContainer.bodycamRepository.enlaceAutenticado.collectAsStateWithLifecycle()
    val gafasState by AppContainer.gafasRepository.state.collectAsStateWithLifecycle()

    // Releer al componer la barra tapa los dos huecos que tiene observar el
    // enlace de las gafas: el permiso de Bluetooth puede concederse despues del
    // arranque, y con la app en segundo plano Android aplaza los avisos a los
    // procesos en cache (verificado el 2026-09-07: una reconexion no llego hasta
    // volver a la app). La bodycam no lo sufre porque su servicio en primer plano
    // mantiene el proceso despierto; las gafas no tienen ninguno, a proposito.
    LaunchedEffect(Unit) { AppContainer.gafasRepository.refrescar() }

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
        // Falcon Camera (bodycam): SOLO indicador del estado del enlace; la
        // conexion se maneja desde la pantalla BODYCAM CONTROL.
        DeviceIndicator(
            icon = Icons.Filled.Videocam,
            description = "Falcon Camera (bodycam)",
            tint = bodycamStateColor(bodycamState, enlaceBodycam),
        )
        // El estado nunca va solo en color: un enlace conectado y otro conectado
        // pero sin acreditar se verian igual, y la diferencia importa mas que la
        // conexion. Cuando todo esta en orden no se escribe nada; el aviso solo
        // aparece cuando hay algo que decir.
        avisoDelEnlace(bodycamState, enlaceBodycam)?.let { aviso ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = aviso.texto,
                color = aviso.color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        // Falcon Lens (gafas BleeqUp): indicador del enlace que trae el sistema.
        // Las gafas se conectan solas al telefono; el mando a distancia (grabar,
        // foto) va por otro enlace y vive en la pantalla FALCON LENS.
        // El set de Material de Compose no trae gafas: el icono es el vector
        // eyeglasses_2 de Material Symbols importado en drawable.
        DeviceIndicator(
            icon = ImageVector.vectorResource(R.drawable.icon_eyeglasses),
            description = "Falcon Lens (gafas)",
            tint = gafasStateColor(gafasState),
        )
        if (isRecording) {
            Spacer(Modifier.width(12.dp))
            IndicatorDot(color = RojoSuave, label = "REC")
        }
    }
}

/**
 * Color del icono de la bodycam.
 *
 * Estar conectado y ser de fiar son dos cosas distintas (workflow 31): un enlace
 * abierto con una camara que no ha podido acreditarse es PEOR que no tener
 * camara, porque el agente cree que la tiene. Por eso el verde se reserva para el
 * enlace autenticado.
 */
private fun bodycamStateColor(state: BodycamState, enlace: EnlaceAutenticado): Color = when {
    state != BodycamState.CONNECTED -> when (state) {
        BodycamState.CONNECTING -> AmarilloAviso
        BodycamState.ERROR -> RojoSuave
        else -> TextoTerciario
    }
    enlace == EnlaceAutenticado.SI -> VerdeOk
    enlace == EnlaceAutenticado.RECHAZADO -> RojoSuave
    else -> AmarilloAviso
}

/**
 * Color del icono de las gafas.
 *
 * Aqui no hay verde condicionado como en la bodycam: las gafas no se acreditan
 * ante el telefono ni reciben ordenes suyas, asi que lo unico que se puede
 * afirmar (y lo unico que el agente necesita saber) es si las lleva enlazadas.
 */
private fun gafasStateColor(state: GafasState): Color = when (state) {
    GafasState.CONNECTED -> VerdeOk
    GafasState.CONNECTING -> AmarilloAviso
    GafasState.DISCONNECTED -> TextoTerciario
}

/** Lo que se escribe junto al icono, o null si no hay nada que advertir. */
private data class AvisoDeEnlace(val texto: String, val color: Color)

private fun avisoDelEnlace(state: BodycamState, enlace: EnlaceAutenticado): AvisoDeEnlace? {
    if (state != BodycamState.CONNECTED) return null
    return when (enlace) {
        // Lo normal no se anuncia: si cada enlace correcto pusiera una etiqueta,
        // el agente dejaria de leerlas y la que importa pasaria desapercibida.
        EnlaceAutenticado.SI -> null
        EnlaceAutenticado.COMPROBANDO -> AvisoDeEnlace("CHECKING", AmarilloAviso)
        EnlaceAutenticado.RECHAZADO -> AvisoDeEnlace("NOT TRUSTED", RojoSuave)
        else -> AvisoDeEnlace("UNVERIFIED", AmarilloAviso)
    }
}

@Composable
private fun DeviceIndicator(
    icon: ImageVector,
    description: String,
    tint: Color,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(20.dp),
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
