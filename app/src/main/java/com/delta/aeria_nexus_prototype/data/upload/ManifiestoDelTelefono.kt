package com.delta.aeria_nexus_prototype.data.upload

import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import org.json.JSONArray
import org.json.JSONObject

/**
 * El expediente de un incidente, tal como lo espera el backend:
 * `aeria-contracts/manifest-schema.md` §5, `schema: "phone-incident/1"`.
 *
 * Lleva lo que el backend no puede sacar de las piezas sueltas —tipo, prioridad,
 * ubicacion, linea de tiempo— y la etiqueta y clasificacion de cada pieza,
 * enlazada con su fichero por el nombre del `.fev`.
 */
object ManifiestoDelTelefono {

    const val ESQUEMA = "phone-incident/1"

    fun json(incidente: OfficerIncident, modeloDelTelefono: String): String {
        val timeline = JSONArray()
        incidente.timeline.forEach { entrada ->
            timeline.put(
                JSONObject()
                    .put("time", entrada.time)
                    .put("type", entrada.type.name)
                    .put("event", entrada.event),
            )
        }
        val evidencia = JSONArray()
        incidente.evidence.forEach { evidencia.put(pieza(it)) }

        val manifiesto = JSONObject()
            .put("schema", ESQUEMA)
            .put("source", "phone")
            .put("incident_id", incidente.id)
            .put("device_model", modeloDelTelefono)
            .put("officer_name", incidente.officerName)
            .put("officer_badge", incidente.officerNum)
            .put("type", incidente.type)
            .put("type_code", incidente.typeCode)
            .put("priority", incidente.priority.name.lowercase())
            .put("location", incidente.location)
            .put("date", incidente.date)
            .put("time", incidente.time)
            .put("duration", incidente.duration.orEmpty())
            .put("witness_count", incidente.witnessCount)
            .put("timeline", timeline)
            .put("evidence", evidencia)
        // Sin fix se omiten, nunca 0.0: eso es un punto real en el golfo de Guinea.
        incidente.latitude?.let { manifiesto.put("latitude", it) }
        incidente.longitude?.let { manifiesto.put("longitude", it) }
        return manifiesto.toString(2)
    }

    private fun pieza(registro: EvidenceRecord): JSONObject {
        val pieza = JSONObject()
            .put("evidence_id", registro.id)
            .put("media_type", registro.type.name.lowercase())
            .put("label", registro.label)
            .put("classification", registro.classification?.name.orEmpty())
            .put("time", registro.time)
            .put("device", registro.device.orEmpty())
        // Sin fichero es que se capturo con la bodycam o no se pudo cifrar: el
        // backend la vera como una pieza que no ha llegado, que es la verdad.
        registro.mediaUri?.let { pieza.put("filename", it) }
        hashReal(registro)?.let { pieza.put("sha256_plain", it) }
        return pieza
    }

    /**
     * Solo el hash de verdad. El de las piezas que no ha cifrado el telefono es
     * simulado (IncidentRepository.fakeHash) y mandarlo haria que el backend lo
     * comparase con algo y diese una falsa alarma de custodia.
     */
    private fun hashReal(registro: EvidenceRecord): String? =
        registro.hash.removePrefix("sha256:").takeIf { it.length == 64 && it.all(Char::isLetterOrDigit) }
}
