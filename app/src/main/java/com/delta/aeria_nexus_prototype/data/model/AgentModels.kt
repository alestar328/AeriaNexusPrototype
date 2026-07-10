package com.delta.aeria_nexus_prototype.data.model

/**
 * Posicion de otro agente recibida por el data stream de Agora.
 * lastSeenMillis registra el ultimo mensaje recibido de ese agente: si pasa
 * demasiado tiempo sin noticias, el mapa lo marca como "sin senal".
 */
data class RemoteAgent(
    val uid: Int,
    val latitude: Double,
    val longitude: Double,
    val lastSeenMillis: Long,
)

/**
 * Alerta SOS emitida por otro dispositivo. La sessionKey (uid + inicio de la
 * sesion) es estable entre reenvios del heartbeat, y permite descartar
 * repeticiones de la misma emergencia.
 */
data class SosAlert(
    val sessionKey: String,
    val officer: String,
    val uid: Int,
    val startedAtMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
)

/** Cancelacion de un SOS previamente emitido, con la ultima posicion del emisor. */
data class SosCancel(
    val officer: String,
    val uid: Int,
    val timestampMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
)
