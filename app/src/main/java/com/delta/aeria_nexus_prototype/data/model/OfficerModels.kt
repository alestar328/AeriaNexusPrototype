package com.delta.aeria_nexus_prototype.data.model

/**
 * Modelos del flujo de campo del agente: incidentes capturados en sitio,
 * su evidencia, su linea de tiempo y el perfil del agente.
 */

data class OfficerProfile(
    val name: String,
    val officerNum: String,
    val agency: String,
    val unit: String,
    val rank: String,
    val jurisdiction: String,
    val languageProfile: String,
    val transcriptLanguage: String,
    val reportLanguage: String,
    val deviceId: String,
    val fcConnected: Boolean,
    val flConnected: Boolean,
    val appVersion: String,
)

data class TimelineEntry(
    val id: String,
    val time: String,
    val event: String,
    val type: TimelineEntryType,
)

data class EvidenceRecord(
    val id: String,
    val type: EvidenceType,
    val label: String,
    val time: String,
    val duration: String? = null,
    val device: String? = null,
    val classification: EvidenceClass? = null,
    val encrypted: Boolean = true,
    val sealed: Boolean = true,
    val hash: String,
    val sync: SyncState,
    // Marca de tiempo dentro de la grabacion si la foto se tomo mientras se grababa.
    val linkedTimestamp: String? = null,
    // Uri del archivo en el album localIncidents cuando se capturo con el telefono.
    val mediaUri: String? = null,
)

data class OfficerIncident(
    val id: String,
    val type: String,
    val typeCode: String,
    val location: String,
    val date: String,
    val time: String,
    val officerName: String,
    val officerNum: String,
    val status: IncidentStatus,
    val priority: Priority,
    val evidenceCount: Int,
    val witnessCount: Int,
    val sync: SyncState,
    val duration: String? = null,
    val narrative: String? = null,
    val timeline: List<TimelineEntry> = emptyList(),
    val evidence: List<EvidenceRecord> = emptyList(),
)

/** Incidente en curso: existe uno como maximo y vive solo en memoria. */
data class ActiveIncident(
    val id: String,
    val type: String,
    val location: String,
    val startedAtMillis: Long,
    val evidenceCount: Int = 0,
    val witnessCount: Int = 0,
    val isRecording: Boolean = false,
    val timeline: List<TimelineEntry> = emptyList(),
    val evidence: List<EvidenceRecord> = emptyList(),
    val reportSubmitted: Boolean = false,
)
