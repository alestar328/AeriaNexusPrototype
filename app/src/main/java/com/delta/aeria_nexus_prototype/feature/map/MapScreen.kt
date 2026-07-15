package com.delta.aeria_nexus_prototype.feature.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.CardSurface
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.DoradoAgente
import com.delta.aeria_nexus_prototype.ui.theme.GrisSinSenal
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk
import com.mapbox.geojson.Point
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions

// Estilo oscuro custom del proyecto, el mismo de la app Flutter Falcon One.
private const val MAP_STYLE_URI = "mapbox://styles/fiddlie-ed/cmc9h7ar2035801sm6361cdtc"

// Camara inicial identica al prototipo: 3D inclinada hasta el primer fix GPS.
private const val INITIAL_LNG = 0.031085
private const val INITIAL_LAT = 51.501435
private const val MAP_ZOOM = 16.0
private const val MAP_PITCH = 60.0

/**
 * Mapa tactico (fase 1): posicion propia en tiempo real con puck pulsante,
 * boton de recentrado y panel de estado (bateria, GPS, satelites).
 * La fase 2 agrega los marcadores de otros agentes via Agora.
 * [focusLatitude]/[focusLongitude] centran la camara en esa posicion al abrir
 * (p. ej. la ubicacion de un SOS) en lugar de en la posicion propia.
 * [onOpenLivestream] abre el livestream del agente en emergencia (por su uid).
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onTabSelected: (MainTab) -> Unit,
    onOpenLivestream: (Int) -> Unit,
    focusLatitude: Double? = null,
    focusLongitude: Double? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resultados ->
        hasLocationPermission =
            resultados[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // El GPS arranca en cuanto hay permiso (incluida la primera composicion).
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) viewModel.onLocationPermissionGranted()
    }

    val focusPoint = if (focusLatitude != null && focusLongitude != null) {
        Point.fromLngLat(focusLongitude, focusLatitude)
    } else {
        null
    }
    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(focusPoint ?: Point.fromLngLat(INITIAL_LNG, INITIAL_LAT))
            zoom(MAP_ZOOM)
            pitch(MAP_PITCH)
        }
    }

    // Vuela la camara a la posicion propia una sola vez, en el primer fix.
    // Con un foco pedido (ubicacion de un SOS) la camara ya esta ahi y el
    // primer fix GPS no debe arrastrarla a la posicion propia.
    var initialCameraDone by remember { mutableStateOf(focusPoint != null) }
    LaunchedEffect(uiState.gpsReady) {
        if (uiState.gpsReady && !initialCameraDone) {
            initialCameraDone = true
            flyToPosition(viewportState, uiState.longitude, uiState.latitude)
        }
    }

    AppScaffold(currentTab = MainTab.MAP, onTabSelected = onTabSelected) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = viewportState,
                style = { MapStyle(style = MAP_STYLE_URI) },
                // Los adornos (logo, attribution, escala, brujula) son slots de
                // Compose, no plugins del MapView: se ocultan con slots vacios.
                logo = {},
                attribution = {},
                scaleBar = {},
                compass = {},
            ) {
                MapEffect(Unit) { mapView ->
                    // Puck de posicion propia con pulso, igual que en Flutter.
                    mapView.location.updateSettings {
                        enabled = true
                        pulsingEnabled = true
                    }
                }

                // Marcadores de los demas agentes de la red tactica (fase 2).
                // allowOverlapWithPuck es obligatorio en todas las anotaciones:
                // sin el, Mapbox OCULTA la anotacion cuando se solapa con el
                // puck de la posicion propia (un companero a pocos metros
                // desapareceria del mapa hasta hacer zoom).
                uiState.remoteAgents.forEach { agente ->
                    key(agente.uid) {
                        ViewAnnotation(
                            options = viewAnnotationOptions {
                                geometry(Point.fromLngLat(agente.longitude, agente.latitude))
                                allowOverlap(true)
                                allowOverlapWithPuck(true)
                                annotationAnchor { anchor(ViewAnnotationAnchor.CENTER) }
                            },
                        ) {
                            RemoteAgentMarkerView(agente)
                        }
                    }
                }

                // SOS vigentes: tooltip rojo sobre la posicion de cada agente
                // en emergencia. Persiste aunque el popup se haya descartado y
                // tocarlo abre el livestream del emisor.
                uiState.activeSos.forEach { sos ->
                    key("sos-${sos.uid}") {
                        ViewAnnotation(
                            options = viewAnnotationOptions {
                                geometry(Point.fromLngLat(sos.longitude, sos.latitude))
                                allowOverlap(true)
                                allowOverlapWithPuck(true)
                                annotationAnchor { anchor(ViewAnnotationAnchor.BOTTOM) }
                            },
                        ) {
                            SosTooltip(
                                sos = sos,
                                onOpenLivestream = { onOpenLivestream(sos.uid) },
                            )
                        }
                    }
                }

                // Avisos de SOS con senal cortada: tooltip fijo sobre la ultima
                // posicion del emisor. Solo aparece en las demas unidades, nunca
                // en el telefono que emitio la alarma (Agora no le devuelve sus
                // propios mensajes).
                uiState.signalCuts.forEach { corte ->
                    key("signal-cut-${corte.uid}") {
                        ViewAnnotation(
                            options = viewAnnotationOptions {
                                geometry(Point.fromLngLat(corte.longitude, corte.latitude))
                                allowOverlap(true)
                                allowOverlapWithPuck(true)
                                annotationAnchor { anchor(ViewAnnotationAnchor.BOTTOM) }
                            },
                        ) {
                            SignalCutTooltip(
                                corte = corte,
                                onDismiss = { viewModel.dismissSignalCut(corte.uid) },
                            )
                        }
                    }
                }
            }

            if (!hasLocationPermission) {
                PermissionRequestCard(
                    modifier = Modifier.align(Alignment.Center),
                    onRequest = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                )
            }

            // Banners de SOS activos: fijos en pantalla, visibles sin importar
            // donde este la camara. Tocar el banner vuela a la posicion del
            // emisor; VIEW LIVE abre su livestream directo.
            if (uiState.activeSos.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    uiState.activeSos.forEach { sos ->
                        SosBanner(
                            sos = sos,
                            onLocate = {
                                flyToPosition(viewportState, sos.longitude, sos.latitude)
                            },
                            onOpenLivestream = { onOpenLivestream(sos.uid) },
                        )
                    }
                }
            }

            RecenterButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 140.dp),
                enabled = uiState.gpsReady,
                onClick = {
                    flyToPosition(viewportState, uiState.longitude, uiState.latitude)
                },
            )

            StatusPanel(
                uiState = uiState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}

/** Anima la camara hasta una posicion, con la misma curva que Flutter. */
private fun flyToPosition(
    viewportState: com.mapbox.maps.extension.compose.animation.viewport.MapViewportState,
    longitude: Double,
    latitude: Double,
) {
    viewportState.flyTo(
        cameraOptions {
            center(Point.fromLngLat(longitude, latitude))
            zoom(MAP_ZOOM)
            pitch(MAP_PITCH)
        },
        MapAnimationOptions.mapAnimationOptions { duration(800L) },
    )
}

@Composable
private fun PermissionRequestCard(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    CardSurface(modifier = modifier.padding(horizontal = 32.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = null,
                tint = AzulPrimario,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Location permission required",
                color = TextoPrincipal,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The tactical map shares your position with your unit.",
                color = TextoSecundario,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            ) {
                Text("Grant permission")
            }
        }
    }
}

@Composable
private fun RecenterButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Superficie.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MyLocation,
            contentDescription = "Center on my location",
            tint = if (enabled) TextoPrincipal else TextoTerciario,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Marcador de otro agente: punto dorado con anillo pulsante mientras emite;
 * gris y con la hora de su ultimo mensaje cuando pierde la senal.
 */
@Composable
private fun RemoteAgentMarkerView(agente: RemoteAgentMarker) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (!agente.isStale) {
                val pulso by rememberInfiniteTransition(label = "pulsoAgente").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1_200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "pulsoAgente",
                )
                Box(
                    modifier = Modifier
                        .size(16.dp + 28.dp * pulso)
                        .background(DoradoAgente.copy(alpha = (1f - pulso) * 0.5f), CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(if (agente.isStale) GrisSinSenal else DoradoAgente, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        if (agente.isStale) {
            Text(
                text = agente.lastSeenLabel,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Banner de SOS activo fijo en lo alto del mapa: se ve siempre, sin importar
 * donde este la camara ni el nivel de zoom. Tocar el cuerpo vuela la camara a
 * la posicion del emisor; el boton VIEW LIVE abre su livestream directo.
 */
@Composable
private fun SosBanner(
    sos: SosMarker,
    onLocate: () -> Unit,
    onOpenLivestream: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RojoCritico)
            .clickable(onClick = onLocate)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SOS — Agent ${sos.officer}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Since ${sos.startedTimeLabel} — tap to locate",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .clickable(onClick = onOpenLivestream)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = "VIEW LIVE",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * Tooltip de SOS vigente sobre la posicion del agente en emergencia. Toda la
 * tarjeta es tocable y abre el livestream del emisor; el punto pulsante en la
 * coordenada distingue la emergencia activa de un simple companero en el mapa.
 */
@Composable
private fun SosTooltip(sos: SosMarker, onOpenLivestream: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Superficie.copy(alpha = 0.95f))
                .border(1.dp, RojoCritico, RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenLivestream)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "SOS activo, tocar para ver el livestream",
                    tint = RojoCritico,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "SOS — Agent ${sos.officer}",
                    color = RojoCritico,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Since ${sos.startedTimeLabel} — tap to view live",
                color = TextoSecundario,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        // Punto pulsante sobre la posicion del emisor; el anchor BOTTOM de la
        // anotacion apoya este extremo justo en la coordenada.
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            val pulso by rememberInfiniteTransition(label = "pulsoSos").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "pulsoSos",
            )
            Box(
                modifier = Modifier
                    .size(12.dp + 20.dp * pulso)
                    .background(RojoCritico.copy(alpha = (1f - pulso) * 0.6f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(RojoCritico, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

/**
 * Tooltip fijo en el mapa cuando un agente en emergencia corto la senal:
 * identifica al agente y la hora exacta del corte, con un punto rojo sobre su
 * ultima posicion conocida. Solo se descarta con la X: un toque accidental
 * sobre la tarjeta no debe borrar un aviso critico.
 */
@Composable
private fun SignalCutTooltip(corte: SignalCutMarker, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Superficie.copy(alpha = 0.95f))
                .border(1.dp, RojoSuave, RoundedCornerShape(8.dp))
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "SOS con senal cortada",
                        tint = RojoSuave,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Agent ${corte.officer}",
                        color = TextoPrincipal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Signal cut: ${corte.cutTimeLabel}",
                    color = RojoSuave,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar aviso de corte",
                    tint = TextoSecundario,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Punto sobre la ultima posicion conocida del emisor; el anchor BOTTOM
        // de la anotacion apoya este extremo justo en la coordenada.
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(RojoSuave, CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun StatusPanel(uiState: MapUiState, modifier: Modifier = Modifier) {
    CardSurface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bateria del telefono, en rojo por debajo del 15 por ciento.
            StatusItem(
                icon = batteryIcon(uiState.batteryPercent),
                text = "${uiState.batteryPercent}%",
                tint = if (uiState.batteryPercent <= 15) RojoSuave else TextoPrincipal,
            )
            StatusItem(
                icon = Icons.Filled.SatelliteAlt,
                text = "${uiState.satellites}",
                tint = TextoPrincipal,
            )
            // Agentes en el canal; verde cuando la red tactica esta conectada.
            StatusItem(
                icon = Icons.Filled.Groups,
                text = "${uiState.connectedUsers}",
                tint = if (uiState.connectedUsers > 0) VerdeOk else TextoTerciario,
            )
            if (uiState.gpsReady) {
                Text(
                    text = "%.5f, %.5f".format(uiState.latitude, uiState.longitude),
                    color = VerdeOk,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = "Waiting for GPS…",
                    color = TextoTerciario,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusItem(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Glifo de bateria acorde al porcentaje real, como en la app Flutter. */
private fun batteryIcon(percent: Int): ImageVector = when {
    percent >= 95 -> Icons.Filled.BatteryFull
    percent >= 65 -> Icons.Filled.Battery6Bar
    percent >= 35 -> Icons.Filled.Battery4Bar
    percent >= 10 -> Icons.Filled.Battery2Bar
    else -> Icons.Filled.Battery0Bar
}
