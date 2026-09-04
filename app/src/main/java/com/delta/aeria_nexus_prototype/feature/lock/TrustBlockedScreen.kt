package com.delta.aeria_nexus_prototype.feature.lock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.data.identity.ProvisionedIdentity
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase
import com.delta.aeria_nexus_prototype.ui.theme.RojoCritico
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario
import com.delta.aeria_nexus_prototype.ui.theme.TextoTerciario

/**
 * Terminal fuera de servicio por un interruptor de corte del §13.
 *
 * Tres cosas que esta pantalla hace a proposito:
 *
 *  1. Dice QUE ha pasado con palabras, no con un codigo de error. El agente esta
 *     en la calle y no puede consultar a nadie mientras no sepa que preguntar.
 *  2. Dice QUE HACER. Siempre hay una salida escrita, aunque sea llamar al mando.
 *  3. Ensena los identificadores. Son los que hay que dictar por radio o telefono
 *     para que al otro lado sepan que terminal es; buscarlos en Ajustes no es una
 *     opcion cuando la app esta bloqueada.
 */
@Composable
fun TrustBlockedScreen(
    reason: TrustBlockReason,
    identity: ProvisionedIdentity?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FondoBase)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "AERIA NEXUS",
            color = TextoTerciario,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp,
        )

        Spacer(Modifier.weight(1f))

        // El estado nunca se comunica solo con color: icono, titulo y texto.
        Icon(
            Icons.Filled.Block,
            contentDescription = null,
            tint = RojoCritico,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = reason.title,
            color = RojoSuave,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = reason.message,
            color = TextoPrincipal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = reason.nextStep,
            color = TextoSecundario,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.weight(1f))

        identity?.let { TarjetaReferencia(it) }

        if (reason.canRetry) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AzulPrimario),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("TRY AGAIN", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

/** Identificadores que el agente tiene que poder leer en voz alta. */
@Composable
private fun TarjetaReferencia(identity: ProvisionedIdentity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, BordeSutil), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "QUOTE THIS WHEN YOU CALL",
            color = TextoTerciario,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        FilaReferencia("OFFICER", identity.userId)
        FilaReferencia("DEVICE", identity.deviceId)
        FilaReferencia("INSTANCE", identity.appInstanceId)
        FilaReferencia("RELEASE", identity.release)
    }
}

@Composable
private fun FilaReferencia(etiqueta: String, valor: String) {
    Row {
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
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private val IDENTIDAD_PREVIEW = ProvisionedIdentity(
    userId = "cmendez.aeriaone.com",
    tenant = "QPD",
    deviceId = "DEV-92A71C",
    appInstanceId = "APPINST-8F27A91C",
    release = "1.5",
)

@Preview(name = "Dispositivo revocado", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun TrustBlockedPreview() {
    AeriaNexusPrototypeTheme {
        TrustBlockedScreen(
            reason = TrustBlockReason.DEVICE_REVOKED,
            identity = IDENTIDAD_PREVIEW,
            onRetry = {},
        )
    }
}

@Preview(name = "Sin cobertura, margen agotado", showBackground = true, backgroundColor = 0xFF080B12, heightDp = 800)
@Composable
private fun TrustBlockedRetryPreview() {
    AeriaNexusPrototypeTheme {
        TrustBlockedScreen(
            reason = TrustBlockReason.OFFLINE_WINDOW_EXPIRED,
            identity = IDENTIDAD_PREVIEW,
            onRetry = {},
        )
    }
}
