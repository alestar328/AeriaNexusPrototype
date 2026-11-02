package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.delta.aeria_nexus_prototype.ui.theme.AzulPrimario
import com.delta.aeria_nexus_prototype.ui.theme.BordeSutil
import com.delta.aeria_nexus_prototype.ui.theme.RojoSuave
import com.delta.aeria_nexus_prototype.ui.theme.Superficie
import com.delta.aeria_nexus_prototype.ui.theme.TextoDeshabilitado
import com.delta.aeria_nexus_prototype.ui.theme.TextoPrincipal
import com.delta.aeria_nexus_prototype.ui.theme.TextoSecundario

/**
 * Lo unico que el agente teclea en el alta: su identificador de AeriaOne, entero.
 *
 * Se lo da su administrador al crear el perfil en el panel. El boton de continuar
 * es el de la pantalla, abajo; el teclado tambien lo dispara.
 */
@Composable
fun FormularioDelAgente(
    agenteId: String,
    error: String?,
    onAgenteIdChange: (String) -> Unit,
    onContinuar: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "OFFICER ID",
            color = TextoPrincipal,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This phone is enrolled. Type the full AeriaOne ID your administrator " +
                "gave you, exactly as shown on the panel.",
            color = TextoSecundario,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = agenteId,
            onValueChange = onAgenteIdChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("name.aeriaone.com", color = TextoDeshabilitado) },
            singleLine = true,
            isError = error != null,
            shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                // El corrector cambiaria el identificador por una palabra parecida.
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onContinuar() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Superficie,
                unfocusedContainerColor = Superficie,
                errorContainerColor = Superficie,
                focusedBorderColor = AzulPrimario.copy(alpha = 0.5f),
                unfocusedBorderColor = BordeSutil,
                errorBorderColor = RojoSuave,
                focusedTextColor = TextoPrincipal,
                unfocusedTextColor = TextoPrincipal,
                errorTextColor = TextoPrincipal,
            ),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = error, color = RojoSuave, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
