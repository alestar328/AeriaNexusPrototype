package com.delta.aeria_nexus_prototype.feature.bodycam

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BodycamViewfinderUiState(
    // Ultimo frame recibido del visor; null mientras conecta.
    val frame: Bitmap? = null,
    val previewActive: Boolean = false,
    val mensajeError: String? = null,
    // Confirmacion breve de la foto capturada; se borra sola.
    val commandFeedback: String? = null,
)

/**
 * Visor remoto para la foto a distancia: la bodycam abre su camara con
 * PREVIEW_START y sirve frames JPEG por su servidor HTTP WiFi; este ViewModel
 * los pide en bucle mientras la pantalla este abierta. El comando PHOTO
 * dispara sobre esa misma camara, asi la foto sale con el encuadre del visor.
 */
class BodycamViewfinderViewModel(
    private val bodycamRepository: BodycamRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BodycamViewfinderUiState())
    val uiState: StateFlow<BodycamViewfinderUiState> = _uiState.asStateFlow()

    private var feedbackJob: Job? = null

    init {
        viewModelScope.launch {
            bodycamRepository.isPreviewing.collect { activo ->
                _uiState.update { it.copy(previewActive = activo) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.commandResponses.collect { respuesta ->
                when {
                    respuesta == "OK:PREVIEW_START" ->
                        _uiState.update { it.copy(mensajeError = null) }
                    respuesta == "OK:PHOTO" -> showFeedback("Photo captured on bodycam")
                    respuesta.startsWith("ERROR:") ->
                        _uiState.update { it.copy(mensajeError = respuesta.removePrefix("ERROR:")) }
                }
            }
        }
        viewModelScope.launch { frameLoop() }
        startPreview()
    }

    /** Pide (o reintenta) el visor; la bodycam responde OK o ERROR. */
    fun startPreview() {
        _uiState.update { it.copy(mensajeError = null) }
        bodycamRepository.sendCommand("PREVIEW_START")
    }

    fun capturePhoto() = bodycamRepository.sendCommand("PHOTO")

    /**
     * Bucle del visor: colecta el stream MJPEG y pinta cada frame. Cuando el
     * stream se corta (microcorte BT o WiFi, la bodycam apago su visor), se
     * reenvia PREVIEW_START y se reengancha sin intervencion del agente.
     */
    private suspend fun frameLoop() {
        while (true) {
            if (_uiState.value.previewActive) {
                bodycamRepository.streamPreviewFrames().collect { frame ->
                    _uiState.update { it.copy(frame = frame) }
                }
                bodycamRepository.sendCommand("PREVIEW_START")
            }
            delay(RETRY_STREAM_MILLIS)
        }
    }

    private fun showFeedback(mensaje: String) {
        feedbackJob?.cancel()
        _uiState.update { it.copy(commandFeedback = mensaje) }
        feedbackJob = viewModelScope.launch {
            delay(FEEDBACK_MILLIS)
            _uiState.update { it.copy(commandFeedback = null) }
        }
    }

    override fun onCleared() {
        // El visor mantiene abierta la camara de la bodycam y gasta su
        // bateria: se apaga siempre al salir de la pantalla.
        bodycamRepository.sendCommand("PREVIEW_STOP")
    }

    companion object {
        // Pausa entre reintentos de stream, para no martillear la red ni el
        // enlace BT cuando el visor no esta disponible.
        private const val RETRY_STREAM_MILLIS = 1_000L
        private const val FEEDBACK_MILLIS = 2_500L
    }
}
