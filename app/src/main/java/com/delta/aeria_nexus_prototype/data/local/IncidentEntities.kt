package com.delta.aeria_nexus_prototype.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.Priority
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType

/**
 * Tablas de Room para los incidents creados por el agente. Los enums se
 * guardan como texto con su nombre; eso lo hace Room automaticamente.
 * Los datos de ejemplo (OfficerSampleData) nunca se guardan aqui.
 */

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey val id: String,
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
    val duration: String?,
    val narrative: String?,
    // Para ordenar la lista del mas reciente al mas antiguo.
    val createdAtMillis: Long,
)

@Entity(
    tableName = "timeline_entries",
    // Si se borra un incident, sus entradas de timeline se borran con el.
    foreignKeys = [
        ForeignKey(
            entity = IncidentEntity::class,
            parentColumns = ["id"],
            childColumns = ["incidentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("incidentId")],
)
data class TimelineEntryEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val time: String,
    val event: String,
    val type: TimelineEntryType,
    // Orden original dentro del incident; Room no garantiza el orden de una @Relation.
    val position: Int,
)

@Entity(
    tableName = "evidence_records",
    foreignKeys = [
        ForeignKey(
            entity = IncidentEntity::class,
            parentColumns = ["id"],
            childColumns = ["incidentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("incidentId")],
)
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val type: EvidenceType,
    val label: String,
    val time: String,
    val duration: String?,
    val device: String?,
    val classification: EvidenceClass?,
    val encrypted: Boolean,
    val sealed: Boolean,
    val hash: String,
    val sync: SyncState,
    val linkedTimestamp: String?,
    val mediaUri: String?,
    val position: Int,
)

/** Un incident con sus tablas hijas, tal como lo devuelve el DAO. */
data class IncidentWithDetails(
    @Embedded val incident: IncidentEntity,
    @Relation(parentColumn = "id", entityColumn = "incidentId")
    val timeline: List<TimelineEntryEntity>,
    @Relation(parentColumn = "id", entityColumn = "incidentId")
    val evidence: List<EvidenceEntity>,
)

fun IncidentWithDetails.toDomain(): OfficerIncident = OfficerIncident(
    id = incident.id,
    type = incident.type,
    typeCode = incident.typeCode,
    location = incident.location,
    date = incident.date,
    time = incident.time,
    officerName = incident.officerName,
    officerNum = incident.officerNum,
    status = incident.status,
    priority = incident.priority,
    evidenceCount = incident.evidenceCount,
    witnessCount = incident.witnessCount,
    sync = incident.sync,
    duration = incident.duration,
    narrative = incident.narrative,
    timeline = timeline.sortedBy { it.position }.map { it.toDomain() },
    evidence = evidence.sortedBy { it.position }.map { it.toDomain() },
)

private fun TimelineEntryEntity.toDomain() = TimelineEntry(
    id = id,
    time = time,
    event = event,
    type = type,
)

private fun EvidenceEntity.toDomain() = EvidenceRecord(
    id = id,
    type = type,
    label = label,
    time = time,
    duration = duration,
    device = device,
    classification = classification,
    encrypted = encrypted,
    sealed = sealed,
    hash = hash,
    sync = sync,
    linkedTimestamp = linkedTimestamp,
    mediaUri = mediaUri,
)

fun OfficerIncident.toEntity(createdAtMillis: Long) = IncidentEntity(
    id = id,
    type = type,
    typeCode = typeCode,
    location = location,
    date = date,
    time = time,
    officerName = officerName,
    officerNum = officerNum,
    status = status,
    priority = priority,
    evidenceCount = evidenceCount,
    witnessCount = witnessCount,
    sync = sync,
    duration = duration,
    narrative = narrative,
    createdAtMillis = createdAtMillis,
)

fun TimelineEntry.toEntity(incidentId: String, position: Int) = TimelineEntryEntity(
    id = id,
    incidentId = incidentId,
    time = time,
    event = event,
    type = type,
    position = position,
)

fun EvidenceRecord.toEntity(incidentId: String, position: Int) = EvidenceEntity(
    id = id,
    incidentId = incidentId,
    type = type,
    label = label,
    time = time,
    duration = duration,
    device = device,
    classification = classification,
    encrypted = encrypted,
    sealed = sealed,
    hash = hash,
    sync = sync,
    linkedTimestamp = linkedTimestamp,
    mediaUri = mediaUri,
    position = position,
)
