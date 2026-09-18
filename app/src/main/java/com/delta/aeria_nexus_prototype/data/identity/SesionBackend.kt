package com.delta.aeria_nexus_prototype.data.identity

/**
 * Token de la sesion abierta con AeriaOne, el que va en `Authorization: Bearer`.
 *
 * Vive solo en memoria, igual que la autorizacion de la clave del agente: si el
 * proceso muere, el agente vuelve a poner el PIN y se pide otro. Guardarlo en disco
 * dejaria una credencial de turno completo al alcance de quien copie la app.
 *
 * Lo abre y lo cierra [IdentityRepository]; la subida y los avisos de SOS solo lo leen.
 */
class SesionBackend {

    @Volatile private var actual: SesionEmitida? = null

    /** El token si hay sesion y no ha caducado; null si no, y entonces no se sube nada. */
    fun token(): String? = actual
        ?.takeIf { System.currentTimeMillis() < it.caducaEnMillis }
        ?.token

    fun guardar(sesion: SesionEmitida) {
        actual = sesion
    }

    /** Olvida la sesion y devuelve el token que habia, para poder invalidarlo en el backend. */
    fun olvidar(): String? {
        val token = actual?.token
        actual = null
        return token
    }
}
