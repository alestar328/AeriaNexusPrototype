package com.delta.aeria_nexus_prototype.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.BatteryRepository
import com.delta.aeria_nexus_prototype.data.LocationRepository
import com.delta.aeria_nexus_prototype.data.model.RemoteAgent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Marcador de otro agente listo para pintar en el mapa. */
data class RemoteAgentMarker(
    val uid: Int,
    val latitude: Double,
    val longitude: Double,
    // Sin senal: llevamos demasiado tiempo sin recibir mensajes de ese agente.
    val isStale: Boolean,
    // Hora del ultimo mensaje recibido, mostrada solo cuando no hay senal.
    val lastSeenLabel: String,
)

data class MapUiState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    // True desde el primer fix GPS; antes de eso no hay posicion valida.
    val gpsReady: Boolean = false,
    val satellites: Int = 0,
    val batteryPercent: Int = 0,
    val remoteAgents: List<RemoteAgentMarker> = emptyList(),
    // Agentes conectados al canal, incluido este telefono (0 = sin conexion).
    val connectedUsers: Int = 0,
)

/**
 * Estado del mapa tactico: posicion propia, satelites, bateria y, desde la
 * fase 2 del port de Falcon One, los agentes remotos de la red Agora.
 */
class MapViewModel(
    private val locationRepository: LocationRepository,
    private val batteryRepository: BatteryRepository,
    private val agoraRepository: AgoraRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var started = false

    init {
        // La bateria no requiere permisos: se observa desde el arranque.
        viewModelScope.launch {
            batteryRepository.batteryPercent().collect { porcentaje ->
                _uiState.update { it.copy(batteryPercent = porcentaje) }
            }
        }
        observarRedTactica()
    }

    /** Arranca los flujos de GPS. Llamar solo con el permiso ya concedido. */
    fun onLocationPermissionGranted() {
        // La red tactica empieza a compartir la posicion en cuanto hay permiso.
        agoraRepository.onLocationPermissionGranted()
        if (started) return
        started = true

        viewModelScope.launch {
            locationRepository.locationUpdates().collect { posicion ->
                _uiState.update {
                    it.copy(
                        latitude = posicion.latitude,
                        longitude = posicion.longitude,
                        gpsReady = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            locationRepository.satelliteCount().collect { cantidad ->
                _uiState.update { it.copy(satellites = cantidad) }
            }
        }
    }

    private fun observarRedTactica() {
        viewModelScope.launch {
            agoraRepository.connectedUsers.collect { usuarios ->
                _uiState.update { it.copy(connectedUsers = usuarios) }
            }
        }
        // El tick de un segundo re-evalua la antiguedad de cada marcador: un
        // agente que deja de emitir no genera ningun evento, y sin este pulso
        // se quedaria pintado como activo para siempre.
        val tick = flow {
            while (true) {
                emit(Unit)
                delay(1_000L)
            }
        }
        viewModelScope.launch {
            combine(agoraRepository.remoteAgents, tick) { agentes, _ -> agentes }
                .collect { agentes ->
                    val marcadores = agentes.values.map { it.toMarker() }
                    _uiState.update { it.copy(remoteAgents = marcadores) }
                }
        }
    }

    private fun RemoteAgent.toMarker(): RemoteAgentMarker {
        val sinSenal = System.currentTimeMillis() - lastSeenMillis > STALE_AFTER_MILLIS
        return RemoteAgentMarker(
            uid = uid,
            latitude = latitude,
            longitude = longitude,
            isStale = sinSenal,
            lastSeenLabel = if (sinSenal) "Last seen: ${formatClock(lastSeenMillis)}" else "",
        )
    }

    private fun formatClock(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    companion object {
        // Los companeros emiten cada 1 s en movimiento mas un heartbeat de 3 s:
        // 7 s de silencio equivalen a dos heartbeats perdidos.
        private const val STALE_AFTER_MILLIS = 7_000L
    }
}
