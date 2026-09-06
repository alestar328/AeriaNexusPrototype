package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "AeriaSesion"

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
    /** Con que respaldo se abrio la sesion (workflow 27, pasos 12 a 14). */
    val origenDeSesion: OrigenDeSesion = OrigenDeSesion.NINGUNA,
    /** Quien emitio el reto que se firmo al entrar, si lo hubo. */
    val emisorDelReto: String? = null,
)

/** Resultado de intentar desbloquear con el PIN. */
enum class UnlockResult { OK, WRONG_PIN, LOCKED_OUT }

/**
 * Con que respaldo se abrio la sesion en curso.
 *
 * La distincion no es cosmetica y no puede quedarse en un log: en el modelo, la
 * decision de autenticacion es del backend. Un PIN correcto sin reto que firmar
 * demuestra que quien tiene el telefono conoce el PIN, y nada mas; nadie ha
 * comprobado que la credencial siga siendo valida ni que el agente siga de alta.
 */
enum class OrigenDeSesion {
    /** No hay sesion abierta. */
    NINGUNA,

    /** PIN correcto y nadie enfrente: no hubo reto que firmar. */
    SOLO_LOCAL,

    /** PIN correcto y reto del backend firmado con la clave del agente. */
    ACREDITADA,
}

/**
 * Custodia del estado de confianza del terminal.
 *
 * Ya no decide nada por su cuenta sobre el PIN: la verificacion y el contador de
 * intentos son de [PinLocal], que los protege con criptografia de verdad. Lo que
 * esta clase hace es traducir eso a los estados por los que pasa la app.
 *
 * Lo que persiste aqui en SharedPreferences NO es secreto y no pretende serlo: en
 * que etapa del alta esta el terminal y los identificadores que ya son publicos
 * (Device ID y agente). Editar ese XML adelanta la pantalla, pero no consigue
 * abrirla: sin el PIN correcto no hay verificador que pase, y sin las claves del
 * Keystore no hay identidad que presentar.
 *
 * Lo que sigue faltando y es del backend: la decision de autenticacion. En el
 * modelo, "authentication decision made" es un paso del servidor, no del APK.
 */
class IdentityRepository(
    context: Context,
    private val pinLocal: PinLocal,
    private val credential: CredentialRepository,
    private val retos: RetoRepository,
) {

    private val prefs = context.getSharedPreferences("aeria_trust", Context.MODE_PRIVATE)

    init {
        // El contador de intentos vivia aqui en claro hasta el workflow 4. Ahora lo
        // custodia PinLocal, y estas claves ya no las lee nadie: se borran para que
        // quien abra el XML no crea que el limite de intentos sigue siendo un
        // numero editable. Dejarlas seria dar una impresion falsa de como funciona.
        prefs.edit()
            .remove("fallos")
            .remove("bloqueos")
            .remove("fin_bloqueo")
            .apply()
    }

    private val _status = MutableStateFlow(leerEstadoGuardado())
    val status: StateFlow<TrustStatus> = _status.asStateFlow()

    /**
     * Aviso de que la sesion se cierra, para que quien tenga ataduras vivas las
     * deshaga (workflow 34). Lo cablea AppContainer.
     *
     * Es una devolucion de llamada y no una dependencia directa a proposito: este
     * repositorio no tiene por que saber que existe una bodycam, y si manana hay
     * gafas la lista de cosas que deshacer no se escribe aqui.
     */
    var alCerrarSesion: ((BindingPeriferico.MotivoDeFin) -> Unit)? = null

    /**
     * Comprueba el PIN y abre la sesion (workflow 27, pasos 6 a 12).
     *
     * El recorrido completo de este lado: verificar el PIN contra [PinLocal], que
     * no lo guarda; aplicar el limite de intentos; autorizar el uso de la clave del
     * agente, y firmar con ella el reto que haya dejado el backend.
     *
     * Lo que sigue faltando y es del servidor son los pasos 13 a 21: validar el
     * certificado, comprobar que el agente sigue de alta, evaluar los permisos y
     * emitir el token de sesion. Por eso una sesion abierta aqui puede ser
     * [OrigenDeSesion.SOLO_LOCAL], y eso hay que enseñarlo, no esconderlo.
     */
    fun unlock(pin: String): UnlockResult {
        if (isLockedOut()) return UnlockResult.LOCKED_OUT

        if (!pinLocal.verificar(pin)) {
            val intentos = pinLocal.registrarFallo()
            _status.update {
                it.copy(
                    attemptsLeft = intentos.intentosRestantes,
                    lockedOutUntil = intentos.bloqueadoHasta,
                )
            }
            return if (intentos.bloqueadoHasta > 0) UnlockResult.LOCKED_OUT else UnlockResult.WRONG_PIN
        }

        pinLocal.reiniciarIntentos()

        // Paso 8: a partir de aqui la clave del agente puede firmar, y solo a
        // partir de aqui.
        credential.autorizarUso()
        val reto = firmarRetoDeEntrada()

        _status.update {
            it.copy(
                state = TrustState.ACTIVE,
                attemptsLeft = MAX_ATTEMPTS,
                lockedOutUntil = 0L,
                origenDeSesion = if (reto != null) {
                    OrigenDeSesion.ACREDITADA
                } else {
                    OrigenDeSesion.SOLO_LOCAL
                },
                emisorDelReto = reto?.emisor,
            )
        }
        return UnlockResult.OK
    }

    /**
     * Pasos 11 a 14: si el backend dejo un reto, se firma con la clave del agente
     * recien autorizada y la respuesta queda donde el backend pueda verificarla.
     *
     * Si no hay reto, el desbloqueo sigue adelante pero la sesion queda marcada
     * como SOLO_LOCAL. Es deliberado: sin backend el agente tiene que poder
     * trabajar, pero nadie debe poder confundir eso con haberse autenticado.
     */
    private fun firmarRetoDeEntrada(): Reto? {
        val reto = retos.consumir(PropositoDelReto.LOGIN) ?: run {
            Log.w(TAG, "desbloqueo sin reto del backend: la sesion es SOLO local")
            return null
        }
        return try {
            val firma = credential.firmarRetoDeSesion(reto.bytes)
            val agente = credential.agenteProvisionado() ?: "desconocido"
            retos.responder(reto, firma, agente)
            Log.i(TAG, "reto de ${reto.emisor} firmado como $agente: sesion acreditada")
            reto
        } catch (e: Exception) {
            // Que falle la firma no puede dejar al agente fuera del terminal: se
            // entra igual y se marca la sesion como local, que es la verdad.
            Log.e(TAG, "no se pudo firmar el reto de entrada", e)
            null
        }
    }

    /**
     * Cierra la sesion y vuelve a bloqueado (workflow 30).
     *
     * Retirar la autorizacion es la mitad importante: si no, la clave del agente
     * seguiria firmando con la pantalla bloqueada.
     */
    fun lock() {
        // El orden importa: primero se deshacen las ataduras, porque para firmar
        // su fin hace falta la clave del agente, y la linea siguiente la retira.
        alCerrarSesion?.invoke(BindingPeriferico.MotivoDeFin.CIERRE_DE_SESION)
        credential.retirarAutorizacion()
        _status.update {
            it.copy(
                state = TrustState.LOCKED,
                blockReason = null,
                origenDeSesion = OrigenDeSesion.NINGUNA,
                emisorDelReto = null,
            )
        }
    }

    /** El agente ha arrancado el alta del terminal (workflow 12, paso 1). */
    fun altaEnCurso() {
        _status.update { it.copy(state = TrustState.ENROLLING) }
    }

    /**
     * El telefono ya tiene su certificado (workflow 12, paso 21), pero el agente
     * todavia no tiene el suyo: el alta NO ha terminado.
     *
     * Es la consecuencia directa de la regla de no equivalencia del documento: el
     * terminal esta acreditado y aun asi no hay a quien pedirle un PIN, porque no
     * hay identidad de persona. Por eso se sigue en ENROLLING y no en LOCKED.
     */
    fun altaDeTerminalCompletada(deviceId: String) {
        prefs.edit()
            .putBoolean(CLAVE_PROVISIONADA, true)
            .putString(CLAVE_DEVICE_ID, deviceId)
            .apply()
        _status.update {
            it.copy(
                state = TrustState.ENROLLING,
                blockReason = null,
                identity = IDENTIDAD_DEMO.copy(deviceId = deviceId),
            )
        }
    }

    /**
     * Credencial del agente instalada (workflow 3, paso 15). A partir de aqui el
     * telefono arranca bloqueado como cualquiera en servicio.
     *
     * El identificador sale del nombre comun del certificado que emitio la CA, no
     * de una constante: es el primer dato de la identidad que ya no nos inventamos.
     */
    fun credencialCompletada(userId: String) {
        prefs.edit()
            .putBoolean(CLAVE_CREDENCIAL, true)
            .putString(CLAVE_USER_ID, userId)
            .apply()
        _status.update { estado ->
            estado.copy(
                // Con certificado pero sin PIN el alta aun no ha terminado: falta el
                // paso 13, que es lo unico que el agente pone de su parte.
                state = if (pinLocal.existe()) TrustState.LOCKED else TrustState.PIN_SETUP,
                blockReason = null,
                identity = (estado.identity ?: IDENTIDAD_DEMO).copy(userId = userId),
            )
        }
    }

    /** El agente ya ha elegido su PIN (workflow 4). Con esto el alta esta completa. */
    fun pinCompletado() {
        _status.update { it.copy(state = TrustState.LOCKED, blockReason = null) }
    }

    /** Si falta, el asistente de alta se queda en la pantalla de crear el PIN. */
    fun hayPin(): Boolean = pinLocal.existe()

    /** Vuelve a terminal sin dar de alta. Las claves las destruye quien las creo. */
    fun deshacerAlta() {
        prefs.edit()
            .remove(CLAVE_PROVISIONADA)
            .remove(CLAVE_DEVICE_ID)
            .remove(CLAVE_CREDENCIAL)
            .remove(CLAVE_USER_ID)
            .apply()
        _status.update {
            it.copy(state = TrustState.NOT_PROVISIONED, blockReason = null, identity = null)
        }
    }

    /** true mientras el teclado sigue bloqueado por fallos consecutivos. */
    fun isLockedOut(): Boolean = pinLocal.estadoDeIntentos().bloqueadoHasta > System.currentTimeMillis()

    /**
     * Fuerza un estado concreto. Solo la usa el simulador de compilaciones debug:
     * sin backend no hay otra forma de recorrer las siete pantallas de arranque, y
     * ensenarlas a mano es exactamente lo que hace falta para la auditoria.
     */
    fun forzarEstado(state: TrustState, reason: TrustBlockReason? = null) {
        val terminalDadoDeAlta = state != TrustState.NOT_PROVISIONED
        // ENROLLING es el unico tramo en el que la credencial todavia no existe.
        // En PIN_SETUP si existe: lo que falta es el PIN, no el certificado.
        val credencialEmitida = terminalDadoDeAlta && state != TrustState.ENROLLING
        prefs.edit()
            .putBoolean(CLAVE_PROVISIONADA, terminalDadoDeAlta)
            .putBoolean(CLAVE_CREDENCIAL, credencialEmitida)
            .apply()
        // Tambien se limpia el bloqueo temporal: si no, reaparece al reiniciar la
        // app y la demostracion se queda encallada.
        pinLocal.reiniciarIntentos()
        if (state == TrustState.PIN_SETUP) {
            // PIN_SETUP es justo el tramo sin PIN: con uno puesto, la pantalla no
            // tendria nada que pedir y el simulador se quedaria encallado.
            pinLocal.borrar()
        } else if (credencialEmitida && !pinLocal.existe()) {
            // Saltar al estado bloqueado sin haber pasado por el alta dejaria una
            // pantalla de PIN que no abre con ningun PIN. Solo ocurre en debug, que
            // es donde vive el simulador, y el PIN es el de la demostracion.
            pinLocal.establecer(PIN_DEMO)
        }
        _status.update {
            it.copy(
                state = state,
                blockReason = if (state == TrustState.BLOCKED) reason else null,
                identity = if (terminalDadoDeAlta) IDENTIDAD_DEMO else null,
                attemptsLeft = MAX_ATTEMPTS,
                lockedOutUntil = 0L,
            )
        }
    }

    private fun leerEstadoGuardado(): TrustStatus {
        val terminalDadoDeAlta = prefs.getBoolean(CLAVE_PROVISIONADA, false)
        val credencialEmitida = prefs.getBoolean(CLAVE_CREDENCIAL, false)
        val intentos = pinLocal.estadoDeIntentos()
        // Device ID y agente son los que emitieron el alta y la CA; si no hay
        // (identidad forzada desde el simulador) se cae a los de ejemplo.
        val identidad = IDENTIDAD_DEMO.copy(
            deviceId = prefs.getString(CLAVE_DEVICE_ID, null) ?: IDENTIDAD_DEMO.deviceId,
            userId = prefs.getString(CLAVE_USER_ID, null) ?: IDENTIDAD_DEMO.userId,
        )
        val estado = when {
            !terminalDadoDeAlta -> TrustState.NOT_PROVISIONED
            // El alta se reanuda por la etapa que falte en vez de repetir las
            // anteriores: certificado del agente, y despues PIN.
            !credencialEmitida -> TrustState.ENROLLING
            !pinLocal.existe() -> TrustState.PIN_SETUP
            else -> TrustState.LOCKED
        }
        return TrustStatus(
            state = estado,
            identity = if (terminalDadoDeAlta) identidad else null,
            attemptsLeft = intentos.intentosRestantes,
            lockedOutUntil = intentos.bloqueadoHasta,
        )
    }

    companion object {

        /** Intentos por tanda antes del bloqueo temporal (workflow 27, paso 7). */
        const val MAX_ATTEMPTS = 5

        /** Digitos del PIN. Fijo, para poder validar solo al completarlo. */
        const val PIN_LENGTH = 6

        /**
         * PIN que se le pone al terminal cuando el simulador de compilaciones debug
         * salta al estado bloqueado sin pasar por el alta. Ya no es el verificador
         * de nadie: el PIN real lo elige el agente y lo custodia [PinLocal].
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

        /** El TELEFONO tiene certificado (workflow 12). */
        private const val CLAVE_PROVISIONADA = "provisionada"

        /** El AGENTE tiene certificado (workflow 3). Son dos marcas porque son dos identidades. */
        private const val CLAVE_CREDENCIAL = "credencial"

        private const val CLAVE_DEVICE_ID = "device_id"
        private const val CLAVE_USER_ID = "user_id"
    }
}
