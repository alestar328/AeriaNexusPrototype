package com.delta.aeria_nexus_prototype.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Volumen del tono que confirma al agente que su SOS ha salido.
 *
 * El nombre de cada valor es tambien lo que viaja a la bodycam en el comando
 * `SOS_TONE:<valor>`: no renombrarlos sin cambiar BodyCamServer a la vez.
 */
enum class TonoSos { OFF, LOW, HIGH }

/**
 * Ajustes del SOS que elige el propio agente. Hoy solo el tono: discreto por
 * defecto, porque un pitido fuerte puede delatar a un agente escondido, pero
 * con la opcion de subirlo (exterior, ruido) o de quitarlo del todo.
 */
class AjustesSos(context: Context) {

    private val prefs = context.getSharedPreferences("aeria_sos", Context.MODE_PRIVATE)

    private val _tono = MutableStateFlow(leerTono())
    val tono: StateFlow<TonoSos> = _tono.asStateFlow()

    fun cambiarTono(tono: TonoSos) {
        prefs.edit().putString(CLAVE_TONO, tono.name).apply()
        _tono.value = tono
    }

    // Un valor guardado que ya no existe en el enum no puede dejar al agente sin
    // SOS: se vuelve al valor por defecto.
    private fun leerTono(): TonoSos =
        TonoSos.entries.firstOrNull { it.name == prefs.getString(CLAVE_TONO, null) } ?: TonoSos.LOW

    private companion object {
        const val CLAVE_TONO = "tono_sos"
    }
}
