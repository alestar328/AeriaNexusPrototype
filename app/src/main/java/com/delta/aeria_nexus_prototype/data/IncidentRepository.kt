package com.delta.aeria_nexus_prototype.data

import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.OfficerProfile
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unica puerta de acceso a datos de la app. Hoy sirve datos de ejemplo en
 * memoria; cuando exista backend, solo esta clase debe cambiar.
 */
class IncidentRepository {

    val officerProfile: OfficerProfile = OfficerSampleData.profile
    val officerIncidents: List<OfficerIncident> = OfficerSampleData.incidents
    val reportIncidents: List<ReportIncident> = ReportSampleData.incidents

    // Incidente activo compartido entre Operations y Active Incident.
    private val _activeIncident = MutableStateFlow<ActiveIncident?>(null)
    val activeIncident: StateFlow<ActiveIncident?> = _activeIncident.asStateFlow()

    fun findOfficerIncident(id: String): OfficerIncident? =
        officerIncidents.find { it.id == id }

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

    /** Aplica un cambio sobre el incidente activo, si existe. */
    fun updateActiveIncident(transform: (ActiveIncident) -> ActiveIncident) {
        _activeIncident.value = _activeIncident.value?.let(transform)
    }

    /** Cierra el incidente activo. En el prototipo simplemente se descarta. */
    fun endActiveIncident() {
        _activeIncident.value = null
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun nowTime(): String = LocalTime.now().format(TIME_FORMAT)

        fun generateIncidentId(): String =
            "INC-2026-%05d".format(Random.nextInt(100_000))

        /** Hash simulado para la evidencia del prototipo. */
        fun fakeHash(): String =
            "sha256:" + UUID.randomUUID().toString().replace("-", "").take(8) + "..."
    }
}
