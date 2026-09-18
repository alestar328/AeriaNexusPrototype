package com.delta.aeria_nexus_prototype.data.identity

/**
 * Para que sirve un reto concreto. Los nombres son los que espera el backend en
 * `POST iam/challenge`.
 *
 * Un reto vale para una cosa y solo una. Si el mismo nonce sirviera para el alta y
 * para el desbloqueo, quien capturase una respuesta de alta podria presentarla como
 * un inicio de sesion: eso es exactamente lo que el modelo llama replay.
 */
enum class PropositoDelReto {
    /** Va dentro de la cadena de atestacion al crear la clave (workflow 12, paso 10). */
    ATESTACION_TERMINAL,

    /** Prueba de posesion de la clave del TERMINAL (workflow 12, paso 16). */
    POSESION_TERMINAL,

    /** Prueba de posesion de la clave del AGENTE (workflow 3, paso 14). */
    POSESION_AGENTE,

    /** Reto de autenticacion al desbloquear (workflow 27, pasos 11 y 12). */
    LOGIN,
}
