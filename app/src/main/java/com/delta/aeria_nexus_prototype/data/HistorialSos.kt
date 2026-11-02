package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Quien disparo el SOS. */
enum class OrigenSos { ESTE_TELEFONO, TELEFONO, BODYCAM }

/** Como termino: lo corto el emisor, o dejo de llegar sin aviso. */
enum class FinDeSos { TERMINADO, SENAL_PERDIDA }

data class RegistroSos(
    // uid de Agora + inicio: identifica la emergencia aunque el heartbeat la repita.
    val clave: String,
    val uid: Int,
    val officer: String,
    val origen: OrigenSos,
    val inicioMillis: Long,
    val finMillis: Long? = null,
    val fin: FinDeSos? = null,
)

/**
 * Historial de los SOS que este telefono ha visto o emitido, mas nuevo primero.
 *
 * Solo se anade: la app no ofrece ninguna forma de borrar un registro. Es la copia
 * local para cuando no se llega al backend; si se llega, manda el historial de
 * AeriaOne ([delBackend]), que tiene tambien los SOS de antes de instalar la app o
 * con el telefono apagado.
 *
 * Se guarda en noBackupFilesDir, fuera de la copia de Android. No lleva posiciones:
 * solo la placa del agente y las horas.
 */
class HistorialSos(
    context: Context,
    private val backend: HistorialSosBackend,
) {

    private val fichero = File(context.noBackupFilesDir, "sos_history.json")

    // Un solo hilo de escritura: dos guardados a la vez podrian dejar el fichero
    // con una version vieja encima de la nueva.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _registros = MutableStateFlow<List<RegistroSos>>(emptyList())
    val registros: StateFlow<List<RegistroSos>> = _registros.asStateFlow()

    // Ultima lectura buena del backend; null hasta la primera.
    private val _delBackend = MutableStateFlow<HistorialDelBackend?>(null)
    val delBackend: StateFlow<HistorialDelBackend?> = _delBackend.asStateFlow()

    init {
        scope.launch { _registros.value = leer() }
    }

    /** Pide el historial a AeriaOne. Si falla se conserva la ultima lectura buena. */
    suspend fun actualizarDelBackend(): Boolean = withContext(Dispatchers.IO) {
        val historial = backend.primeraPagina() ?: return@withContext false
        _delBackend.value = historial
        true
    }

    /**
     * Apunta un SOS nuevo. Un reenvio del heartbeat no duplica nada; si la
     * emergencia se habia dado por perdida y vuelve a oirse, se reabre.
     */
    fun registrarInicio(registro: RegistroSos) = modificar { lista ->
        val existente = lista.firstOrNull { it.clave == registro.clave }
        when {
            existente == null -> listOf(registro) + lista
            existente.fin == FinDeSos.SENAL_PERDIDA ->
                lista.map { if (it.clave == registro.clave) it.copy(finMillis = null, fin = null) else it }
            else -> lista
        }
    }

    /**
     * Cierra el ultimo SOS abierto del emisor [uid]. Un fin TERMINADO que llega tarde
     * corrige a uno dado por perdido: el emisor si lo corto, solo que no se le oyo.
     */
    fun registrarFin(uid: Int, finMillis: Long, fin: FinDeSos) = modificar { lista ->
        val abierto = lista.firstOrNull {
            it.uid == uid && (it.fin == null || (it.fin == FinDeSos.SENAL_PERDIDA && fin == FinDeSos.TERMINADO))
        } ?: return@modificar lista
        lista.map { if (it.clave == abierto.clave) it.copy(finMillis = finMillis, fin = fin) else it }
    }

    private fun modificar(cambio: (List<RegistroSos>) -> List<RegistroSos>) {
        scope.launch {
            val antes = _registros.value
            val despues = cambio(antes)
            if (despues === antes) return@launch
            _registros.value = despues
            guardar(despues)
        }
    }

    private fun leer(): List<RegistroSos> {
        if (!fichero.exists()) return emptyList()
        return try {
            val json = JSONArray(fichero.readText())
            (0 until json.length()).map { aRegistro(json.getJSONObject(it)) }
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo leer el historial de SOS", e)
            emptyList()
        } catch (e: JSONException) {
            apartarIlegible(e)
        } catch (e: IllegalArgumentException) {
            // Un origen o un fin que esta version no conoce.
            apartarIlegible(e)
        }
    }

    // El fichero ilegible se aparta en vez de pisarlo con el siguiente guardado:
    // el historial no se borra desde la app, tampoco por accidente.
    private fun apartarIlegible(e: Exception): List<RegistroSos> {
        Log.w(TAG, "Historial de SOS ilegible, se aparta", e)
        fichero.renameTo(File(fichero.parentFile, "sos_history.unreadable-${System.currentTimeMillis()}.json"))
        return emptyList()
    }

    private fun guardar(lista: List<RegistroSos>) {
        val json = JSONArray()
        lista.forEach { json.put(aJson(it)) }
        // Se escribe aparte y se renombra: un corte a mitad no deja el fichero roto.
        val temporal = File(fichero.parentFile, "${fichero.name}.tmp")
        try {
            temporal.writeText(json.toString())
            if (!temporal.renameTo(fichero)) Log.w(TAG, "No se pudo guardar el historial de SOS")
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo guardar el historial de SOS", e)
        }
    }

    private fun aJson(registro: RegistroSos) = JSONObject()
        .put("key", registro.clave)
        .put("uid", registro.uid)
        .put("officer", registro.officer)
        .put("source", registro.origen.name)
        .put("started_at", registro.inicioMillis)
        .apply {
            registro.finMillis?.let { put("ended_at", it) }
            registro.fin?.let { put("end", it.name) }
        }

    private fun aRegistro(json: JSONObject) = RegistroSos(
        clave = json.getString("key"),
        uid = json.getInt("uid"),
        officer = json.optString("officer"),
        origen = OrigenSos.valueOf(json.getString("source")),
        inicioMillis = json.getLong("started_at"),
        finMillis = if (json.has("ended_at")) json.getLong("ended_at") else null,
        fin = if (json.has("end")) FinDeSos.valueOf(json.getString("end")) else null,
    )

    private companion object {
        const val TAG = "HistorialSos"
    }
}
