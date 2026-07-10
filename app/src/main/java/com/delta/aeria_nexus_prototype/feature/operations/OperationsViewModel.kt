package com.delta.aeria_nexus_prototype.feature.operations

import androidx.lifecycle.ViewModel
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import kotlinx.coroutines.flow.StateFlow

class OperationsViewModel(
    private val repositorio: IncidentRepository,
    private val agoraRepository: AgoraRepository,
) : ViewModel() {

    /** Incidente activo compartido; null cuando no hay ninguno en curso. */
    val activeIncident: StateFlow<ActiveIncident?> = repositorio.activeIncident

    /** True mientras este dispositivo tiene un SOS emitido sin cancelar. */
    val sosActive: StateFlow<Boolean> = agoraRepository.sosActive

    /** Crea un incidente nuevo y devuelve su id para navegar a el. */
    fun createIncident(): String = repositorio.startNewIncident()

    /** Emite el SOS: alerta a los demas dispositivos y publica la camara propia. */
    fun activateSos() {
        agoraRepository.activateSos(repositorio.officerProfile.officerNum)
    }

    /** Cancela el SOS propio y corta el livestream. */
    fun cancelSos() = agoraRepository.cancelSos()
}
