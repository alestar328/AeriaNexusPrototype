package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

/** Resultado de cambiar el PIN (workflow 5). */
enum class CambioDePin { OK, PIN_ACTUAL_INCORRECTO, BLOQUEADO, NO_GUARDADO }

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
 * La decision de autenticacion es del backend ("authentication decision made" es un
 * paso del servidor, no del APK): aqui se pide y se guarda su respuesta, el token de
 * [SesionBackend].
 */
class IdentityRepository(
    context: Context,
    private val pinLocal: PinLocal,
    private val credential: CredentialRepository,
    private val iam: IamClient,
    private val sesionBackend: SesionBackend,
    private val auditoria: AuditoriaLocal,
) {

    private val prefs = context.getSharedPreferences("aeria_trust", Context.MODE_PRIVATE)

    // Vive lo mismo que la app. Solo lo usa la acreditacion, que es red.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Identificador de la sesion en curso, para correlar sus eventos de auditoria (§14.1).
     * Lo pone el desbloqueo y lo quita el cierre. Es local: el id de sesion de verdad lo
     * emitira el backend con su token (workflow 28), y entonces sustituye a este.
     */
    @Volatile var sesionActual: String? = null
        private set

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
     * Aviso de que AeriaOne ha acreditado la sesion y ya hay token. Lo cablea
     * AppContainer para soltar la evidencia que esperaba sin poder subirse.
     */
    var alAcreditarSesion: (() -> Unit)? = null

    /**
     * Comprueba el PIN y abre la sesion (workflow 27, pasos 6 a 12).
     *
     * El recorrido de este lado: verificar el PIN contra [PinLocal], que no lo
     * guarda; aplicar el limite de intentos, y autorizar el uso de la clave del
     * agente. La sesion se abre en el acto como [OrigenDeSesion.SOLO_LOCAL] y pasa a
     * [OrigenDeSesion.ACREDITADA] cuando AeriaOne acepta la firma de su reto (ver
     * [acreditar]). Se hace en dos tiempos a proposito: sin cobertura el agente tiene
     * que poder trabajar, y esperar a la red dejaria el teclado colgado.
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
            auditarFallo(intentos, via = "unlock")
            return if (intentos.bloqueadoHasta > 0) UnlockResult.LOCKED_OUT else UnlockResult.WRONG_PIN
        }

        pinLocal.reiniciarIntentos()

        // Paso 8: a partir de aqui la clave del agente puede firmar, y solo a
        // partir de aqui.
        credential.autorizarUso()
        val sesion = UUID.randomUUID().toString()
        sesionActual = sesion
        auditoria.registrar(TipoEvento.SESION_ABIERTA)

        _status.update {
            it.copy(
                state = TrustState.ACTIVE,
                attemptsLeft = MAX_ATTEMPTS,
                lockedOutUntil = 0L,
                origenDeSesion = OrigenDeSesion.SOLO_LOCAL,
                emisorDelReto = null,
            )
        }
        acreditar(sesion)
        return UnlockResult.OK
    }

    /**
     * Pasos 11 a 21: pedir un reto a AeriaOne, firmarlo con la clave del agente recien
     * autorizada y cambiar la firma por el token de la sesion.
     *
     * Si falla —sin red, agente dado de baja, terminal revocado— el agente sigue
     * dentro con la sesion SOLO_LOCAL: nadie debe poder confundir eso con haberse
     * autenticado, pero tampoco dejarle sin terminal en mitad de la calle.
     */
    private fun acreditar(sesion: String) {
        val identidad = _status.value.identity ?: return
        if (!iam.configurado()) {
            Log.w(TAG, "sin servidor AeriaOne configurado: la sesion es SOLO local")
            return
        }
        scope.launch {
            try {
                val reto = iam.pedirReto(PropositoDelReto.LOGIN, identidad.userId)
                val firma = credential.firmarRetoDeSesion(reto.nonce)
                val emitida = iam.abrirSesion(
                    reto = reto,
                    firma = firma,
                    deviceId = identidad.deviceId,
                    appInstanceId = identidad.appInstanceId,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                guardarSiSigueAbierta(sesion, emitida, reto.emisor)
            } catch (e: Exception) {
                Log.e(TAG, "AeriaOne no ha acreditado la sesion: ${e.message}")
                auditoria.registrar(TipoEvento.SESION_NO_ACREDITADA, listOf("reason" to e.message))
            }
        }
    }

    /**
     * El agente pudo bloquear mientras el reto iba y volvia. Un token que llega tarde
     * se invalida en el backend en vez de guardarse: si no, la subida seguiria
     * funcionando con la pantalla bloqueada.
     */
    private fun guardarSiSigueAbierta(sesion: String, emitida: SesionEmitida, emisor: String) {
        if (sesionActual != sesion) {
            runCatching { iam.cerrarSesion(emitida.token) }
            return
        }
        sesionBackend.guardar(emitida)
        auditoria.registrar(TipoEvento.SESION_ACREDITADA, listOf("challenge_issuer" to emisor))
        _status.update { it.copy(origenDeSesion = OrigenDeSesion.ACREDITADA, emisorDelReto = emisor) }
        Log.i(TAG, "sesion acreditada por $emisor")
        alAcreditarSesion?.invoke()
    }

    /**
     * Comprueba el PIN actual antes de dejar cambiarlo (workflow 5).
     *
     * Cada fallo cuenta contra el mismo limite que el desbloqueo. Si no contase, un
     * telefono perdido con la sesion abierta dejaria probar PINs sin limite desde
     * esta pantalla. Y agotar la tanda aqui cierra la sesion: quien no sabe el PIN
     * no deberia seguir dentro.
     */
    fun comprobarPinActual(pin: String): CambioDePin {
        if (isLockedOut()) return CambioDePin.BLOQUEADO
        if (pinLocal.verificar(pin)) {
            pinLocal.reiniciarIntentos()
            _status.update { it.copy(attemptsLeft = MAX_ATTEMPTS, lockedOutUntil = 0L) }
            return CambioDePin.OK
        }
        val intentos = pinLocal.registrarFallo()
        _status.update {
            it.copy(attemptsLeft = intentos.intentosRestantes, lockedOutUntil = intentos.bloqueadoHasta)
        }
        auditarFallo(intentos, via = "pin_change")
        if (intentos.bloqueadoHasta > 0) {
            lock()
            return CambioDePin.BLOQUEADO
        }
        return CambioDePin.PIN_ACTUAL_INCORRECTO
    }

    /**
     * Sustituye el PIN. Vuelve a comprobar el actual aunque la pantalla ya lo haya
     * hecho: es el unico sitio que escribe el verificador, y no debe fiarse de que
     * quien lo llama haya pasado por el primer paso.
     */
    fun cambiarPin(actual: String, nuevo: String): CambioDePin {
        val comprobacion = comprobarPinActual(actual)
        if (comprobacion != CambioDePin.OK) return comprobacion
        if (!pinLocal.establecer(nuevo)) {
            auditoria.registrar(TipoEvento.PIN_CAMBIO_FALLIDO)
            return CambioDePin.NO_GUARDADO
        }
        auditoria.registrar(TipoEvento.PIN_CAMBIADO)
        return CambioDePin.OK
    }

    /** Un fallo de PIN, y si con el se agoto la tanda, el bloqueo que empieza. */
    private fun auditarFallo(intentos: EstadoIntentos, via: String) {
        if (intentos.bloqueadoHasta > 0) {
            auditoria.registrar(
                TipoEvento.PIN_BLOQUEO_TEMPORAL,
                listOf("via" to via, "until" to Instant.ofEpochMilli(intentos.bloqueadoHasta).toString()),
            )
        } else {
            auditoria.registrar(
                TipoEvento.PIN_INCORRECTO,
                listOf("via" to via, "attempts_left" to intentos.intentosRestantes.toString()),
            )
        }
    }

    /**
     * Fin de turno explicito (workflow 30): el mismo cierre que [lock], pero la
     * camara recibe el motivo verdadero. No es lo mismo que la sesion se cierre
     * sola que el agente diga que ha terminado.
     */
    fun terminarTurno() = lock(BindingPeriferico.MotivoDeFin.FIN_DE_TURNO)

    /**
     * Cierra la sesion y vuelve a bloqueado (workflow 30).
     *
     * Retirar la autorizacion es la mitad importante: si no, la clave del agente
     * seguiria firmando con la pantalla bloqueada.
     */
    fun lock(motivo: BindingPeriferico.MotivoDeFin = BindingPeriferico.MotivoDeFin.CIERRE_DE_SESION) {
        // El orden importa: primero se deshacen las ataduras, porque para firmar
        // su fin hace falta la clave del agente, y la linea siguiente la retira.
        alCerrarSesion?.invoke(motivo)
        credential.retirarAutorizacion()
        // El token caducaria solo, pero hasta entonces serviria para subir en nombre
        // de un agente que ya no esta de servicio.
        sesionBackend.olvidar()?.let { token ->
            scope.launch {
                runCatching { iam.cerrarSesion(token) }
                    .onFailure { Log.w(TAG, "no se pudo cerrar la sesion en AeriaOne: ${it.message}") }
            }
        }
        // Despues de los avisos, para que desatar y sellar queden en esta sesion, y
        // antes de olvidarla, para que el propio cierre tambien.
        auditoria.registrar(TipoEvento.SESION_CERRADA, listOf("reason" to motivo.name))
        sesionActual = null
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
