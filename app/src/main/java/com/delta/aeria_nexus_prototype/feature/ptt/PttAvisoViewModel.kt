package com.delta.aeria_nexus_prototype.feature.ptt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PttAvisoUiState(
    // Un companero tiene el PTT abierto y su voz suena en el canal.
    val hablando: Boolean = false,
    // Texto de la banda. Generico mientras no se pueda identificar al oficial.
    val titulo: String = TITULO_GENERICO,
) {
    companion object {
        const val TITULO_GENERICO = "Un oficial esta comunicando"
    }
}

/**
 * Avisa de que otra unidad esta hablando por el PTT, venga de la bodycam de un
 * companero o de su telefono. Vive por encima de la navegacion, como el SOS, para
 * que se vea en cualquier pantalla.
 *
 * Los dos origenes NO se detectan igual, y por eso solo uno tiene nombre:
 *
 * - **Bodycam**: se deduce de su audio remoto (uid fijo 9001). El aviso es
 *   GENERICO a proposito, no por falta de diseno: todas las bodycams comparten ese
 *   uid, asi que el canal no permite saber cual habla. Cuando haya autenticacion y
 *   usuarios reales, el nombre entra por [AgoraRepository.oficialHablando] sin
 *   tocar nada mas de este fichero.
 * - **Telefono**: llega anunciado por el data stream con el numero de oficial
 *   dentro ([AgoraRepository.pttsRemotos]), asi que ese si se puede nombrar.
 */
class PttAvisoViewModel(
    private val agoraRepository: AgoraRepository,
    private val bodycamRepository: BodycamRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PttAvisoUiState())
    val uiState: StateFlow<PttAvisoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                agoraRepository.bodycamHablando,
                agoraRepository.oficialHablando,
                agoraRepository.pttsRemotos,
            ) { hablando, oficial, pttsDeTelefono ->
                Triple(hablando, oficial, pttsDeTelefono)
            }
                .collect { (hablando, oficial, pttsDeTelefono) ->
                    // El agente que acaba de pulsar el PTT no se avisa a si mismo:
                    // su propia camara suena en el canal como la de cualquier otro.
                    // Mismo criterio que el SOS en SosAlertViewModel. Con el PTT del
                    // propio telefono no hace falta filtro: Agora nunca devuelve al
                    // emisor su propio anuncio del data stream.
                    val esBodycamPropia = bodycamRepository.isConnected
                    val oficialDeTelefono = pttsDeTelefono.values.firstOrNull()
                    _uiState.value = PttAvisoUiState(
                        hablando = (hablando && !esBodycamPropia) ||
                            pttsDeTelefono.isNotEmpty(),
                        titulo = (oficialDeTelefono ?: oficial)
                            ?.let { "$it esta comunicando" }
                            ?: PttAvisoUiState.TITULO_GENERICO,
                    )
                }
        }
    }
}
