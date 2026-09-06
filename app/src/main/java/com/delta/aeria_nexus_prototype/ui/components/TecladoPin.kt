package com.delta.aeria_nexus_prototype.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.ui.theme.AzulClaro
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.VerdeOk

/**
 * Teclado numerico y puntos del PIN.
 *
 * Salen de la pantalla de bloqueo porque los usan dos: esa y la de crear el PIN
 * en el alta (workflow 4). Tienen que ser el mismo teclado, y no solo por no
 * duplicar codigo: el agente teclea aqui el secreto que va a usar cada dia, y si
 * la pantalla donde lo elige tuviera otras teclas se equivocaria al crearlo.
 */
@Composable
fun PinDots(
    longitud: Int,
    enFallo: Boolean = false,
    confirmado: Boolean = false,
) {
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
                            confirmado -> VerdeOk
                            else -> AzulClaro
                        },
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = when {
                            enFallo -> RojoSuave
                            confirmado -> VerdeOk
                            else -> TextoDeshabilitado
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
fun TecladoPin(habilitado: Boolean, onDigito: (Char) -> Unit, onBorrar: () -> Unit) {
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

private const val TECLA_VACIA = ' '
private const val TECLA_BORRAR = '<'

private val FILAS_TECLADO = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf(TECLA_VACIA, '0', TECLA_BORRAR),
)
