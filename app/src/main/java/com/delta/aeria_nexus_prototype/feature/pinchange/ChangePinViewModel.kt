package com.delta.aeria_nexus_prototype.feature.pinchange

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.CambioDePin
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.feature.enrollment.PoliticaPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Los tres pasos del cambio, y el final. */
enum class PasoCambioPin { ACTUAL, NUEVO, CONFIRMAR, HECHO }

data class ChangePinUiState(
    val paso: PasoCambioPin = PasoCambioPin.ACTUAL,
    val digitos: String = "",
    val error: String? = null,
    /** Mientras se deriva la clave el teclado no admite mas digitos. */
    val trabajando: Boolean = false,
)

/**
 * Cambio de PIN del agente (workflow 5).
 *
 * Tres pasos: el PIN actual, el nuevo y el nuevo otra vez. El actual se comprueba en
 * cuanto se teclea y no al final: equivocarse en el primer paso y enterarse despues
 * de haber elegido y repetido el nuevo es hacer teclear doce digitos para nada.
 *
 * Los PIN solo viven en este ViewModel mientras dura el cambio, igual que en el alta.
 */
class ChangePinViewModel(
    private val identity: IdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePinUiState())
    val uiState: StateFlow<ChangePinUiState> = _uiState.asStateFlow()

    private var pinActual: String? = null
    private var pinNuevo: String? = null

    fun escribirDigito(digito: Char) {
        val estado = _uiState.value
        if (estado.trabajando || estado.paso == PasoCambioPin.HECHO) return
        if (estado.digitos.length >= IdentityRepository.PIN_LENGTH) return

        val escrito = estado.digitos + digito
        _uiState.update { it.copy(digitos = escrito, error = null) }
        if (escrito.length == IdentityRepository.PIN_LENGTH) resolver(escrito)
    }

    fun borrarDigito() {
        if (_uiState.value.trabajando) return
        _uiState.update { it.copy(digitos = it.digitos.dropLast(1), error = null) }
    }

    /**
     * Comprobar y guardar derivan la clave con PBKDF2, que cuesta cientos de
     * milisegundos a proposito: va fuera del hilo de la interfaz. La pausa minima
     * es la misma del alta, para que se llegue a ver el sexto punto.
     */
    private fun resolver(escrito: String) {
        _uiState.update { it.copy(trabajando = true) }
        viewModelScope.launch {
            val inicio = System.currentTimeMillis()
            val siguiente = withContext(Dispatchers.Default) { siguientePaso(escrito) }
            val transcurrido = System.currentTimeMillis() - inicio
            if (transcurrido < MINIMO_VISIBLE_MILLIS) delay(MINIMO_VISIBLE_MILLIS - transcurrido)
            _uiState.update { siguiente.copy(trabajando = false) }
        }
    }

    private fun siguientePaso(escrito: String): ChangePinUiState = when (_uiState.value.paso) {
        PasoCambioPin.ACTUAL -> when (identity.comprobarPinActual(escrito)) {
            CambioDePin.OK -> {
                pinActual = escrito
                ChangePinUiState(paso = PasoCambioPin.NUEVO)
            }
            CambioDePin.PIN_ACTUAL_INCORRECTO -> ChangePinUiState(
                error = "Wrong PIN. ${identity.status.value.attemptsLeft} attempts left.",
            )
            // Con la tanda agotada la sesion ya se ha cerrado y la app vuelve a la
            // pantalla de bloqueo: este estado apenas llega a verse.
            else -> ChangePinUiState(error = "Too many attempts.")
        }

        PasoCambioPin.NUEVO -> {
            val motivo = PoliticaPin.motivoDeRechazo(escrito)
                ?: "Choose a PIN different from the current one.".takeIf { escrito == pinActual }
            if (motivo != null) {
                ChangePinUiState(paso = PasoCambioPin.NUEVO, error = motivo)
            } else {
                pinNuevo = escrito
                ChangePinUiState(paso = PasoCambioPin.CONFIRMAR)
            }
        }

        PasoCambioPin.CONFIRMAR -> {
            val nuevo = pinNuevo
            val actual = pinActual
            pinNuevo = null
            when {
                nuevo == null || actual == null -> ChangePinUiState(error = "Start again.")
                escrito != nuevo -> ChangePinUiState(
                    paso = PasoCambioPin.NUEVO,
                    error = "PINs did not match. Choose the new PIN again.",
                )
                else -> when (identity.cambiarPin(actual, nuevo)) {
                    CambioDePin.OK -> {
                        pinActual = null
                        ChangePinUiState(paso = PasoCambioPin.HECHO)
                    }
                    CambioDePin.NO_GUARDADO -> ChangePinUiState(
                        error = "Could not store the new PIN. Your current PIN still works.",
                    )
                    else -> ChangePinUiState(error = "Start again.")
                }
            }
        }

        PasoCambioPin.HECHO -> _uiState.value
    }

    override fun onCleared() {
        pinActual = null
        pinNuevo = null
    }

    private companion object {
        const val MINIMO_VISIBLE_MILLIS = 150L
    }
}
