package com.delta.aeria_nexus_prototype.data.transcript

import android.os.Build
import com.delta.aeria_nexus_prototype.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * El fichero de transcripcion tal y como lo fija el contrato comun con la bodycam:
 * docs/TRANSCRIPT-FORMAT.md del repo BodyCamServer (schema falconone.transcript/1).
 * El backend parsea el mismo formato venga de donde venga, asi que aqui no se
 * anade ni se renombra nada sin cambiar antes el contrato para las dos apps.
 */
object TranscripcionJson {

    const val ESQUEMA = "falconone.transcript/1"

    enum class Estado(val valor: String) { COMPLETE("complete"), NO_SPEECH("no_speech"), FAILED("failed") }

    /** Lo que se sabe de la grabacion antes de transcribirla. Es el trabajo pendiente en disco. */
    data class Origen(
        val sha256Plain: String,
        val incidentId: String?,
        val duracion: Double?,
        val grabadaEnMillis: Long,
        /** `phone` o `falconone`: quien grabo, que no siempre es quien transcribe. */
        val grabadaPor: String,
    )

    /** Lo que la boveda ensena de una transcripcion, sea del telefono o de la bodycam. */
    data class Leida(
        val incidentId: String?,
        /** `phone`, `falconone` o `bodycam`: de donde viene, que es lo primero que se ve. */
        val grabadaPor: String,
        val grabadaEn: String?,
        val estado: String,
        val error: String?,
        val idioma: String?,
        val segmentos: List<MotorWhisper.Segmento>,
    )

    /** Null si no es una transcripcion de este esquema. Los campos que no conoce se ignoran. */
    fun leer(texto: String): Leida? = try {
        val json = JSONObject(texto)
        if (json.optString("schema") != ESQUEMA) null else leerCampos(json)
    } catch (e: Exception) {
        null
    }

    private fun leerCampos(json: JSONObject): Leida {
        val fuente = json.optJSONObject("source")
        val lista = json.optJSONArray("segments") ?: JSONArray()
        val segmentos = (0 until lista.length()).map { i ->
            val segmento = lista.getJSONObject(i)
            MotorWhisper.Segmento(segmento.getDouble("start"), segmento.getDouble("end"), segmento.getString("text"))
        }
        return Leida(
            incidentId = json.optString("incident_id").takeUnless { json.isNull("incident_id") || it.isEmpty() },
            // recorded_by es opcional: si falta, quien grabo es quien transcribio.
            grabadaPor = fuente?.optString("recorded_by")?.ifEmpty { null }
                ?: json.optJSONObject("device")?.optString("app").orEmpty(),
            grabadaEn = fuente?.optString("recorded_at")?.ifEmpty { null },
            estado = json.optString("status"),
            error = json.optString("error").takeUnless { json.isNull("error") || it.isEmpty() },
            idioma = json.optString("language").takeUnless { json.isNull("language") || it.isEmpty() },
            segmentos = segmentos,
        )
    }

    fun construir(
        origen: Origen,
        estado: Estado,
        error: String?,
        resultado: MotorWhisper.Resultado?,
        segundosDeProceso: Double,
    ): JSONObject {
        val segmentos = resultado?.segmentos.orEmpty()
        return JSONObject()
            .put("schema", ESQUEMA)
            .put("incident_id", origen.incidentId ?: JSONObject.NULL)
            .put("source", fuente(origen))
            .put("device", JSONObject().put("app", "phone").put("device_model", Build.MODEL))
            .put("engine", motor())
            .put("created_at", fechaUtc(System.currentTimeMillis()))
            .put("processing_seconds", redondear(segundosDeProceso))
            .put("status", estado.valor)
            .put("error", error ?: JSONObject.NULL)
            .put("language", resultado?.idioma ?: JSONObject.NULL)
            .put("language_probability", resultado?.probabilidadIdioma?.let(::redondear) ?: JSONObject.NULL)
            .put("text", segmentos.joinToString(" ") { it.texto })
            .put("segments", lista(segmentos))
    }

    private fun fuente(origen: Origen) = JSONObject()
        .put("sha256_plain", origen.sha256Plain)
        .put("duration", origen.duracion ?: JSONObject.NULL)
        .put("recorded_at", fechaUtc(origen.grabadaEnMillis))
        .put("recorded_by", origen.grabadaPor)

    private fun motor() = JSONObject()
        .put("name", "whisper.cpp")
        // Sin libreria (ABI o CPU no soportada) no hay version que preguntar.
        .put("version", if (WhisperNativo.motivoNoDisponible == null) WhisperNativo.version() else "unavailable")
        .put("model", BuildConfig.WHISPER_MODELO)
        .put("model_sha256", BuildConfig.WHISPER_MODELO_SHA256)

    private fun lista(segmentos: List<MotorWhisper.Segmento>): JSONArray {
        val lista = JSONArray()
        segmentos.forEachIndexed { indice, segmento ->
            lista.put(
                JSONObject()
                    .put("id", indice)
                    .put("start", segmento.inicio)
                    .put("end", segmento.fin)
                    .put("text", segmento.texto)
            )
        }
        return lista
    }

    fun fechaUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun redondear(valor: Double): Double = Math.round(valor * 100) / 100.0
}
