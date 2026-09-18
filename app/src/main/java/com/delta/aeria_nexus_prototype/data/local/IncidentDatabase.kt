package com.delta.aeria_nexus_prototype.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de datos local de la app. Pendiente para produccion: los datos de
 * incidentes son sensibles y hoy se guardan sin cifrar; antes de un despliegue
 * real hay que migrar a SQLCipher o equivalente.
 */
@Database(
    entities = [
        IncidentEntity::class,
        TimelineEntryEntity::class,
        EvidenceEntity::class,
        RawEvidenceEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class IncidentDatabase : RoomDatabase() {

    abstract fun incidentDao(): IncidentDao

    abstract fun rawEvidenceDao(): RawEvidenceDao

    companion object {

        /**
         * v2: tabla `raw_evidence`, el material importado de un periferico que aun
         * no pertenece a ningun incidente.
         *
         * Migracion de verdad y no `fallbackToDestructiveMigration()`: en los
         * telefonos de prueba ya hay incidentes guardados, y borrarlos para anadir
         * una tabla vacia seria destruir evidencia por comodidad.
         */
        private val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `raw_evidence` (
                        `fileName` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `originalName` TEXT NOT NULL,
                        `recordedAtMillis` INTEGER NOT NULL,
                        `importedAtMillis` INTEGER NOT NULL,
                        `bytes` INTEGER NOT NULL,
                        `plainSha256` TEXT NOT NULL,
                        `incidentId` TEXT,
                        `classification` TEXT,
                        `label` TEXT,
                        `categorizedAtMillis` INTEGER,
                        PRIMARY KEY(`fileName`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v3: coordenadas del incidente. Los que ya existen se quedan sin ellas. */
        private val MIGRACION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `latitude` REAL")
                db.execSQL("ALTER TABLE `incidents` ADD COLUMN `longitude` REAL")
            }
        }

        fun build(context: Context): IncidentDatabase =
            Room.databaseBuilder(context, IncidentDatabase::class.java, "aeria_nexus.db")
                .addMigrations(MIGRACION_1_2, MIGRACION_2_3)
                .build()
    }
}
