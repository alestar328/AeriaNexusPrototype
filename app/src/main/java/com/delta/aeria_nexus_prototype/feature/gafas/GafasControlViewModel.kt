package com.delta.aeria_nexus_prototype.feature.gafas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.GafasCommandRepository
import com.delta.aeria_nexus_prototype.data.GafasControlState
import com.delta.aeria_nexus_prototype.data.GafasRepository
import com.delta.aeria_nexus_prototype.data.GafasState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GafasControlUiState(
    /** Enlace Bluetooth clasico, el mismo que pinta el indicador de la barra. */
    val enlace: GafasState = GafasState.DISCONNECTED,
    /** Canal de mando BLE, que solo existe mientras se esta en esta pantalla. */
    val control: GafasControlState = GafasControlState.DESCONECTADO,
    /** Ultima orden de grabacion dada, no lo que hacen las gafas de verdad. */
    val grabando: Boolean = false,
    /** Resolucion de video configurada, o null mientras no se sepa. */
    val resolucion: String? = null,
    /** Espacio libre en la tarjeta, ya formateado, o null mientras no se sepa. */
    val espacioLibre: String? = null,
    /** Respuesta corta de las gafas; se borra sola. */
    val aviso: String? = null,
)

/**
 * Controlador remoto de las gafas BleeqUp Ranger: grabacion y foto por BLE.
 *
 * Usa dos repositorios porque son dos enlaces distintos con el mismo aparato:
 * [GafasRepository] observa el enlace de audio que trae el sistema (y de paso
 * sabe si hay permiso de Bluetooth), y [GafasCommandRepository] abre y cierra el
 * GATT propietario por el que viajan las ordenes.
 */
class GafasControlViewModel(
    private val mando: GafasCommandRepository,
    private val presencia: GafasRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GafasControlUiState())
    val uiState: StateFlow<GafasControlUiState> = _uiState.asStateFlow()

    private var avisoJob: Job? = null

    init {
        viewModelScope.launch {
            presencia.state.collect { estado -> _uiState.update { it.copy(enlace = estado) } }
        }
        viewModelScope.launch {
            mando.estado.collect { estado -> _uiState.update { it.copy(control = estado) } }
        }
        viewModelScope.launch {
            mando.grabando.collect { grabando -> _uiState.update { it.copy(grabando = grabando) } }
        }
        viewModelScope.launch {
            mando.camara.collect { camara ->
                _uiState.update {
                    it.copy(
                        resolucion = if (camara.altoVideo > 0) {
                            "${camara.anchoVideo}x${camara.altoVideo}"
                        } else {
                            null
                        },
                        espacioLibre = formatearEspacio(camara.bytesLibres),
                    )
                }
            }
        }
        viewModelScope.launch {
            mando.mensajes.collect { mensaje -> mostrarAviso(mensaje) }
        }
    }

    fun conectar() = mando.conectar()

    fun desconectar() = mando.desconectar()

    fun alternarGrabacion() {
        if (_uiState.value.grabando) mando.pararGrabacion() else mando.iniciarGrabacion()
    }

    fun hacerFoto() = mando.hacerFoto()

    fun tienePermisoBluetooth(): Boolean = presencia.tienePermisoBluetooth()

    /** Tras conceder el permiso hay que releer: los avisos perdidos no vuelven. */
    fun alConcederBluetooth() {
        presencia.refrescar()
        mando.conectar()
    }

    /**
     * Cierra el GATT al salir de la pantalla.
     *
     * Las gafas siguen grabando si se les mando grabar: lo que se suelta es el
     * canal de mando, no la grabacion. Dejarlo abierto haria que la siguiente
     * conexion reusara un enlace cacheado y fallaran las escrituras.
     */
    override fun onCleared() {
        mando.desconectar()
    }

    private fun mostrarAviso(mensaje: String) {
        avisoJob?.cancel()
        _uiState.update { it.copy(aviso = mensaje) }
        avisoJob = viewModelScope.launch {
            delay(AVISO_MILLIS)
            _uiState.update { it.copy(aviso = null) }
        }
    }

    private fun formatearEspacio(bytes: Long): String? {
        if (bytes < 0) return null
        val gigas = bytes.toDouble() / (1024 * 1024 * 1024)
        return String.format(java.util.Locale.US, "%.1f GB", gigas)
    }

    private companion object {
        /** Lo que dura en pantalla la respuesta de las gafas. */
        const val AVISO_MILLIS = 3_000L
    }
}
