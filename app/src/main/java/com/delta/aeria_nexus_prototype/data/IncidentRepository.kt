package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.local.IncidentDao
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceEntity
import com.delta.aeria_nexus_prototype.data.local.toDomain
import com.delta.aeria_nexus_prototype.data.local.toEntity
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
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

    // ── Material importado de un periferico (gafas, bodycam) ─────────────────
    // Entra DESPUES de que su grabacion termino, asi que no puede colgarse del
    // incidente activo: el agente elige. Ver RawEvidenceRepository.

    /**
     * Crea un incidente nuevo a partir de una pieza importada y lo guarda ya
     * cerrado, como borrador.
     *
     * **Se fecha en la grabacion, no en el momento de categorizar.** Un incidente
     * que dice haber ocurrido cuando el agente lo archivo es un dato falso, y la
     * fecha es justo lo que hace util una prueba. Solo cuando el aparato no dio
     * fecha se cae a la de importacion, y entonces la linea de tiempo lo dice.
     */
    suspend fun crearIncidenteDeImportacion(
        fila: RawEvidenceEntity,
        clasificacion: EvidenceClass,
        etiqueta: String,
    ): String {
        val id = generateIncidentId()
        val conFechaReal = fila.recordedAtMillis > 0
        val cuando = Instant.ofEpochMilli(if (conFechaReal) fila.recordedAtMillis else fila.importedAtMillis)
            .atZone(ZoneId.systemDefault())
        val evidencia = fila.aEvidenceRecord(clasificacion, etiqueta, cuando.format(TIME_FORMAT))
        val incidente = OfficerIncident(
            id = id,
            type = "Imported Evidence",
            typeCode = "IMPORT",
            location = "Unknown",
            date = cuando.format(DATE_FORMAT),
            time = cuando.format(TIME_FORMAT),
            officerName = officerProfile.name,
            officerNum = officerProfile.officerNum,
            status = IncidentStatus.DRAFT,
            priority = Priority.MEDIUM,
            evidenceCount = 1,
            witnessCount = 0,
            sync = SyncState.LOCAL_ONLY,
            timeline = listOf(
                TimelineEntry(
                    id = UUID.randomUUID().toString(),
                    time = cuando.format(TIME_FORMAT),
                    event = if (conFechaReal) {
                        "Evidence imported from ${fila.source.label}"
                    } else {
                        "Evidence imported from ${fila.source.label} (no recording date)"
                    },
                    type = TimelineEntryType.SYSTEM,
                ),
            ),
            evidence = listOf(evidencia),
        )
        _officerIncidents.value = listOf(incidente) + _officerIncidents.value
        incidentDao.save(incidente, System.currentTimeMillis())
        onIncidentSaved?.invoke()
        return id
    }

    /**
     * Adjunta una pieza importada a un incidente que el agente ya tenia.
     *
     * Devuelve false si ese incidente no existe: mejor no categorizar que dejar
     * una evidencia apuntando a un incidente fantasma.
     */
    suspend fun adjuntarEvidenciaImportada(
        incidentId: String,
        fila: RawEvidenceEntity,
        clasificacion: EvidenceClass,
        etiqueta: String,
    ): Boolean {
        val incidente = findOfficerIncident(incidentId) ?: return false
        val cuando = Instant.ofEpochMilli(
            if (fila.recordedAtMillis > 0) fila.recordedAtMillis else fila.importedAtMillis,
        ).atZone(ZoneId.systemDefault())
        val evidencia = fila.aEvidenceRecord(clasificacion, etiqueta, cuando.format(TIME_FORMAT))

        incidentDao.insertEvidence(listOf(evidencia.toEntity(incidentId, incidente.evidence.size)))
        incidentDao.incrementarEvidencia(incidentId)
        // La lista vive en memoria; sin esto la pantalla de Incidents seguiria
        // mostrando el contador viejo hasta reiniciar la app.
        _officerIncidents.value = _officerIncidents.value.map { existente ->
            if (existente.id != incidentId) {
                existente
            } else {
                existente.copy(
                    evidence = existente.evidence + evidencia,
                    evidenceCount = existente.evidenceCount + 1,
                )
            }
        }
        onIncidentSaved?.invoke()
        return true
    }

    /**
     * El hash viaja tal cual desde el cifrado: es el de la cadena de custodia y no
     * se recalcula al categorizar — categorizar no toca el fichero.
     */
    private fun RawEvidenceEntity.aEvidenceRecord(
        clasificacion: EvidenceClass,
        etiqueta: String,
        hora: String,
    ) = EvidenceRecord(
        id = UUID.randomUUID().toString(),
        type = EvidenceType.VIDEO,
        label = etiqueta,
        time = hora,
        device = source.label,
        classification = clasificacion,
        encrypted = true,
        sealed = true,
        hash = evidenceHash(plainSha256),
        sync = SyncState.LOCAL_ONLY,
        mediaUri = fileName,
    )

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
