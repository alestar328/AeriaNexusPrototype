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

/** Que fuente de camara muestra la pantalla del visor remoto. */
enum class ViewfinderMode {
    // Foto a distancia: el telefono enciende el visor con PREVIEW_START.
    PHOTO,
    // Monitor de grabacion: la bodycam ya graba y sirve sus frames sola.
    RECORDING,
}

data class BodycamViewfinderUiState(
    // Ultimo frame recibido del visor; null mientras conecta.
    val frame: Bitmap? = null,
    val previewActive: Boolean = false,
    // True mientras la bodycam siga grabando (solo importa en modo RECORDING).
    val recordingActive: Boolean = true,
    val mensajeError: String? = null,
    // Confirmacion breve de la foto capturada; se borra sola.
    val commandFeedback: String? = null,
)

/**
 * Visor remoto de la camara de la bodycam, con dos modos:
 * - PHOTO: la bodycam abre su camara con PREVIEW_START y el comando PHOTO
 *   dispara sobre esa misma camara (la foto sale con el encuadre del visor).
 * - RECORDING: la bodycam ya esta grabando y sirve los frames de esa misma
 *   grabacion; aqui no se envia ningun comando de visor, solo se mira.
 * En ambos los frames llegan como MJPEG por el servidor HTTP WiFi de la W1.
 */
class BodycamViewfinderViewModel(
    private val bodycamRepository: BodycamRepository,
    val mode: ViewfinderMode,
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
            bodycamRepository.isRecording.collect { grabando ->
                _uiState.update { it.copy(recordingActive = grabando) }
            }
        }
        // Las respuestas de comandos solo importan en modo foto; el monitor
        // de grabacion no envia comandos y no debe pintar errores ajenos.
        if (mode == ViewfinderMode.PHOTO) {
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
        }
        viewModelScope.launch { frameLoop() }
        if (mode == ViewfinderMode.PHOTO) startPreview()
    }

    /** Pide (o reintenta) el visor; la bodycam responde OK o ERROR. */
    fun startPreview() {
        _uiState.update { it.copy(mensajeError = null) }
        bodycamRepository.sendCommand("PREVIEW_START")
    }

    fun capturePhoto() = bodycamRepository.sendCommand("PHOTO")

    /** Detiene la grabacion desde el monitor; al confirmarse se cierra solo. */
    fun stopRecording() = bodycamRepository.sendCommand("REC_STOP")

    /**
     * Bucle del visor: colecta el stream MJPEG y pinta cada frame. Cuando el
     * stream se corta (microcorte BT o WiFi), se reengancha sin intervencion
     * del agente; en modo foto ademas se reenvia PREVIEW_START por si la
     * bodycam apago su visor. La grabacion no necesita comando: sigue sola.
     */
    private suspend fun frameLoop() {
        while (true) {
            val fuenteViva = when (mode) {
                ViewfinderMode.PHOTO -> _uiState.value.previewActive
                ViewfinderMode.RECORDING -> _uiState.value.recordingActive
            }
            if (fuenteViva) {
                // Cada fuente llega con su orientacion; ver las constantes
                // de rotacion ajustadas en campo en BodycamRepository.
                val rotacion = when (mode) {
                    ViewfinderMode.PHOTO -> BodycamRepository.PREVIEW_ROTATION_DEGREES
                    ViewfinderMode.RECORDING -> BodycamRepository.MONITOR_ROTATION_DEGREES
                }
                bodycamRepository.streamPreviewFrames(rotacion).collect { frame ->
                    _uiState.update { it.copy(frame = frame) }
                }
                if (mode == ViewfinderMode.PHOTO) {
                    bodycamRepository.sendCommand("PREVIEW_START")
                }
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
        // El visor de foto mantiene abierta la camara de la bodycam y gasta su
        // bateria: se apaga siempre al salir de la pantalla. El monitor de
        // grabacion NO envia nada: salir de mirar no detiene la evidencia.
        if (mode == ViewfinderMode.PHOTO) {
            bodycamRepository.sendCommand("PREVIEW_STOP")
        }
    }

    companion object {
        // Pausa entre reintentos de stream, para no martillear la red ni el
        // enlace BT cuando el visor no esta disponible.
        private const val RETRY_STREAM_MILLIS = 1_000L
        private const val FEEDBACK_MILLIS = 2_500L
    }
}
