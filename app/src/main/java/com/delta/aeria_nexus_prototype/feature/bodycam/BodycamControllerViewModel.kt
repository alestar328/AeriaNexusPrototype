package com.delta.aeria_nexus_prototype.feature.bodycam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.data.BuscadorBodycam
import com.delta.aeria_nexus_prototype.data.DispositivoCercano
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BodycamControllerUiState(
    val connectionState: BodycamState = BodycamState.DISCONNECTED,
    val batteryPercent: Int = 0,
    val isRecording: Boolean = false,
    val isStreaming: Boolean = false,
    // True cuando el livestream pedido desde esta pantalla ya arranco y hay
    // que abrir el visor; la pantalla lo consume con onViewerOpened().
    val openViewer: Boolean = false,
    // Mensaje corto de confirmacion o error de un comando; se borra solo.
    val commandFeedback: String? = null,
    // Bodycam con la que conecta este telefono; null hasta que el agente elige una.
    val bodycamElegida: DispositivoCercano? = null,
    val mostrarSelector: Boolean = false,
    val buscando: Boolean = false,
    val cercanas: List<DispositivoCercano> = emptyList(),
)

/**
 * Controlador remoto de la bodycam W1 por Bluetooth. El livestream desde aqui
 * tiene la misma semantica que el boton fisico SOS de la bodycam: al publicar
 * video con su uid de bodycam, todos los telefonos del canal reciben la emergencia.
 * Foto y grabacion son locales en la bodycam y no disparan ningun SOS.
 */
class BodycamControllerViewModel(
    private val bodycamRepository: BodycamRepository,
    private val buscadorBodycam: BuscadorBodycam,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BodycamControllerUiState(connectionState = bodycamRepository.state.value),
    )
    val uiState: StateFlow<BodycamControllerUiState> = _uiState.asStateFlow()

    // Solo el livestream pedido con el boton de esta pantalla abre el visor
    // automaticamente; si lo enciende el boton fisico, el agente decide.
    private var livestreamRequested = false

    private var feedbackJob: Job? = null
    private var busquedaJob: Job? = null

    init {
        viewModelScope.launch {
            bodycamRepository.bodycamElegida.collect { elegida ->
                _uiState.update { it.copy(bodycamElegida = elegida) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.state.collect { estado ->
                _uiState.update { it.copy(connectionState = estado) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.batteryPercent.collect { bateria ->
                _uiState.update { it.copy(batteryPercent = bateria) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.isRecording.collect { grabando ->
                _uiState.update { it.copy(isRecording = grabando) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.isStreaming.collect { transmitiendo ->
                if (transmitiendo && livestreamRequested) {
                    livestreamRequested = false
                    _uiState.update { it.copy(isStreaming = true, openViewer = true) }
                } else {
                    _uiState.update { it.copy(isStreaming = transmitiendo) }
                }
            }
        }
        viewModelScope.launch {
            bodycamRepository.commandResponses.collect { respuesta ->
                when {
                    respuesta == "OK:PHOTO" -> showFeedback("Photo captured on bodycam")
                    respuesta.startsWith("ERROR:") -> {
                        // Un error (por ejemplo bodycam sin WiFi al pedir el
                        // stream) anula la apertura automatica del visor.
                        livestreamRequested = false
                        showFeedback("Bodycam: ${respuesta.removePrefix("ERROR:")}")
                    }
                }
            }
        }
    }

    fun connect() = bodycamRepository.connect()

    fun disconnect() = bodycamRepository.disconnect()

    fun hasBluetoothPermission(): Boolean = bodycamRepository.hasBluetoothPermission()

    fun permisosDeBusqueda(): Array<String> = buscadorBodycam.permisosNecesarios()

    fun tienePermisosDeBusqueda(): Boolean = buscadorBodycam.tienePermisos()

    fun abrirSelector() {
        _uiState.update { it.copy(mostrarSelector = true) }
        buscar()
    }

    fun buscar() {
        // Los reintentos de conexion llaman a cancelDiscovery() y matarian la
        // busqueda a medias; ademas quien busca otra camara ya no quiere la actual.
        bodycamRepository.disconnect()
        val anterior = busquedaJob
        busquedaJob = viewModelScope.launch {
            // Hay que esperar a que la busqueda anterior acabe de cerrarse: su
            // cancelDiscovery() final cortaria la nueva recien arrancada.
            anterior?.cancelAndJoin()
            _uiState.update { it.copy(buscando = true, cercanas = emptyList()) }
            try {
                buscadorBodycam.buscar().collect { lista ->
                    _uiState.update { it.copy(cercanas = lista) }
                }
            } finally {
                _uiState.update { it.copy(buscando = false) }
            }
        }
    }

    fun elegirBodycam(dispositivo: DispositivoCercano) {
        cerrarSelector()
        bodycamRepository.elegirBodycam(dispositivo)
        bodycamRepository.connect()
    }

    fun cerrarSelector() {
        busquedaJob?.cancel()
        _uiState.update { it.copy(mostrarSelector = false) }
    }

    fun toggleLivestream() {
        if (_uiState.value.isStreaming) {
            bodycamRepository.sendCommand("STREAM_STOP")
        } else {
            livestreamRequested = true
            bodycamRepository.sendCommand("STREAM_START")
        }
    }

    fun toggleRecording() {
        val comando = if (_uiState.value.isRecording) "REC_STOP" else "REC_START"
        bodycamRepository.sendCommand(comando)
    }

    fun takePhoto() = bodycamRepository.sendCommand("PHOTO")

    /** La pantalla ya navego al visor del livestream; se apaga la orden. */
    fun onViewerOpened() {
        _uiState.update { it.copy(openViewer = false) }
    }

    private fun showFeedback(mensaje: String) {
        feedbackJob?.cancel()
        _uiState.update { it.copy(commandFeedback = mensaje) }
        feedbackJob = viewModelScope.launch {
            delay(FEEDBACK_MILLIS)
            _uiState.update { it.copy(commandFeedback = null) }
        }
    }

    companion object {
        private const val FEEDBACK_MILLIS = 2_500L
    }
}
