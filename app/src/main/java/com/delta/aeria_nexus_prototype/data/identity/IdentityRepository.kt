package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Identidad con la que el backend reconoce a este telefono. */
data class ProvisionedIdentity(
    /** Nombre de usuario de AeriaOne. El agente nunca lo teclea (workflow 27, paso 4). */
    val userId: String,
    /** Tenant: frontera de datos y de politica. */
    val tenant: String,
    /** AeriaOne BYOD Device ID, asignado en el alta (workflow 12, paso 8). */
    val deviceId: String,
    /** Identidad de ESTA instalacion, no de la app en general (workflow 22-23, paso 12). */
    val appInstanceId: String,
    /** Release aprobada de la que procede la instalacion. */
    val release: String,
)

/** Estado completo de confianza del terminal. Un solo objeto para no tener flujos sueltos. */
data class TrustStatus(
    val state: TrustState = TrustState.NOT_PROVISIONED,
    /** Solo cuando [state] es BLOCKED. */
    val blockReason: TrustBlockReason? = null,
    /** Solo cuando el telefono esta provisionado. */
    val identity: ProvisionedIdentity? = null,
    /** Intentos de PIN que quedan antes del proximo bloqueo temporal. */
    val attemptsLeft: Int = IdentityRepository.MAX_ATTEMPTS,
    /** Instante (epoch millis) hasta el que el teclado esta bloqueado; 0 si no lo esta. */
    val lockedOutUntil: Long = 0L,
)

/** Resultado de intentar desbloquear con el PIN. */
enum class UnlockResult { OK, WRONG_PIN, LOCKED_OUT }

/**
 * Custodia del estado de confianza del terminal.
 *
 * ATENCION: hoy no hay criptografia aqui. Esta clase es el hueco con la forma
 * exacta que dejaran los workflows 12 (alta del telefono), 4 (PIN) y 27-28
 * (desbloqueo y sesion); la UI ya trabaja contra la interfaz definitiva para que
 * al llegar la cripto no haya que rehacer pantallas. Lo que hoy se decide en
 * local pasara a decidirlo el backend, que es justo el punto del modelo IAM:
 * "authentication decision made" es un paso del servidor, no del APK.
 *
 * Lo que persiste en SharedPreferences no es secreto (estado, contador de fallos
 * y hora de fin del bloqueo). El verificador del PIN vivira en Keystore cuando se
 * implemente el workflow 4; hasta entonces el contador se puede borrar limpiando
 * los datos de la app, y eso es aceptable porque tampoco hay nada que proteger.
 */
class IdentityRepository(context: Context) {

    private val prefs = context.getSharedPreferences("aeria_trust", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(leerEstadoGuardado())
    val status: StateFlow<TrustStatus> = _status.asStateFlow()

    /**
     * Comprueba el PIN y abre la sesion.
     *
     * Cuando exista el workflow 27 esto sera: verificar el PIN en local, autorizar
     * la clave del Keystore, firmar el reto del backend y recibir el token. La
     * firma de la funcion no cambia.
     */
    fun unlock(pin: String): UnlockResult {
        if (isLockedOut()) return UnlockResult.LOCKED_OUT

        if (pin != PIN_DEMO) {
            registrarFallo()
            return UnlockResult.WRONG_PIN
        }

        prefs.edit()
            .putInt(CLAVE_FALLOS, 0)
            .putInt(CLAVE_BLOQUEOS, 0)
            .putLong(CLAVE_FIN_BLOQUEO, 0L)
            .apply()
        _status.update {
            it.copy(
                state = TrustState.ACTIVE,
                attemptsLeft = MAX_ATTEMPTS,
                lockedOutUntil = 0L,
            )
        }
        return UnlockResult.OK
    }

    /** Cierra la sesion y vuelve a bloqueado (workflow 30). */
    fun lock() {
        _status.update { it.copy(state = TrustState.LOCKED, blockReason = null) }
    }

    /** El agente ha arrancado el alta del terminal (workflow 12, paso 1). */
    fun altaEnCurso() {
        _status.update { it.copy(state = TrustState.ENROLLING) }
    }

    /**
     * Alta terminada (workflow 12, paso 21): el terminal ya tiene su certificado y
     * a partir de aqui arranca bloqueado como cualquier telefono en servicio.
     *
     * El Device ID es el que salio del alta; el usuario y el tenant siguen siendo
     * los de ejemplo porque los emite el IAM y todavia no hay a quien preguntar.
     */
    fun altaCompletada(deviceId: String) {
        prefs.edit()
            .putBoolean(CLAVE_PROVISIONADA, true)
            .putString(CLAVE_DEVICE_ID, deviceId)
            .apply()
        _status.update {
            it.copy(
                state = TrustState.LOCKED,
                blockReason = null,
                identity = IDENTIDAD_DEMO.copy(deviceId = deviceId),
            )
        }
    }

    /** Vuelve a terminal sin dar de alta. La identidad la destruye quien la creo. */
    fun deshacerAlta() {
        prefs.edit().remove(CLAVE_PROVISIONADA).remove(CLAVE_DEVICE_ID).apply()
        _status.update {
            it.copy(state = TrustState.NOT_PROVISIONED, blockReason = null, identity = null)
        }
    }

    /** true mientras el teclado sigue bloqueado por fallos consecutivos. */
    fun isLockedOut(): Boolean = System.currentTimeMillis() < _status.value.lockedOutUntil

    /**
     * Fuerza un estado concreto. Solo la usa el simulador de compilaciones debug:
     * sin backend no hay otra forma de recorrer las siete pantallas de arranque, y
     * ensenarlas a mano es exactamente lo que hace falta para la auditoria.
     */
    fun forzarEstado(state: TrustState, reason: TrustBlockReason? = null) {
        val provisionada = state != TrustState.NOT_PROVISIONED
        prefs.edit()
            .putBoolean(CLAVE_PROVISIONADA, provisionada)
            // Tambien se limpia el bloqueo temporal: si no, reaparece al
            // reiniciar la app y la demostracion se queda encallada.
            .putInt(CLAVE_FALLOS, 0)
            .putLong(CLAVE_FIN_BLOQUEO, 0L)
            .apply()
        _status.update {
            it.copy(
                state = state,
                blockReason = if (state == TrustState.BLOCKED) reason else null,
                identity = if (provisionada) IDENTIDAD_DEMO else null,
                attemptsLeft = MAX_ATTEMPTS,
                lockedOutUntil = 0L,
            )
        }
    }

    /** Suma un fallo y aplica el bloqueo temporal si se agotaron los intentos. */
    private fun registrarFallo() {
        val fallos = prefs.getInt(CLAVE_FALLOS, 0) + 1
        if (fallos < MAX_ATTEMPTS) {
            prefs.edit().putInt(CLAVE_FALLOS, fallos).apply()
            _status.update { it.copy(attemptsLeft = MAX_ATTEMPTS - fallos) }
            return
        }

        // Tanda agotada: el bloqueo crece con cada tanda para que probar el PIN a
        // ciegas deje de ser viable, pero sin llegar nunca a inutilizar el
        // terminal. Un agente que se equivoca al empezar el turno no puede
        // quedarse sin app durante el turno entero.
        val bloqueos = prefs.getInt(CLAVE_BLOQUEOS, 0) + 1
        val finBloqueo = System.currentTimeMillis() + duracionBloqueoMillis(bloqueos)
        prefs.edit()
            .putInt(CLAVE_FALLOS, 0)
            .putInt(CLAVE_BLOQUEOS, bloqueos)
            .putLong(CLAVE_FIN_BLOQUEO, finBloqueo)
            .apply()
        _status.update { it.copy(attemptsLeft = MAX_ATTEMPTS, lockedOutUntil = finBloqueo) }
    }

    private fun leerEstadoGuardado(): TrustStatus {
        val provisionada = prefs.getBoolean(CLAVE_PROVISIONADA, false)
        val finBloqueo = prefs.getLong(CLAVE_FIN_BLOQUEO, 0L)
        // El Device ID guardado es el que emitio el alta; si no hay (identidad
        // forzada desde el simulador) se cae al de ejemplo.
        val identidad = IDENTIDAD_DEMO.copy(
            deviceId = prefs.getString(CLAVE_DEVICE_ID, null) ?: IDENTIDAD_DEMO.deviceId,
        )
        return TrustStatus(
            state = if (provisionada) TrustState.LOCKED else TrustState.NOT_PROVISIONED,
            identity = if (provisionada) identidad else null,
            attemptsLeft = MAX_ATTEMPTS - prefs.getInt(CLAVE_FALLOS, 0),
            lockedOutUntil = if (finBloqueo > System.currentTimeMillis()) finBloqueo else 0L,
        )
    }

    companion object {

        /** Intentos por tanda antes del bloqueo temporal (workflow 27, paso 7). */
        const val MAX_ATTEMPTS = 5

        /** Digitos del PIN. Fijo, para poder validar solo al completarlo. */
        const val PIN_LENGTH = 6

        /**
         * PIN de la demostracion. No es un secreto y no pretende serlo: hasta el
         * workflow 4 no existe verificador real, y dejarlo a la vista evita el
         * espejismo de que aqui ya hay seguridad. Son los digitos de la placa
         * P-4471 rellenados a seis.
         */
        private const val PIN_DEMO = "004471"

        /**
         * Identidad de ejemplo. Los identificadores son los del documento de
         * ciberseguridad a proposito: quien audite el dia 15 los reconoce de
         * haberlos leido alli. El workflow 12 los sustituira por los que emita
         * el backend de verdad.
         */
        private val IDENTIDAD_DEMO = ProvisionedIdentity(
            userId = "cmendez.aeriaone.com",
            tenant = "QPD",
            deviceId = "DEV-92A71C",
            appInstanceId = "APPINST-8F27A91C",
            release = "1.5",
        )

        private const val CLAVE_PROVISIONADA = "provisionada"
        private const val CLAVE_DEVICE_ID = "device_id"
        private const val CLAVE_FALLOS = "fallos"
        private const val CLAVE_BLOQUEOS = "bloqueos"
        private const val CLAVE_FIN_BLOQUEO = "fin_bloqueo"

        /** 1 min, 5 min y 30 min a partir de la tercera tanda. */
        private fun duracionBloqueoMillis(bloqueos: Int): Long = when (bloqueos) {
            1 -> 60_000L
            2 -> 300_000L
            else -> 1_800_000L
        }
    }
}
