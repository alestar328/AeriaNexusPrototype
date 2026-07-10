package com.delta.aeria_nexus_prototype.feature.sos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.model.SosAlert
import com.delta.aeria_nexus_prototype.data.model.SosCancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SosAlertUiState(
    // Alerta SOS de otro agente que se esta mostrando ahora mismo; null si ninguna.
    val activeAlert: SosAlert? = null,
    // Aviso de que el emisor corto la senal, con su ultima posicion conocida.
    val signalCut: SosCancel? = null,
)

/**
 * Alertas SOS entrantes de la red tactica. Vive por encima de la navegacion
 * para que la alerta aparezca en cualquier pantalla de la app.
 */
class SosAlertViewModel(
    private val agoraRepository: AgoraRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SosAlertUiState())
    val uiState: StateFlow<SosAlertUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            agoraRepository.incomingSos.collect { alerta ->
                // Si ya hay una alerta en pantalla se conserva la primera; el
                // heartbeat del emisor ya viene filtrado por el repositorio.
                _uiState.update {
                    if (it.activeAlert == null) it.copy(activeAlert = alerta, signalCut = null) else it
                }
            }
        }
        viewModelScope.launch {
            agoraRepository.incomingSosCancel.collect { cancelacion ->
                // Solo interesa la cancelacion del SOS que se esta mostrando.
                _uiState.update {
                    if (it.activeAlert?.uid == cancelacion.uid) {
                        it.copy(activeAlert = null, signalCut = cancelacion)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Cierra la alerta activa (tanto al aceptarla como al descartarla). */
    fun dismissAlert() {
        _uiState.update { it.copy(activeAlert = null) }
    }

    /** Cierra el aviso de senal cortada. */
    fun dismissSignalCut() {
        _uiState.update { it.copy(signalCut = null) }
    }
}
