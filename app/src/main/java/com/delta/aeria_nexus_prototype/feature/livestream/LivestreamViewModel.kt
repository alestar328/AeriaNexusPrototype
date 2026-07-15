package com.delta.aeria_nexus_prototype.feature.livestream

import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.OfficerSampleData
import com.delta.aeria_nexus_prototype.data.model.AgentIdCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LivestreamUiState(
    // Solo en modo emisor: true mientras el SOS propio sigue emitiendo.
    val sosActive: Boolean = false,
    // Solo en modo receptor: momento en que el emisor corto la senal.
    val signalCutAtMillis: Long? = null,
    // Ultima posicion conocida del emisor cuando corto, si se sabe.
    val signalCutLatitude: Double? = null,
    val signalCutLongitude: Double? = null,
)

/**
 * Livestream del SOS. Con [watchUid] 0 es el EMISOR: muestra la camara propia
 * que se esta publicando al canal. Con otro uid es RECEPTOR: reproduce en vivo
 * la camara del agente que emitio el SOS.
 */
class LivestreamViewModel(
    private val agoraRepository: AgoraRepository,
    val watchUid: Int,
) : ViewModel() {

    val isBroadcaster: Boolean = watchUid == OWN_CAMERA_UID

    // Ficha del agente que sale en pantalla: la propia al emitir, la del
    // emisor del SOS al mirar. Null si la placa no esta en el padron (por
    // ejemplo el stream de la bodycam, que no tiene agente asignado).
    val agent: AgentIdCard? = if (isBroadcaster) {
        OfficerSampleData.findAgent(OfficerSampleData.profile.officerNum)
    } else {
        agoraRepository.sosOfficer(watchUid)?.let(OfficerSampleData::findAgent)
    }

    // El estado inicial toma el valor real del SOS: si arrancara en false, la
    // pantalla del emisor se cerraria sola antes de recibir el primer collect.
    private val _uiState =
        MutableStateFlow(LivestreamUiState(sosActive = agoraRepository.sosActive.value))
    val uiState: StateFlow<LivestreamUiState> = _uiState.asStateFlow()

    init {
        if (isBroadcaster) {
            viewModelScope.launch {
                agoraRepository.sosActive.collect { activo ->
                    _uiState.update { it.copy(sosActive = activo) }
                }
            }
        } else {
            agoraRepository.startWatching(watchUid)
            viewModelScope.launch {
                agoraRepository.incomingSosCancel.collect { cancelacion ->
                    if (cancelacion.uid == watchUid) {
                        marcarSenalCortada(
                            millis = cancelacion.timestampMillis,
                            latitude = cancelacion.latitude,
                            longitude = cancelacion.longitude,
                        )
                    }
                }
            }
            viewModelScope.launch {
                agoraRepository.remoteVideoStopped.collect { uid ->
                    if (uid == watchUid) {
                        // El video murio sin mensaje de cancelacion (se quedo sin
                        // red, cerro la app...): la ultima posicion del mapa es
                        // el mejor dato disponible sobre donde estaba.
                        val agente = agoraRepository.remoteAgents.value[watchUid]
                        marcarSenalCortada(
                            millis = System.currentTimeMillis(),
                            latitude = agente?.latitude,
                            longitude = agente?.longitude,
                        )
                    }
                }
            }
        }
    }

    private fun marcarSenalCortada(millis: Long, latitude: Double?, longitude: Double?) {
        _uiState.update {
            // El primer corte gana: los eventos de cancelacion y de video caido
            // suelen llegar casi juntos y no deben pisarse entre si.
            if (it.signalCutAtMillis != null) it
            else it.copy(
                signalCutAtMillis = millis,
                signalCutLatitude = latitude,
                signalCutLongitude = longitude,
            )
        }
    }

    /** Conecta la vista de video segun el modo (camara propia o remota). */
    fun attachVideo(view: TextureView) {
        if (isBroadcaster) {
            agoraRepository.attachLocalVideo(view)
        } else {
            agoraRepository.attachRemoteVideo(view, watchUid)
        }
    }

    /** Cancela el SOS propio (solo tiene efecto en modo emisor). */
    fun cancelSos() = agoraRepository.cancelSos()

    override fun onCleared() {
        // Al salir de la pantalla el receptor deja de escuchar la voz del
        // emisor; el emisor NO corta nada: su SOS sigue hasta que lo cancele.
        if (!isBroadcaster) agoraRepository.stopWatching(watchUid)
    }

    companion object {
        const val OWN_CAMERA_UID = 0
    }
}
