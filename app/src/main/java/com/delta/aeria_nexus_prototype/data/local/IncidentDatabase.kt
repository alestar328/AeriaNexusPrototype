package com.delta.aeria_nexus_prototype.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos local de la app. Pendiente para produccion: los datos de
 * incidentes son sensibles y hoy se guardan sin cifrar; antes de un despliegue
 * real hay que migrar a SQLCipher o equivalente.
 */
@Database(
    entities = [IncidentEntity::class, TimelineEntryEntity::class, EvidenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class IncidentDatabase : RoomDatabase() {

    abstract fun incidentDao(): IncidentDao

    companion object {
        fun build(context: Context): IncidentDatabase =
            Room.databaseBuilder(context, IncidentDatabase::class.java, "aeria_nexus.db")
                .build()
    }
}
