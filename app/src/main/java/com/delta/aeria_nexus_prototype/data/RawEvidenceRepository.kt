package com.delta.aeria_nexus_prototype.data

import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceDao
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceEntity
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import kotlinx.coroutines.flow.Flow

private const val TAG = "AeriaRawEvidence"

/**
 * Material importado de un periferico que todavia no pertenece a ningun
 * incidente, y el paso que lo convierte en evidencia: **la categorizacion**.
 *
 * El flujo, decidido con el cliente el 2026-09-09:
 *
 *   1. El video baja de las gafas (o de la bodycam) y se cifra en la boveda.
 *      Aqui se registra en bruto: aparato de origen, nombre original y **fecha de
 *      grabacion**, que es lo unico que se sabe con certeza de el.
 *   2. El agente lo categoriza: elige clasificacion y **elige el incidente** —
 *      uno que ya existe, o uno nuevo que se crea en ese momento.
 *   3. Si no lo categoriza, se queda en bruto en la boveda, con su fecha. No se
 *      pierde y no se inventa a que pertenece.
 *
 * **Por que el agente elige y no lo adivinamos:** un periferico entrega su video
 * cuando la grabacion ya termino, asi que cruzar por hora con el incidente activo
 * seria una conjetura. Una conjetura dentro de una cadena de custodia es peor que
 * un hueco: el hueco se ve, la conjetura no.
 */
class RawEvidenceRepository(
    private val dao: RawEvidenceDao,
    private val incidentes: IncidentRepository,
) {

    /** Lo que espera categorizacion. La pantalla de la boveda lo observa. */
    val sinCategorizar: Flow<List<RawEvidenceEntity>> = dao.observarSinCategorizar()

    /**
     * Apunta un fichero recien descargado y cifrado. Lo llama quien importa (hoy
     * GafasMediaRepository); no crea incidente ni decide nada.
     *
     * [recordedAtMillis] es la fecha que dio el aparato. Si llega a 0 se queda a 0
     * **a proposito**: es preferible que la boveda diga "sin fecha de grabacion" a
     * que ponga la de descarga y parezca un dato que no es.
     */
    suspend fun registrarImportacion(
        sellada: EvidenceCrypto.Sealed,
        source: EvidenceSource,
        originalName: String,
        recordedAtMillis: Long,
    ) = registrar(
        fileName = sellada.file.name,
        bytes = sellada.cipherBytes,
        plainSha256 = sellada.plainSha256,
        source = source,
        originalName = originalName,
        recordedAtMillis = recordedAtMillis,
    )

    /**
     * Forma general del registro, con el hash de custodia explicito. La usa el
     * cifrado a traves de [registrarImportacion] y el gancho de pruebas de
     * MainActivity, que no tiene un `Sealed` que ensenar.
     */
    suspend fun registrar(
        fileName: String,
        bytes: Long,
        plainSha256: String,
        source: EvidenceSource,
        originalName: String,
        recordedAtMillis: Long,
    ) {
        val fila = RawEvidenceEntity(
            fileName = fileName,
            source = source,
            originalName = originalName,
            recordedAtMillis = recordedAtMillis.coerceAtLeast(0L),
            importedAtMillis = System.currentTimeMillis(),
            bytes = bytes,
            plainSha256 = plainSha256,
        )
        dao.insertar(fila)
        Log.d(TAG, "Importado en bruto: ${fila.fileName} (${source.label})")
    }

    /**
     * Categoriza una pieza en bruto y la mete en un incidente.
     *
     * Con [incidentId] nulo se **crea** un incidente nuevo fechado en la grabacion,
     * no en el momento de categorizar: un incidente que dice haber ocurrido cuando
     * el agente lo archivo es un dato falso.
     *
     * Devuelve el id del incidente al que quedo, o null si la pieza ya no esta.
     */
    suspend fun categorizar(
        fileName: String,
        clasificacion: EvidenceClass,
        label: String,
        incidentId: String? = null,
    ): String? {
        val fila = dao.buscar(fileName)
        if (fila == null) {
            Log.w(TAG, "No hay nada en bruto llamado $fileName")
            return null
        }
        if (fila.incidentId != null) {
            Log.w(TAG, "$fileName ya estaba en el incidente ${fila.incidentId}")
            return fila.incidentId
        }

        val etiqueta = label.ifBlank { etiquetaPorDefecto(fila) }
        val destino = incidentId
            ?: incidentes.crearIncidenteDeImportacion(fila, clasificacion, etiqueta)
        if (incidentId != null &&
            !incidentes.adjuntarEvidenciaImportada(incidentId, fila, clasificacion, etiqueta)
        ) {
            Log.e(TAG, "El incidente $incidentId no existe: no se categoriza $fileName")
            return null
        }

        dao.categorizar(fileName, destino, clasificacion, etiqueta, System.currentTimeMillis())
        Log.d(TAG, "$fileName categorizado como ${clasificacion.label} en $destino")
        return destino
    }

    /** Nombre visible cuando el agente no escribe ninguno. */
    private fun etiquetaPorDefecto(fila: RawEvidenceEntity): String =
        "${fila.source.label} — ${fila.originalName}"
}
