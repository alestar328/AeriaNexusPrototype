package com.delta.aeria_nexus_prototype.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import kotlinx.coroutines.flow.Flow

/**
 * Material que ha entrado en la boveda **sin pertenecer todavia a ningun
 * incidente**: en bruto.
 *
 * Es el caso de las gafas y sera el de la bodycam: el telefono captura DENTRO de
 * un incidente activo, pero un periferico entrega su video **a posteriori**,
 * cuando la grabacion ya termino. Hasta que el agente lo categoriza, el fichero
 * vive cifrado en la boveda con lo unico que se sabe de el con certeza: de que
 * aparato salio, como se llamaba alli y **cuando se grabo**.
 *
 * Esa fecha es el motivo de que esta tabla exista. La boveda lista los .fev por
 * `lastModified`, que es **la fecha de descarga, no la de grabacion**; para una
 * prueba esa diferencia importa, y el dato solo lo tiene el aparato de origen.
 *
 * [incidentId] nulo = sigue en bruto. Al categorizar se rellena junto con la
 * clasificacion, y la fila se queda: es lo que ata el fichero de la boveda con su
 * incidente sin tener que descifrar nada.
 */
@Entity(tableName = "raw_evidence")
data class RawEvidenceEntity(
    /** Nombre del `.fev` en la boveda. Un fichero, una fila. */
    @PrimaryKey val fileName: String,
    /** Aparato del que salio: Falcon Lens (gafas) o Falcon Core (bodycam). */
    val source: EvidenceSource,
    /** Como lo llamaba el aparato de origen, por trazabilidad. */
    val originalName: String,
    /** Fecha de GRABACION, la del aparato. 0 si el origen no la dio. */
    val recordedAtMillis: Long,
    /** Fecha de descarga al telefono. Nunca se confunde con la anterior. */
    val importedAtMillis: Long,
    val bytes: Long,
    /** SHA-256 del claro: el de la cadena de custodia. */
    val plainSha256: String,
    /** Null mientras siga en bruto; al categorizar, el incidente al que pertenece. */
    val incidentId: String? = null,
    val classification: EvidenceClass? = null,
    val label: String? = null,
    val categorizedAtMillis: Long? = null,
)

@Dao
interface RawEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(fila: RawEvidenceEntity)

    /**
     * Lo que espera categorizacion, lo mas reciente primero. Ordena por fecha de
     * **grabacion** y solo cae en la de importacion cuando el aparato no dio la
     * suya: el agente busca por cuando ocurrio, no por cuando lo descargo.
     */
    @Query(
        """
        SELECT * FROM raw_evidence
        WHERE incidentId IS NULL
        ORDER BY (CASE WHEN recordedAtMillis > 0 THEN recordedAtMillis ELSE importedAtMillis END) DESC
        """,
    )
    fun observarSinCategorizar(): Flow<List<RawEvidenceEntity>>

    @Query("SELECT * FROM raw_evidence WHERE fileName = :fileName")
    suspend fun buscar(fileName: String): RawEvidenceEntity?

    /** Todas las filas ya categorizadas de un incidente. */
    @Query("SELECT * FROM raw_evidence WHERE incidentId = :incidentId")
    suspend fun deIncidente(incidentId: String): List<RawEvidenceEntity>

    @Query(
        """
        UPDATE raw_evidence
        SET incidentId = :incidentId, classification = :clasificacion,
            label = :label, categorizedAtMillis = :cuando
        WHERE fileName = :fileName
        """,
    )
    suspend fun categorizar(
        fileName: String,
        incidentId: String,
        clasificacion: EvidenceClass,
        label: String,
        cuando: Long,
    ): Int
}
