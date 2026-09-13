package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GafasPendientes"

/** Un video que esta en la tarjeta de las gafas y todavia no se ha traido. */
data class VideoPendiente(
    val nombre: String,
    /** Cuando lo cerraron las gafas, segun el reloj del TELEFONO. */
    val cerradoEnMillis: Long,
)

/**
 * Los videos que las gafas han grabado y siguen en su tarjeta.
 *
 * Existe porque **el video no llega solo**: se queda en las gafas y hay que ir a
 * buscarlo por su punto de acceso WiFi, que ademas esta apagado mientras graban.
 * Entre que la grabacion termina y alguien puede descargarla pueden pasar horas, y
 * el oficial no va a estar mirando el telefono, asi que la lista tiene que
 * sobrevivir a que la app se cierre o el terminal se reinicie. Por eso se guarda
 * en disco y no en memoria.
 *
 * **La fecha es la del telefono, no la de las gafas.** Medido el 2026-09-13: el
 * reloj de las gafas salta hacia atras al apagarlas, asi que ni el nombre del
 * fichero ni su `fileTime` sirven para fechar nada.
 */
class GafasPendientesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(FICHERO, Context.MODE_PRIVATE)

    private val _pendientes = MutableStateFlow(leer())
    val pendientes: StateFlow<List<VideoPendiente>> = _pendientes.asStateFlow()

    /** Anota un video recien cerrado. Repetir el mismo nombre no lo duplica. */
    fun anadir(nombre: String) {
        val actuales = _pendientes.value
        if (actuales.any { it.nombre == nombre }) {
            Log.i(TAG, "'$nombre' ya estaba anotado")
            return
        }
        guardar(actuales + VideoPendiente(nombre, System.currentTimeMillis()))
        Log.i(TAG, "pendiente de descargar: '$nombre' (${_pendientes.value.size} en total)")
    }

    /** Lo quita de la lista, una vez traido de verdad. */
    fun quitar(nombre: String) {
        guardar(_pendientes.value.filterNot { it.nombre == nombre })
    }

    private fun guardar(lista: List<VideoPendiente>) {
        val array = JSONArray()
        for (video in lista) {
            array.put(
                JSONObject()
                    .put(CAMPO_NOMBRE, video.nombre)
                    .put(CAMPO_INSTANTE, video.cerradoEnMillis),
            )
        }
        prefs.edit().putString(CLAVE, array.toString()).apply()
        _pendientes.value = lista
    }

    private fun leer(): List<VideoPendiente> {
        val crudo = prefs.getString(CLAVE, null) ?: return emptyList()
        return try {
            val array = JSONArray(crudo)
            (0 until array.length()).map { i ->
                val objeto = array.getJSONObject(i)
                VideoPendiente(
                    nombre = objeto.getString(CAMPO_NOMBRE),
                    cerradoEnMillis = objeto.getLong(CAMPO_INSTANTE),
                )
            }
        } catch (e: Exception) {
            // Una lista corrupta no puede impedir que la app arranque, pero perderla
            // en silencio seria perder evidencia sin que nadie se entere.
            Log.e(TAG, "lista de pendientes ilegible, se descarta: ${e.message}")
            emptyList()
        }
    }

    private companion object {
        const val FICHERO = "gafas_pendientes"
        const val CLAVE = "videos"
        const val CAMPO_NOMBRE = "nombre"
        const val CAMPO_INSTANTE = "instante"
    }
}
