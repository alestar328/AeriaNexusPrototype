package com.delta.aeria_nexus_prototype.feature.lock

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.ProvisionedIdentity
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AmarilloAviso
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Pantalla de bloqueo del terminal.
 *
 * No usa AppScaffold a proposito. La barra superior de la app lleva el estado de
 * la bodycam, las gafas y el indicador de grabacion, y la inferior lleva la
 * navegacion: todo eso es informacion operativa, y antes de autenticarse no debe
 * verse nada de eso. Quien encuentre el telefono solo ve a quien pertenece y que
 * hace falta un PIN.
 */
@Composable
fun LockScreen(viewModel: LockViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LockContent(
        uiState = uiState,
        onDigito = viewModel::escribirDigito,
        onBorrar = viewModel::borrarDigito,
    )
}

@Composable
private fun LockContent(
    uiState: LockUiState,
    onDigito: (Char) -> Unit,
    onBorrar: () -> Unit,
) {
    val bloqueado = uiState.lockoutSeconds > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoBase)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Teclado y puntos van fuera del scroll y la identidad dentro: en un movil
        // corto lo que tiene que ceder es la ficha, nunca las teclas. Sin esto el
        // contenido cabe justo en un 19,5:9 y se corta en un 16:9.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Cabecera(sessionExpired = uiState.sessionExpired)
            Spacer(Modifier.height(20.dp))
            uiState.identity?.let { TarjetaIdentidad(it) }
        }

        Spacer(Modifier.height(20.dp))
        PinDots(longitud = uiState.pin.length, enFallo = uiState.mensajeError != null)
        Spacer(Modifier.height(16.dp))
        Aviso(uiState = uiState, bloqueado = bloqueado)

        Spacer(Modifier.height(16.dp))
        Teclado(habilitado = !bloqueado, onDigito = onDigito, onBorrar = onBorrar)
    }
}

@Composable
private fun Cabecera(sessionExpired: Boolean) {
    Text(
        text = "AERIA NEXUS",
        color = TextoTerciario,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 4.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = AzulClaro,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            // Se distingue el arranque en frio de la sesion cortada a media
            // jornada: no es lo mismo empezar el turno que perderlo a mitad.
            text = if (sessionExpired) "SESSION EXPIRED" else "LOCKED",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (sessionExpired) {
            "Your duty session ended. Enter your PIN to continue."
        } else {
            "Enter your PIN to start duty."
        },
        color = TextoSecundario,
        fontSize = 14.sp,
    )
}

/**
 * Quien es este telefono. El agente no teclea su usuario (workflow 27, paso 4),
 * asi que la unica forma de saber que el terminal es el suyo es leerlo aqui;
 * ademas son los identificadores que hay que dictar por radio si algo falla.
 */
@Composable
private fun TarjetaIdentidad(identity: ProvisionedIdentity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = "ENROLLED OFFICER",
            color = TextoTerciario,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = identity.userId,
            color = TextoPrincipal,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        FilaDato("TENANT", identity.tenant)
        FilaDato("DEVICE", identity.deviceId)
        FilaDato("INSTANCE", identity.appInstanceId)
        FilaDato("RELEASE", identity.release)
    }
}

@Composable
private fun FilaDato(etiqueta: String, valor: String) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = etiqueta,
            color = TextoTerciario,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = valor,
            color = TextoSecundario,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Un punto por digito. El PIN nunca se pinta, ni siquiera el ultimo caracter. */
@Composable
private fun PinDots(longitud: Int, enFallo: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(IdentityRepository.PIN_LENGTH) { indice ->
            val relleno = indice < longitud
            Box(
                Modifier
                    .size(16.dp)
                    // El relleno dice cuantos digitos hay y el borde dice si el
                    // intento anterior fallo. Son dos cosas distintas: pintar de
                    // rojo un punto vacio hace creer que el PIN sigue escrito.
                    .background(
                        color = when {
                            !relleno -> Color.Transparent
                            enFallo -> RojoSuave
                            else -> AzulClaro
                        },
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (enFallo) RojoSuave else TextoDeshabilitado,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * Linea de aviso bajo los puntos: error, intentos restantes o cuenta atras.
 * Reserva altura fija para que el teclado no salte cuando aparece el mensaje.
 */
@Composable
private fun Aviso(uiState: LockUiState, bloqueado: Boolean) {
    Column(
        modifier = Modifier.heightIn(min = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            bloqueado -> {
                Text(
                    text = "TOO MANY ATTEMPTS",
                    color = AmarilloAviso,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Try again in ${formatearCuentaAtras(uiState.lockoutSeconds)}",
                    color = TextoSecundario,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            uiState.mensajeError != null -> {
                Text(
                    text = uiState.mensajeError,
                    color = RojoSuave,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Se avisa solo cuando queda poco: contar los intentos desde
                    // el primer fallo pone nervioso sin necesidad.
                    text = if (uiState.attemptsLeft <= 2) {
                        "${uiState.attemptsLeft} attempts left before temporary lockout"
                    } else {
                        "Check the PIN and try again"
                    },
                    color = TextoSecundario,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun Teclado(habilitado: Boolean, onDigito: (Char) -> Unit, onBorrar: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FILAS_TECLADO.forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                fila.forEach { tecla ->
                    when (tecla) {
                        TECLA_VACIA -> Spacer(Modifier.weight(1f))
                        TECLA_BORRAR -> TeclaBorrar(
                            habilitado = habilitado,
                            onClick = onBorrar,
                            modifier = Modifier.weight(1f),
                        )
                        else -> TeclaDigito(
                            digito = tecla,
                            habilitado = habilitado,
                            onClick = { onDigito(tecla) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TeclaDigito(
    digito: Char,
    habilitado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaseTecla(habilitado = habilitado, onClick = onClick, modifier = modifier) {
        Text(
            text = digito.toString(),
            color = if (habilitado) TextoPrincipal else TextoDeshabilitado,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TeclaBorrar(habilitado: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BaseTecla(habilitado = habilitado, onClick = onClick, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Delete last digit",
            tint = if (habilitado) TextoSecundario else TextoDeshabilitado,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Tecla de 68.dp: se usa con guantes y sin mirar la pantalla. */
@Composable
private fun BaseTecla(
    habilitado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 68.dp)
            .background(Superficie, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(12.dp))
            .clickable(enabled = habilitado, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

private fun formatearCuentaAtras(segundos: Int): String =
    "%d:%02d".format(segundos / 60, segundos % 60)

private const val TECLA_VACIA = ' '
private const val TECLA_BORRAR = '<'

private val FILAS_TECLADO = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf(TECLA_VACIA, '0', TECLA_BORRAR),
)

private val IDENTIDAD_PREVIEW = ProvisionedIdentity(
    userId = "cmendez.aeriaone.com",
    tenant = "QPD",
    deviceId = "DEV-92A71C",
    appInstanceId = "APPINST-8F27A91C",
    release = "1.5",
)

@Preview(name = "Bloqueada", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun LockScreenPreview() {
    AeriaNexusPrototypeTheme {
        LockContent(
            uiState = LockUiState(identity = IDENTIDAD_PREVIEW, pin = "004"),
            onDigito = {},
            onBorrar = {},
        )
    }
}

@Preview(name = "PIN erroneo", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun LockScreenErrorPreview() {
    AeriaNexusPrototypeTheme {
        LockContent(
            uiState = LockUiState(
                identity = IDENTIDAD_PREVIEW,
                attemptsLeft = 2,
                mensajeError = "Wrong PIN",
            ),
            onDigito = {},
            onBorrar = {},
        )
    }
}

@Preview(name = "Bloqueo temporal", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun LockScreenLockoutPreview() {
    AeriaNexusPrototypeTheme {
        LockContent(
            uiState = LockUiState(identity = IDENTIDAD_PREVIEW, lockoutSeconds = 272),
            onDigito = {},
            onBorrar = {},
        )
    }
}
