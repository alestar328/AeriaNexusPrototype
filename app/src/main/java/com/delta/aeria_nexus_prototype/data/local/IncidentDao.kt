package com.delta.aeria_nexus_prototype.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.model.SyncState

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

    /**
     * Estado de subida de una evidencia concreta. Devuelve las filas afectadas: cero
     * significa que el agente todavia no ha clasificado esa evidencia y su fila aun no
     * existe, lo cual es normal — la verdad de si algo se subio esta en el recibo que
     * escribe EvidenceUploader, no aqui.
     */
    @Query("UPDATE evidence_records SET sync = :state WHERE id = :evidenceId")
    suspend fun updateEvidenceSync(evidenceId: String, state: SyncState): Int

    /**
     * Suma uno al contador de evidencia de un incidente ya guardado. Hace falta
     * porque una evidencia importada de un periferico se adjunta DESPUES de
     * cerrarlo, y el contador se calculo al guardarlo.
     */
    @Query("UPDATE incidents SET evidenceCount = evidenceCount + 1 WHERE id = :incidentId")
    suspend fun incrementarEvidencia(incidentId: String): Int
}
