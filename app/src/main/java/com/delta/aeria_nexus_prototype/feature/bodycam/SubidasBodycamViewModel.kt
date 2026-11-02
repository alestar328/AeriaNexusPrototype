package com.delta.aeria_nexus_prototype.feature.bodycam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import com.delta.aeria_nexus_prototype.data.BodycamState
import com.delta.aeria_nexus_prototype.data.SubidaDeEvidencia
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubidasUiState(
    val conectada: Boolean = false,
    val subidas: List<SubidaDeEvidencia> = emptyList(),
    val cargando: Boolean = false,
    // Incidente cuya cancelacion espera confirmacion del agente.
    val confirmando: String? = null,
    val feedback: String? = null,
)

/**
 * Subidas de evidencia de la bodycam, con el boton de cancelar.
 *
 * ## Por que existe esta pantalla
 *
 * La bodycam no se rinde nunca subiendo: reencola cada incidente sin entregar en
 * cada arranque y reintenta con espera creciente. Es lo que queremos para una
 * evidencia real en una furgoneta sin cobertura, y un bucle infinito para una
 * prueba vieja o un destino mal configurado. Cancelar es la puerta de salida, y el
 * telefono es el unico sitio con pantalla para enseñar la lista y elegir: la de la
 * unidad mide 3 cm.
 *
 * ## Cancelar no borra
 *
 * Corta la transferencia y saca el incidente de la cola. El video se queda en la
 * unidad y se puede devolver a la cola sin repetir un solo byte, porque el
 * servidor conserva los bloques que ya recibio. Por eso el dialogo de confirmacion
 * dice exactamente eso: si diera a entender que borra, nadie lo usaria.
 */
class SubidasBodycamViewModel(
    private val bodycamRepository: BodycamRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubidasUiState())
    val uiState: StateFlow<SubidasUiState> = _uiState.asStateFlow()

    private var feedbackJob: Job? = null

    init {
        viewModelScope.launch {
            bodycamRepository.state.collect { estado ->
                val conectada = estado == BodycamState.CONNECTED
                _uiState.update { it.copy(conectada = conectada) }
                // Al recuperar el enlace la lista que se esta enseñando es de antes
                // del corte: se vuelve a pedir en cuanto hay por donde preguntar.
                if (conectada) refrescar()
            }
        }
        viewModelScope.launch {
            bodycamRepository.subidas.collect { lista ->
                _uiState.update { it.copy(subidas = lista, cargando = false) }
            }
        }
        viewModelScope.launch {
            bodycamRepository.commandResponses.collect { respuesta ->
                when {
                    respuesta.startsWith("OK:UPLOAD_CANCEL") -> {
                        mostrar("Upload cancelled — video kept on the camera")
                        refrescar()
                    }
                    respuesta.startsWith("OK:UPLOAD_RESUME") -> {
                        mostrar("Upload queued again")
                        refrescar()
                    }
                    respuesta.startsWith("ERROR:") ->
                        mostrar("Bodycam: ${respuesta.removePrefix("ERROR:")}")
                }
            }
        }
    }

    fun refrescar() {
        if (!_uiState.value.conectada) return
        _uiState.update { it.copy(cargando = true) }
        bodycamRepository.pedirSubidas()
        // La lista llega por el mismo canal de texto que todo lo demas; si la
        // bodycam no contesta, el indicador no se puede quedar girando para siempre.
        viewModelScope.launch {
            delay(TIEMPO_DE_ESPERA_MILLIS)
            _uiState.update { if (it.cargando) it.copy(cargando = false) else it }
        }
    }

    /** Cancelar pide confirmacion: es una decision sobre evidencia, no un toggle. */
    fun pedirConfirmacion(incidentId: String) {
        _uiState.update { it.copy(confirmando = incidentId) }
    }

    fun cerrarConfirmacion() {
        _uiState.update { it.copy(confirmando = null) }
    }

    fun confirmarCancelacion() {
        val id = _uiState.value.confirmando ?: return
        cerrarConfirmacion()
        bodycamRepository.cancelarSubida(id)
    }

    fun reanudar(incidentId: String) = bodycamRepository.reanudarSubida(incidentId)

    private fun mostrar(mensaje: String) {
        feedbackJob?.cancel()
        _uiState.update { it.copy(feedback = mensaje) }
        feedbackJob = viewModelScope.launch {
            delay(FEEDBACK_MILLIS)
            _uiState.update { it.copy(feedback = null) }
        }
    }

    companion object {
        private const val FEEDBACK_MILLIS = 3_000L
        private const val TIEMPO_DE_ESPERA_MILLIS = 5_000L
    }
}
