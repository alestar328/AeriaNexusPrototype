package com.delta.aeria_nexus_prototype.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident

@Dao
interface IncidentDao {

    @Transaction
    @Query("SELECT * FROM incidents ORDER BY createdAtMillis DESC")
    suspend fun getAll(): List<IncidentWithDetails>

    /** Guarda el incident completo (con timeline y evidencia) en una sola transaccion. */
    @Transaction
    suspend fun save(incident: OfficerIncident, createdAtMillis: Long) {
        insertIncident(incident.toEntity(createdAtMillis))
        insertTimeline(incident.timeline.mapIndexed { index, entry -> entry.toEntity(incident.id, index) })
        insertEvidence(incident.evidence.mapIndexed { index, record -> record.toEntity(incident.id, index) })
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeline(entries: List<TimelineEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(records: List<EvidenceEntity>)
}
