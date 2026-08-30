package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.local.IncidentDao
import com.delta.aeria_nexus_prototype.data.local.toDomain
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.OfficerProfile
import com.delta.aeria_nexus_prototype.data.model.Priority
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unica puerta de acceso a datos de la app. Los incidents que crea el agente
 * se guardan en Room; el resto siguen siendo datos de ejemplo en memoria.
 */
class IncidentRepository(private val incidentDao: IncidentDao) {

    // Vive lo mismo que la app (el repositorio es unico), por eso no hace
    // falta cancelarlo. Dispatchers.IO porque solo hace trabajo de disco.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val officerProfile: OfficerProfile = OfficerSampleData.profile
    val reportIncidents: List<ReportIncident> = ReportSampleData.incidents

    // Lista de incidentes del agente: solo lo guardado en Room, mas recientes
    // arriba. Observable para que la pestana Incidents se actualice sola.
    // Arranca vacia y la rellena el init; la pantalla ya tiene estado vacio.
    private val _officerIncidents = MutableStateFlow(emptyList<OfficerIncident>())
    val officerIncidents: StateFlow<List<OfficerIncident>> = _officerIncidents.asStateFlow()

    init {
        // La carga inicial termina antes de que el usuario pueda cerrar un
        // incidente nuevo, por eso basta con reemplazar el valor completo.
        scope.launch {
            _officerIncidents.value = incidentDao.getAll().map { it.toDomain() }
        }
    }

    // Incidente activo compartido entre Operations y Active Incident.
    private val _activeIncident = MutableStateFlow<ActiveIncident?>(null)
    val activeIncident: StateFlow<ActiveIncident?> = _activeIncident.asStateFlow()

    fun findOfficerIncident(id: String): OfficerIncident? =
        _officerIncidents.value.find { it.id == id }

    fun findReportIncident(id: String): ReportIncident? =
        reportIncidents.find { it.id == id }

    /** Crea un incidente nuevo, lo deja como activo y devuelve su id. */
    fun startNewIncident(): String {
        val id = generateIncidentId()
        _activeIncident.value = ActiveIncident(
            id = id,
            type = "Field Incident",
            location = "Current Location",
            startedAtMillis = System.currentTimeMillis(),
            timeline = listOf(
                TimelineEntry(
                    id = UUID.randomUUID().toString(),
                    time = nowTime(),
                    event = "Incident created",
                    type = TimelineEntryType.CREATED,
                ),
            ),
        )
        return id
    }

    /**
     * Aviso de que un incidente acaba de persistirse. Lo usa EvidenceUploader para
     * volcar el estado de subida en cuanto las filas de evidencia existen: antes de
     * este momento no hay nada que actualizar.
     */
    var onIncidentSaved: (() -> Unit)? = null

    /** Aplica un cambio sobre el incidente activo, si existe. */
    fun updateActiveIncident(transform: (ActiveIncident) -> ActiveIncident) {
        _activeIncident.value = _activeIncident.value?.let(transform)
    }

    /** Cierra el incidente activo y lo archiva como borrador en la lista de Incidents. */
    fun endActiveIncident() {
        _activeIncident.value?.let { activo ->
            val incidente = activo.toOfficerIncident()
            _officerIncidents.value = listOf(incidente) + _officerIncidents.value
            scope.launch {
                incidentDao.save(incidente, System.currentTimeMillis())
                onIncidentSaved?.invoke()
            }
        }
        _activeIncident.value = null
    }

    /** Convierte el incidente en curso al formato de la lista, con la evidencia capturada. */
    private fun ActiveIncident.toOfficerIncident(): OfficerIncident {
        val inicio = Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault())
        val minutos = ((System.currentTimeMillis() - startedAtMillis) / 60_000).toInt()
        return OfficerIncident(
            id = id,
            type = type,
            typeCode = "FIELD",
            location = location,
            date = inicio.format(DATE_FORMAT),
            time = inicio.format(TIME_FORMAT),
            officerName = officerProfile.name,
            officerNum = officerProfile.officerNum,
            status = IncidentStatus.DRAFT,
            priority = Priority.MEDIUM,
            evidenceCount = evidence.size,
            witnessCount = witnessCount,
            sync = SyncState.LOCAL_ONLY,
            duration = "$minutos min",
            timeline = timeline,
            evidence = evidence,
        )
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        fun nowTime(): String = LocalTime.now().format(TIME_FORMAT)

        fun generateIncidentId(): String =
            "INC-2026-%05d".format(Random.nextInt(100_000))

        /**
         * Hash de custodia de una evidencia: el SHA-256 real del fichero en
         * claro que devuelve EvidenceCrypto.seal (campo plainSha256), con el
         * prefijo "sha256:" que espera el descifrador de referencia
         * (tools/falcon_evidence_decrypt.py --expect-sha256).
         *
         * Si el cifrado fallo llega null y se cae al hash simulado: la evidencia
         * sigue su curso, pero se ve que no esta verificada.
         */
        fun evidenceHash(plainSha256: String?): String =
            if (plainSha256 != null) "sha256:$plainSha256" else fakeHash()

        /**
         * Hash simulado. Solo para la evidencia que esta app no ha calculado:
         * la que graba la bodycam (que la cifra y la hashea ella misma) y los
         * datos de ejemplo del prototipo.
         */
        fun fakeHash(): String =
            "sha256:" + UUID.randomUUID().toString().replace("-", "").take(8) + "..."
    }
}
