package com.delta.aeria_nexus_prototype.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// La app es siempre oscura por diseno: se usa de noche en patrullas y el tema
// claro no existe en el prototipo original. No se usa color dinamico para
// mantener la identidad visual en cualquier dispositivo.
private val EsquemaOscuro = darkColorScheme(
    primary = AzulPrimario,
    onPrimary = Color.White,
    secondary = CianFalconCore,
    onSecondary = Color.Black,
    background = FondoBase,
    onBackground = TextoPrincipal,
    surface = Superficie,
    onSurface = TextoPrincipal,
    surfaceVariant = SuperficiePresionada,
    onSurfaceVariant = TextoSecundario,
    outline = BordeSutil,
    outlineVariant = BordeMuySutil,
    error = RojoCritico,
    onError = Color.White,
)

@Composable
fun AeriaNexusPrototypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EsquemaOscuro,
        typography = Typography,
        content = content,
    )
}
