package com.delta.aeria_nexus_prototype.data.identity

/** En que punto esta la credencial del telefono respecto a su caducidad. */
enum class FaseDeCaducidad {
    /** Sin certificado que juzgar, o lejos de caducar. */
    VIGENTE,

    /** Caduca pronto: se avisa al agente para que la renueve antes. */
    POR_CADUCAR,

    /** Ya caduco, pero se esta grabando: se deja terminar. */
    EN_GRACIA,

    /** Caduco y no se graba nada: el telefono queda fuera de servicio. */
    CADUCADA,
}

data class EstadoDeCaducidad(
    val fase: FaseDeCaducidad = FaseDeCaducidad.VIGENTE,
    /** El notAfter mas temprano de los dos certificados; null si no hay ninguno. */
    val caducaEn: Long? = null,
)

/**
 * Que hace el telefono cuando su credencial caduca (workflow 53).
 *
 * Cuenta el primero que caduque de los dos certificados, el del terminal y el del
 * agente: con cualquiera de ellos vencido el backend ya no acredita la sesion.
 *
 * LA REGLA: una credencial caducada deja el telefono fuera de servicio, **salvo
 * mientras se graba**. Cortar una grabacion en mitad de una intervencion por una
 * fecha de un certificado seria perder evidencia por un tramite; lo que se graba
 * queda cifrado en la boveda igual que siempre. La gracia dura lo que dure la
 * grabacion en curso y no se puede alargar empezando otra: al pasar a
 * [FaseDeCaducidad.CADUCADA] la app se bloquea y ya no se puede empezar nada.
 *
 * LO QUE NO RESUELVE: la hora es la del reloj del telefono, que el agente puede
 * atrasar. La hora fiable es el workflow 64; hasta entonces, atrasar el reloj
 * retrasa el bloqueo, y el backend sigue rechazando el certificado igual.
 */
object PoliticaDeCaducidad {

    /** Con cuanta antelacion se avisa. Dos semanas dan para pedir la renovacion con calma. */
    const val AVISO_PREVIO_MILLIS = 14L * 24 * 60 * 60 * 1000

    fun evaluar(caducaEn: Long?, ahora: Long, grabando: Boolean): FaseDeCaducidad = when {
        caducaEn == null -> FaseDeCaducidad.VIGENTE
        ahora > caducaEn && grabando -> FaseDeCaducidad.EN_GRACIA
        ahora > caducaEn -> FaseDeCaducidad.CADUCADA
        caducaEn - ahora <= AVISO_PREVIO_MILLIS -> FaseDeCaducidad.POR_CADUCAR
        else -> FaseDeCaducidad.VIGENTE
    }

    /** Dias que quedan, redondeando hacia arriba: "caduca en 0 dias" no se lee bien. */
    fun diasRestantes(caducaEn: Long, ahora: Long): Long =
        ((caducaEn - ahora).coerceAtLeast(0) + DIA_MILLIS - 1) / DIA_MILLIS

    private const val DIA_MILLIS = 24L * 60 * 60 * 1000
}
