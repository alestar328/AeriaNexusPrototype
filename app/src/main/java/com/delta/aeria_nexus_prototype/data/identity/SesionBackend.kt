package com.delta.aeria_nexus_prototype.data.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Token de la sesion abierta con AeriaOne, el que va en `Authorization: Bearer`.
 *
 * Vive solo en memoria, igual que la autorizacion de la clave del agente: si el
 * proceso muere, el agente vuelve a poner el PIN y se pide otro. Guardarlo en disco
 * dejaria una credencial de turno completo al alcance de quien copie la app.
 *
 * Lo abre y lo cierra [IdentityRepository]; la subida y los avisos de SOS solo lo leen,
 * y la bodycam recibe una copia mientras dure (ver BodycamRepository.prestarToken).
 */
class SesionBackend {

    private val _actual = MutableStateFlow<SesionEmitida?>(null)

    /** La sesion en curso, o null. Emite al abrirla y al cerrarla. */
    val actual: StateFlow<SesionEmitida?> = _actual.asStateFlow()

    /** El token si hay sesion y no ha caducado; null si no, y entonces no se sube nada. */
    fun token(): String? = _actual.value
        ?.takeIf { System.currentTimeMillis() < it.caducaEnMillis }
        ?.token

    fun guardar(sesion: SesionEmitida) {
        _actual.value = sesion
    }

    /** Olvida la sesion y devuelve el token que habia, para poder invalidarlo en el backend. */
    fun olvidar(): String? {
        val token = _actual.value?.token
        _actual.value = null
        return token
    }
}
