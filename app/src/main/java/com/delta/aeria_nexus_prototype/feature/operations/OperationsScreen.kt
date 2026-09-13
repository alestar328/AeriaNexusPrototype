package com.delta.aeria_nexus_prototype.feature.operations

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.R
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteFin
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteInicio
import com.delta.aeria_nexus_prototype.ui.theme.AzulGradienteMedio
import com.delta.aeria_nexus_prototype.ui.theme.AzulOscuroPanel
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla principal de operaciones: crear incidente, continuar el activo,
 * accesos de radio y llamada, y boton de emergencia.
 */
@Composable
fun OperationsScreen(
    viewModel: OperationsViewModel,
    onOpenActiveIncident: (String) -> Unit,
    onOpenSosLivestream: () -> Unit,
    onOpenBodycamControl: () -> Unit,
    onOpenLensControl: () -> Unit,
) {
    val activeIncident by viewModel.activeIncident.collectAsStateWithLifecycle()
    val sosActive by viewModel.sosActive.collectAsStateWithLifecycle()
    val pttActivo by viewModel.pttActivo.collectAsStateWithLifecycle()

    // El PTT necesita el microfono. Se pide al primer intento de hablar y esa
    // pulsacion se pierde a proposito: mientras el dialogo esta delante no hay
    // captura, y abrir el canal al conceder el permiso dejaria el microfono
    // abierto sin que nadie mantenga el boton.
    val pttPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    // Salir de la pantalla con el boton pulsado (una notificacion, el boton atras)
    // no genera evento de soltar: sin esto el microfono se quedaria abierto.
    DisposableEffect(Unit) {
        onDispose { viewModel.terminarPtt() }
    }

    // El livestream necesita camara y microfono. Se piden al tocar EMERGENCY y
    // el SOS se emite aunque se nieguen: la alerta llega igual, solo sin video.
    val sosPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.activateSos()
        onOpenSosLivestream()
    }

    // Los indicadores de bodycam y gafas de la barra superior no valen nada sin
    // el permiso de Bluetooth: sin el, Android no entrega los avisos de conexion
    // y los iconos se quedan en gris con los aparatos puestos y funcionando. Se
    // pide aqui, al empezar el turno, y no solo en BODYCAM CONTROL, porque un
    // agente que nunca entre en esa pantalla tendria un indicador que le miente.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.alConcederBluetooth() }

    // Una sola vez por sesion: pedirlo en cada vuelta a Operations le pondria el
    // dialogo delante una y otra vez a quien ya haya dicho que no.
    var yaSePidioBluetooth by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!yaSePidioBluetooth && !viewModel.tienePermisoBluetooth()) {
            yaSePidioBluetooth = true
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BrandHeader()

        // Las dos tarjetas grandes se reparten con weight el alto que
        // realmente queda en este telefono, en lugar de usar alturas
        // fijas que desbordaban en pantallas bajas. Por debajo del
        // umbral se compactan ademas los contenidos internos.
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val compacto = maxHeight < 500.dp
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                NewIncidentButton(
                    compacto = compacto,
                    onClick = { onOpenActiveIncident(viewModel.createIncident()) },
                    modifier = Modifier.weight(1f),
                )
                ContinueIncidentButton(
                    compacto = compacto,
                    incidentId = activeIncident?.id,
                    startedAtMillis = activeIncident?.startedAtMillis,
                    onClick = { activeIncident?.let { onOpenActiveIncident(it.id) } },
                    modifier = Modifier.weight(1f),
                )
                // La llamada a central se queda fuera de momento: era
                // decorativa (no hay telefonia en el prototipo) y ocupaba
                // media fila. Se recupera cuando exista central de verdad.
                // RadioActionButton(
                //     icon = Icons.Filled.Phone,
                //     description = "Call dispatch",
                //     compacto = compacto,
                //     modifier = Modifier.weight(1f),
                // )
            }
        }

        // El PTT va a lo ancho y pegado al SOS, con el mismo peso visual: son
        // las dos cosas que el agente puede necesitar pulsar sin mirar.
        Spacer(Modifier.height(10.dp))
        PttButton(
            activo = pttActivo,
            onPress = {
                if (viewModel.tienePermisoMicrofono()) {
                    viewModel.iniciarPtt()
                } else {
                    pttPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onRelease = { viewModel.terminarPtt() },
        )
        Spacer(Modifier.height(10.dp))
        EmergencyButton(
            sosActive = sosActive,
            onClick = {
                if (sosActive) {
                    viewModel.cancelSos()
                } else {
                    sosPermissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                }
            },
        )
    }

}

@Composable
private fun BrandHeader() {
    Text(
        text = "AERIA NEXUS",
        color = TextoTerciario,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 3.sp,
    )
    Text(
        text = "OPERATIONS",
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun NewIncidentButton(
    compacto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(AzulGradienteInicio, AzulGradienteMedio, AzulGradienteFin),
                ),
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (compacto) 40.dp else 56.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (compacto) 26.dp else 36.dp),
            )
        }
        Spacer(Modifier.height(if (compacto) 6.dp else 12.dp))
        Text(
            text = "NEW INCIDENT",
            color = Color.White,
            fontSize = if (compacto) 15.sp else 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun ContinueIncidentButton(
    compacto: Boolean,
    incidentId: String?,
    startedAtMillis: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hayActivo = incidentId != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (hayActivo) 1f else 0.5f)
            .clip(RoundedCornerShape(24.dp))
            .background(Superficie)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            .clickable(enabled = hayActivo, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // En compacto el icono se omite: esta tarjeta lleva hasta tres lineas
        // de texto y con el icono no cabe en el alto que le toca por weight.
        if (!compacto) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ListAlt,
                    contentDescription = null,
                    tint = TextoSecundario,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = "CONTINUE ACTIVE INCIDENT",
            color = TextoTerciario,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        if (incidentId != null && startedAtMillis != null) {
            Text(
                text = incidentId,
                color = AzulClaro,
                fontSize = if (compacto) 16.sp else 18.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Started " + formatStartTime(startedAtMillis),
                color = TextoTerciario,
                fontSize = 11.sp,
            )
        } else {
            Text(
                text = "No active incident",
                color = TextoDeshabilitado,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RadioActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    compacto: Boolean,
    modifier: Modifier = Modifier,
) {
    // Altura propia en lugar de aspectRatio: atada al ancho, la fila crecia
    // hasta ~120.dp y era una de las causas del apinamiento vertical.
    Box(
        modifier = modifier
            .heightIn(min = if (compacto) 64.dp else 96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AzulOscuroPanel)
            .border(1.dp, AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (compacto) 48.dp else 64.dp)
                .background(AzulPrimario.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .border(1.dp, AzulClaro.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = AzulClaro,
                modifier = Modifier.size(if (compacto) 28.dp else 36.dp),
            )
        }
    }
}

/**
 * PTT del propio telefono: mantener pulsado para hablar, soltar para cerrar.
 *
 * Aqui SI es mantener-para-hablar, al reves que en la bodycam, donde el firmware
 * de la W1 solo avisa al SOLTAR la tecla F2 y obliga a un conmutador. En una
 * pantalla no existe esa limitacion, y mantener es lo que evita el fallo clasico
 * de la radio: dejarse el microfono abierto sin darse cuenta.
 *
 * El indicador se enciende con lo que el repositorio confirma haber abierto, no
 * con la pulsacion: un boton que dice ON AIR sin que salga voz es peor que uno
 * que no responde, porque el agente cree que le estan oyendo.
 */
@Composable
private fun PttButton(
    activo: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    // Late mientras se transmite, como el SOS: tiene que verse de reojo.
    val latido by rememberInfiniteTransition(label = "pttLatido").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pttLatido",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (activo) AzulPrimario.copy(alpha = 0.35f) else AzulOscuroPanel)
            .border(
                1.dp,
                if (activo) AzulClaro else AzulPrimario.copy(alpha = 0.2f),
                RoundedCornerShape(16.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        // tryAwaitRelease vuelve tanto al soltar como al cancelarse
                        // el gesto (el dedo se sale del boton, un scroll lo roba):
                        // en los dos casos hay que cerrar el microfono.
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Push to talk",
                tint = AzulClaro,
                modifier = Modifier
                    .size(26.dp)
                    .alpha(if (activo) latido else 1f),
            )
            // El texto esta siempre puesto, tambien en reposo: si apareciera solo
            // al transmitir, el boton cambiaria de ancho en mitad de la pulsacion.
            Text(
                text = if (activo) "ON AIR — RELEASE TO STOP" else "PTT — HOLD TO TALK",
                color = if (activo) AzulClaro else TextoSecundario,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * Boton SOS de la red tactica. Un toque emite la alerta a todos los
 * dispositivos del canal; mientras esta activa, el boton parpadea y un
 * segundo toque la cancela en los receptores.
 */
@Composable
private fun EmergencyButton(sosActive: Boolean, onClick: () -> Unit) {
    // El parpadeo hace inconfundible que la emergencia propia sigue emitiendo.
    val parpadeo by rememberInfiniteTransition(label = "sosParpadeo").animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sosParpadeo",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .alpha(if (sosActive) parpadeo else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(RojoCritico, Color(0xFFDC2626))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (sosActive) "SOS ACTIVE — TAP TO CANCEL" else "EMERGENCY",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = if (sosActive) 1.sp else 3.sp,
        )
    }
}

private fun formatStartTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
