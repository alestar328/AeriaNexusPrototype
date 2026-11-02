package com.delta.aeria_nexus_prototype.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.BatteryRepository
import com.delta.aeria_nexus_prototype.data.FinDeSos
import com.delta.aeria_nexus_prototype.data.HistorialDelBackend
import com.delta.aeria_nexus_prototype.data.HistorialSos
import com.delta.aeria_nexus_prototype.data.LocationRepository
import com.delta.aeria_nexus_prototype.data.OrigenSos
import com.delta.aeria_nexus_prototype.data.RegistroSos
import com.delta.aeria_nexus_prototype.data.SosDelBackend
import com.delta.aeria_nexus_prototype.data.model.RemoteAgent
import com.delta.aeria_nexus_prototype.data.model.SosAlert
import com.delta.aeria_nexus_prototype.data.model.SosCancel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    // Directos de SOS de este agente que se pueden abrir desde su marcador: el de
    // su telefono y el de la bodycam que lleva. Null el que no este emitiendo.
    val sosTelefonoUid: Int? = null,
    val sosBodycamUid: Int? = null,
) {
    val tieneSos: Boolean get() = sosTelefonoUid != null || sosBodycamUid != null
}

/**
 * SOS vigente de otro agente, anclado a su posicion en el mapa. Persiste
 * aunque el receptor haya descartado el popup: mientras la emergencia dure,
 * el mapa la muestra y tocarla abre el livestream del emisor.
 */
data class SosMarker(
    val uid: Int,
    // Identifica esta emergencia: cerrar su banner no oculta la siguiente del mismo agente.
    val sessionKey: String,
    val officer: String,
    val latitude: Double,
    val longitude: Double,
    val startedTimeLabel: String,
)

/**
 * Aviso fijo en el mapa de que otro agente en emergencia corto la senal:
 * quien era y a que hora corto, anclado a su ultima posicion conocida.
 */
data class SignalCutMarker(
    val uid: Int,
    val officer: String,
    val latitude: Double,
    val longitude: Double,
    val cutTimeLabel: String,
)

/** Una fila del historial de SOS, ya con los textos que se pintan. */
data class SosHistoryItem(
    val key: String,
    val officer: String,
    val sourceLabel: String,
    val dateLabel: String,
    val startLabel: String,
    val endLabel: String,
    val isActive: Boolean,
)

data class MapUiState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    // True desde el primer fix GPS; antes de eso no hay posicion valida.
    val gpsReady: Boolean = false,
    val satellites: Int = 0,
    val batteryPercent: Int = 0,
    val remoteAgents: List<RemoteAgentMarker> = emptyList(),
    val activeSos: List<SosMarker> = emptyList(),
    // Los SOS vigentes cuyo banner no ha cerrado el usuario. El tooltip del mapa
    // sigue aunque se cierre el banner, mientras la emergencia dure.
    val sosBanners: List<SosMarker> = emptyList(),
    val sosHistory: List<SosHistoryItem> = emptyList(),
    // Cuantos SOS ha habido: el total de AeriaOne si se ha podido leer, si no los
    // que ha visto este telefono.
    val sosTotal: Int = 0,
    // False mientras la lista sea la copia local de este telefono.
    val sosHistoryFromServer: Boolean = false,
    val sosHistoryLoading: Boolean = false,
    val signalCuts: List<SignalCutMarker> = emptyList(),
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
    private val historialSos: HistorialSos,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var started = false

    // Sesiones SOS cuyo banner ha cerrado el usuario en esta pantalla.
    private val bannersCerrados = MutableStateFlow<Set<String>>(emptySet())

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
            combine(
                agoraRepository.remoteAgents,
                agoraRepository.activeSosAlerts,
                tick,
            ) { agentes, alertas, _ -> agentes.values.map { it.toMarker(alertas) } }
                .collect { marcadores ->
                    _uiState.update { it.copy(remoteAgents = marcadores) }
                }
        }
        // Los SOS vigentes se combinan con las posiciones de la red para que
        // el marcador siga al emisor mientras se mueve; sin posicion viva se
        // usa la coordenada que trajo la propia alerta.
        viewModelScope.launch {
            combine(
                agoraRepository.activeSosAlerts,
                agoraRepository.remoteAgents,
                bannersCerrados,
            ) { alertas, agentes, cerrados ->
                val marcadores = alertas.values.mapNotNull { it.toSosMarker(posicionDelEmisor(it.uid, agentes)) }
                marcadores to marcadores.filter { it.sessionKey !in cerrados }
            }.collect { (marcadores, banners) ->
                _uiState.update { it.copy(activeSos = marcadores, sosBanners = banners) }
            }
        }
        viewModelScope.launch {
            combine(
                historialSos.registros,
                historialSos.delBackend,
                agoraRepository.activeSosAlerts,
                agoraRepository.sosActive,
            ) { registros, delBackend, alertas, sosPropio ->
                historialParaPintar(registros, delBackend, alertas.keys, sosPropio)
            }.collect { historial ->
                _uiState.update {
                    it.copy(
                        sosHistory = historial.filas,
                        sosTotal = historial.total,
                        sosHistoryFromServer = historial.delServidor,
                    )
                }
            }
        }
        // El contador se pone al dia al abrir el mapa y con cada SOS que empieza o
        // termina, sin sondear al backend cada pocos segundos.
        refreshSosHistory()
        viewModelScope.launch {
            agoraRepository.incomingSos.collect { refrescarTrasUnMomento() }
        }
        viewModelScope.launch {
            agoraRepository.incomingSosCancel.collect { refrescarTrasUnMomento() }
        }
        viewModelScope.launch {
            agoraRepository.sosSignalCuts.collect { cortes ->
                val avisos = cortes.values.mapNotNull { it.toSignalCutMarker() }
                _uiState.update { it.copy(signalCuts = avisos) }
            }
        }
    }

    /** Quita el aviso de corte de senal del agente [uid] cuando el usuario lo toca. */
    fun dismissSignalCut(uid: Int) {
        agoraRepository.dismissSignalCut(uid)
    }

    /** Vuelve a pedir el historial a AeriaOne; se llama tambien al abrir la lista. */
    fun refreshSosHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(sosHistoryLoading = true) }
            historialSos.actualizarDelBackend()
            _uiState.update { it.copy(sosHistoryLoading = false) }
        }
    }

    // El aviso al backend sale a la vez que el del canal: se le deja un margen
    // para que el SOS ya este apuntado cuando se le pregunta.
    private suspend fun refrescarTrasUnMomento() {
        delay(ESPERA_AVISO_BACKEND_MILLIS)
        refreshSosHistory()
    }

    /** Cierra el banner de un SOS; el tooltip del mapa sigue mientras dure. */
    fun dismissSosBanner(sessionKey: String) {
        bannersCerrados.update { it + sessionKey }
    }

    /** Quita el marcador gris "Last seen" de un agente del que ya no llega nada. */
    fun dismissStaleAgent(uid: Int) {
        agoraRepository.olvidarAgente(uid)
    }

    /**
     * Donde esta quien emite el directo [uid]: su propio telefono o, si es una
     * bodycam, el telefono que anuncia llevarla.
     */
    private fun posicionDelEmisor(uid: Int, agentes: Map<Int, RemoteAgent>): RemoteAgent? =
        agentes[uid] ?: agentes.values.firstOrNull { it.bodycamUid == uid }

    private fun SosAlert.toSosMarker(posicionViva: RemoteAgent?): SosMarker? {
        // La posicion de la red esta mas fresca que la de la alerta (el emisor
        // sigue compartiendo ubicacion durante el SOS); sin ninguna de las dos
        // no hay donde anclar el marcador (bodycam sin telefono que la anuncie).
        val lat = posicionViva?.latitude ?: latitude ?: return null
        val lng = posicionViva?.longitude ?: longitude ?: return null
        return SosMarker(
            uid = uid,
            sessionKey = sessionKey,
            officer = officer,
            latitude = lat,
            longitude = lng,
            startedTimeLabel = formatClock(startedAtMillis),
        )
    }

    private fun SosCancel.toSignalCutMarker(): SignalCutMarker? {
        // Si la cancelacion no trajo posicion se usa la ultima conocida del
        // agente en la red; sin ninguna de las dos no hay donde anclar el aviso.
        val ultimaPosicion = agoraRepository.remoteAgents.value[uid]
        val lat = latitude ?: ultimaPosicion?.latitude ?: return null
        val lng = longitude ?: ultimaPosicion?.longitude ?: return null
        return SignalCutMarker(
            uid = uid,
            officer = officer,
            latitude = lat,
            longitude = lng,
            cutTimeLabel = formatClock(timestampMillis),
        )
    }

    private fun RemoteAgent.toMarker(alertas: Map<Int, SosAlert>): RemoteAgentMarker {
        val sinSenal = System.currentTimeMillis() - lastSeenMillis > STALE_AFTER_MILLIS
        return RemoteAgentMarker(
            uid = uid,
            latitude = latitude,
            longitude = longitude,
            isStale = sinSenal,
            lastSeenLabel = if (sinSenal) "Last seen: ${formatClock(lastSeenMillis)}" else "",
            sosTelefonoUid = uid.takeIf { it in alertas },
            sosBodycamUid = bodycamUid?.takeIf { it in alertas },
        )
    }

    private class HistorialPintado(val filas: List<SosHistoryItem>, val total: Int, val delServidor: Boolean)

    private fun historialParaPintar(
        registros: List<RegistroSos>,
        delBackend: HistorialDelBackend?,
        uidsEnSos: Set<Int>,
        sosPropio: Boolean,
    ): HistorialPintado {
        if (delBackend != null) {
            return HistorialPintado(delBackend.items.map { it.toHistoryItem() }, delBackend.total, delServidor = true)
        }
        return HistorialPintado(registros.map { it.toHistoryItem(uidsEnSos, sosPropio) }, registros.size, delServidor = false)
    }

    private fun SosDelBackend.toHistoryItem(): SosHistoryItem {
        val finTexto = when {
            live -> "Active"
            stoppedAtMillis == null -> "No end recorded"
            reason == "signal_lost" -> "Signal lost ${formatClock(stoppedAtMillis)}"
            else -> "Ended ${formatClock(stoppedAtMillis)}"
        }
        return SosHistoryItem(
            key = sosId,
            officer = listOf(officerCode, officerName).filter { it.isNotBlank() }.joinToString(" — "),
            sourceLabel = if (source == "bodycam") "Bodycam" else "Phone",
            dateLabel = formatDate(startedAtMillis),
            startLabel = formatClock(startedAtMillis),
            endLabel = finTexto,
            isActive = live,
        )
    }

    /**
     * Un registro sin fin solo se pinta como activo si el SOS sigue vivo de verdad:
     * si la app se cerro en mitad de uno, su fin no llego a apuntarse.
     */
    private fun RegistroSos.toHistoryItem(uidsEnSos: Set<Int>, sosPropio: Boolean): SosHistoryItem {
        val activo = fin == null && when (origen) {
            OrigenSos.ESTE_TELEFONO -> sosPropio
            else -> uid in uidsEnSos
        }
        val finTexto = when {
            activo -> "Active"
            fin == FinDeSos.TERMINADO && finMillis != null -> "Ended ${formatClock(finMillis)}"
            fin == FinDeSos.SENAL_PERDIDA && finMillis != null -> "Signal lost ${formatClock(finMillis)}"
            else -> "No end recorded"
        }
        return SosHistoryItem(
            key = clave,
            officer = officer,
            sourceLabel = when (origen) {
                OrigenSos.ESTE_TELEFONO -> "This phone"
                OrigenSos.TELEFONO -> "Phone"
                OrigenSos.BODYCAM -> "Bodycam"
            },
            dateLabel = formatDate(inicioMillis),
            startLabel = formatClock(inicioMillis),
            endLabel = finTexto,
            isActive = activo,
        )
    }

    private fun formatDate(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))

    private fun formatClock(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    companion object {
        // Los companeros emiten cada 1 s en movimiento mas un heartbeat de 3 s:
        // 7 s de silencio equivalen a dos heartbeats perdidos.
        private const val STALE_AFTER_MILLIS = 7_000L
        private const val ESPERA_AVISO_BACKEND_MILLIS = 3_000L
    }
}
