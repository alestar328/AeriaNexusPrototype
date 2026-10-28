package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.AlmacenDeInvitacion
import com.delta.aeria_nexus_prototype.data.identity.AltaDeTerminal
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.DeviceAttributes
import com.delta.aeria_nexus_prototype.data.identity.DevicePosture
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IamClient
import com.delta.aeria_nexus_prototype.data.identity.IamException
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.InvitacionDeAlta
import com.delta.aeria_nexus_prototype.data.identity.NivelClave
import com.delta.aeria_nexus_prototype.data.identity.PropositoDelReto
import com.delta.aeria_nexus_prototype.data.identity.QrNoValido
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PasoEstado {
    PENDIENTE,
    EN_CURSO,
    HECHO,

    /** Hecho, pero con algo que el backend tendra que valorar. No detiene el alta. */
    AVISO,
    FALLIDO,
}

/**
 * Las dos mitades del alta, en el orden en que ocurren.
 *
 * Son dos y no una porque son dos identidades distintas: primero se acredita el
 * telefono (workflow 12) y despues la persona que lo va a usar (workflow 3). Entre
 * una y otra el terminal ya tiene certificado y todavia no sirve para nada, que es
 * exactamente lo que dice el modelo.
 *
 * El PIN es el tramo siguiente (workflow 4) y tiene pantalla y ViewModel propios,
 * porque no es una lista de pasos que ocurren solos sino lo unico que el agente
 * decide. Ver [PinSetupViewModel].
 */
enum class FaseAlta { TERMINAL, AGENTE }

data class PasoAlta(
    val titulo: String,
    val estado: PasoEstado = PasoEstado.PENDIENTE,
    val detalle: String? = null,
)

data class EnrollmentUiState(
    val fase: FaseAlta = FaseAlta.TERMINAL,
    val iniciada: Boolean = false,
    val pasos: List<PasoAlta> = PASOS_TERMINAL,
    val mensajeError: String? = null,

    /** La fase del agente espera a que el agente teclee su identificador (alta sin QR). */
    val pideAgente: Boolean = false,
    val agenteId: String = "",
    val errorDeAgenteId: String? = null,

    /** El lector del QR de alta esta abierto a pantalla completa. */
    val escaneando: Boolean = false,

    /** Hay un QR de alta guardado: el alta sigue con el sin preguntar nada. */
    val hayQr: Boolean = false,

    /** AeriaOne ha rechazado el codigo del QR: hace falta otro, reintentar no sirve. */
    val pideQrNuevo: Boolean = false,

    /**
     * Solo en depuracion con `api_url` en upload.conf: se puede dar de alta sin QR,
     * tecleando el identificador como antes del 19-oct-2026.
     */
    val permiteSinQr: Boolean = false,
)

/**
 * Asistente de alta (workflows 12 y 3) contra el IAM de AeriaOne.
 *
 * Desde el 19-oct-2026 empieza escaneando el QR que genera el panel (iam-session.md
 * §4.4): de ahi salen el servidor, el agente y un codigo de un solo uso, y el agente
 * no teclea nada mas que el PIN.
 *
 * Los 21 pasos del alta del terminal y los 16 de la credencial del agente se
 * agrupan en unos pocos que el agente pueda seguir. No es cosmetica: el alta
 * puede tardar y puede fallar, y si falla hay que poder decir en cual de las
 * cosas fallo sin leer un log.
 */
class EnrollmentViewModel(
    private val enrollment: EnrollmentRepository,
    private val credential: CredentialRepository,
    private val identity: IdentityRepository,
    private val iam: IamClient,
    private val invitaciones: AlmacenDeInvitacion,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EnrollmentUiState(
            hayQr = invitaciones.actual() != null,
            permiteSinQr = iam.servidorDesdeFichero(),
        ),
    )
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    init {
        // Con el telefono ya acreditado solo falta el agente, y se sigue por ahi en
        // vez de rehacer una clave que AeriaOne ya ha certificado. Con el QR guardado
        // es la app que murio entre las dos fases (§4.4): se sigue sola.
        if (enrollment.altaCompleta()) {
            val invitacion = invitaciones.actual()
            if (invitacion != null) enrolarAgente(invitacion.userId) else pedirAgente()
        }
    }

    /**
     * El boton principal. Sin QR guardado lo primero es escanearlo; con QR, arranca
     * el alta o reintenta la fase que fallo.
     */
    fun onBotonPrincipal() {
        val estado = _uiState.value
        when {
            estado.iniciada -> Unit
            estado.pideQrNuevo -> abrirEscaner()
            estado.pideAgente -> continuarConElFormulario()
            invitaciones.actual() == null -> abrirEscaner()
            else -> iniciar()
        }
    }

    fun abrirEscaner() {
        if (_uiState.value.iniciada) return
        _uiState.update { it.copy(escaneando = true) }
    }

    fun cerrarEscaner() {
        _uiState.update { it.copy(escaneando = false) }
    }

    /**
     * Texto leido por [EscanerDeQr]. Se guarda entero antes de nada: el alta tiene dos
     * fases y la app puede morir entre ellas (§4.4).
     */
    fun onQrLeido(texto: String) {
        val invitacion = try {
            InvitacionDeAlta.desdeQr(texto)
        } catch (e: QrNoValido) {
            _uiState.update { it.copy(escaneando = false, mensajeError = e.message) }
            return
        }
        // Un terminal dado de alta contra otro servidor no sirve para este QR: su
        // certificado lo firmo otra CA. Se descarta y se empieza de cero.
        if (enrollment.altaCompleta() && !anclaInstaladaEsLaDe(invitacion)) {
            enrollment.deshacerAlta()
            credential.borrarCredencial()
            identity.deshacerAlta()
        }
        invitaciones.guardar(invitacion)
        _uiState.update {
            it.copy(
                escaneando = false,
                hayQr = true,
                pideQrNuevo = false,
                pideAgente = false,
                mensajeError = null,
                fase = FaseAlta.TERMINAL,
                pasos = PASOS_TERMINAL,
            )
        }
        iniciar()
    }

    private fun anclaInstaladaEsLaDe(invitacion: InvitacionDeAlta): Boolean {
        val pem = enrollment.anclaDePerifericosPem() ?: return false
        return invitacion.esLaCaDeTerminales(pem)
    }

    /**
     * Arranque del alta, y reintento de la fase que fallo. Sin QR solo se llega aqui
     * en depuracion, con el servidor de upload.conf.
     */
    fun iniciar() {
        val estado = _uiState.value
        if (estado.iniciada) return
        val invitacion = invitaciones.actual()
        if (enrollment.altaCompleta()) {
            // Sin QR, tras un fallo se vuelve al formulario en vez de reintentar a
            // ciegas: lo mas probable es una errata en el identificador.
            when {
                invitacion != null -> enrolarAgente(invitacion.userId)
                estado.pideAgente -> continuarConElFormulario()
                else -> pedirAgente()
            }
            return
        }
        _uiState.update {
            it.copy(fase = FaseAlta.TERMINAL, iniciada = true, mensajeError = null, pasos = PASOS_TERMINAL)
        }
        identity.altaEnCurso()

        viewModelScope.launch {
            try {
                val tenant = invitacion?.tenant ?: TENANT_SIN_QR
                val atributos = recogerDatos()
                val postura = mirarPostura()
                generarClaveDelTerminal()
                val csr = crearPeticionDelTerminal(tenant)
                val alta = entregarPeticionDelTerminal(csr, atributos, postura)
                comprobarServidor(alta, invitacion)
                instalarIdentidadDelTerminal(alta)
                if (invitacion != null) enrolarAgente(invitacion.userId) else pedirAgente()
            } catch (e: Exception) {
                marcarFalloEnPasoActual(e.message ?: "Unexpected error")
            }
        }
    }

    /** Workflow 12, paso 3. */
    private suspend fun recogerDatos(): DeviceAttributes {
        enCurso(DATOS)
        val atributos = withContext(Dispatchers.IO) { enrollment.atributos() }
        hecho(
            DATOS,
            "${atributos.manufacturer} ${atributos.model} · Android ${atributos.androidRelease} · " +
                "patch ${atributos.securityPatch}",
        )
        return atributos
    }

    /** Pasos 5 y 6: se informa, no se decide. La elegibilidad es del backend (paso 4). */
    private suspend fun mirarPostura(): DevicePosture {
        enCurso(POSTURA)
        val postura = withContext(Dispatchers.IO) { enrollment.postura() }
        when {
            !postura.keystoreDisponible ->
                throw IllegalStateException("This phone has no Android Keystore")

            postura.indiciosDeManipulacion.isNotEmpty() -> aviso(
                POSTURA,
                "Reported to AeriaOne: " + postura.indiciosDeManipulacion.joinToString(", "),
            )

            postura.esEmulador -> aviso(POSTURA, "Reported to AeriaOne: emulator")

            else -> hecho(POSTURA, "Keystore available · no tampering indicators found")
        }
        return postura
    }

    /**
     * Paso 10. El reto de atestacion lo emite AeriaOne y viaja dentro de la cadena
     * de atestacion: es lo que impide presentar la atestacion de otro terminal o de
     * otro dia.
     */
    private suspend fun generarClaveDelTerminal() {
        enCurso(CLAVE)
        val reto = withContext(Dispatchers.IO) {
            iam.pedirReto(PropositoDelReto.ATESTACION_TERMINAL, enrollment.deviceId())
        }
        val resultado = withContext(Dispatchers.Default) { enrollment.generarClave(reto.nonce) }
        val atestacion = enrollment.certificadosDeAtestacion()
        val sufijo = if (atestacion <= 1) {
            " · no attestation"
        } else {
            " · attestation chain of $atestacion, challenged by ${reto.emisor}"
        }
        describirNivelDeClave(CLAVE, resultado.nivel, sufijo)
    }

    /** Paso 11. El Device ID que va dentro es una propuesta: lo decide AeriaOne. */
    private suspend fun crearPeticionDelTerminal(tenant: String): String {
        enCurso(PETICION)
        val csr = withContext(Dispatchers.Default) { enrollment.crearCsr(enrollment.deviceId(), tenant) }
        hecho(PETICION, ALGORITMO_VISIBLE)
        return csr
    }

    /** Pasos 12 a 14: AeriaOne registra el terminal y firma su certificado. */
    private suspend fun entregarPeticionDelTerminal(
        csr: String,
        atributos: DeviceAttributes,
        postura: DevicePosture,
    ): AltaDeTerminal {
        enCurso(ENTREGA)
        val alta = withContext(Dispatchers.IO) { iam.enrolarTerminal(csr, atributos, postura) }
        hecho(ENTREGA, "Certificate issued by AeriaOne")
        return alta
    }

    /**
     * §4.4, antes de adoptar nada y antes de mandar el codigo del QR: la CA que manda
     * el servidor es la del QR (huella) y es la que firmo nuestro certificado (firma).
     * Si falla no se instala nada y el telefono sigue sin dar de alta.
     */
    private suspend fun comprobarServidor(alta: AltaDeTerminal, invitacion: InvitacionDeAlta?) {
        enCurso(SERVIDOR)
        if (invitacion == null) {
            aviso(SERVIDOR, "Not checked: enrolled without QR (debug, upload.conf)")
            return
        }
        withContext(Dispatchers.Default) {
            invitacion.comprobarCaDeTerminales(alta.anclaDePerifericosPem, alta.cadenaPem)
        }
        hecho(SERVIDOR, "CA fingerprint and signature match the enrollment QR")
    }

    /** Pasos 8 y 15: se adopta el Device ID asignado y se instala el certificado. */
    private suspend fun instalarIdentidadDelTerminal(alta: AltaDeTerminal) {
        enCurso(IDENTIDAD)
        withContext(Dispatchers.IO) {
            enrollment.adoptarDeviceId(alta.deviceId)
            enrollment.instalarCertificado(alta.cadenaPem)
            alta.anclaDePerifericosPem?.let(enrollment::instalarAnclaDePerifericos)
        }
        // El telefono queda acreditado, pero el alta NO ha terminado: falta la
        // credencial del agente, que es el workflow 3.
        identity.altaDeTerminalCompletada(alta.deviceId)
        hecho(IDENTIDAD, "${alta.deviceId} · assigned by AeriaOne")
    }

    /**
     * Segunda mitad sin QR (solo depuracion): el agente teclea su identificador entero,
     * tal como se lo da su administrador al darle de alta en el panel.
     */
    private fun pedirAgente() {
        _uiState.update {
            it.copy(
                fase = FaseAlta.AGENTE,
                iniciada = false,
                pideAgente = true,
                mensajeError = null,
                pasos = PASOS_AGENTE,
            )
        }
    }

    /**
     * El identificador va en el nombre comun del certificado y tiene que coincidir
     * letra a letra con el del perfil: se guarda ya en minusculas y sin espacios.
     */
    fun onAgenteIdChange(texto: String) {
        val limpio = texto.filterNot { it.isWhitespace() }.lowercase()
        _uiState.update { it.copy(agenteId = limpio, errorDeAgenteId = null) }
    }

    private fun continuarConElFormulario() {
        val userId = _uiState.value.agenteId
        if (!FORMATO_DE_AGENTE.matches(userId)) {
            _uiState.update { it.copy(errorDeAgenteId = ERROR_DE_FORMATO) }
            return
        }
        enrolarAgente(userId)
    }

    /** Segunda mitad: la credencial del agente (workflow 3). */
    private fun enrolarAgente(userId: String) {
        _uiState.update {
            it.copy(
                fase = FaseAlta.AGENTE,
                iniciada = true,
                pideAgente = false,
                mensajeError = null,
                pasos = PASOS_AGENTE,
            )
        }

        viewModelScope.launch {
            try {
                val invitacion = invitaciones.actual()
                presentarAlAgente(userId, invitacion)
                generarClaveDelAgente(userId)
                val csr = crearPeticionDelAgente(userId, invitacion?.tenant ?: TENANT_SIN_QR)
                entregarPeticionDelAgente(csr, userId, invitacion)
            } catch (e: Exception) {
                marcarFalloEnPasoActual(mensajeDeFallo(e))
                val codigo = (e as? IamException)?.codigo
                if (codigo in CODIGOS_DE_QR_GASTADO && invitaciones.actual() != null) {
                    _uiState.update { it.copy(pideQrNuevo = true) }
                }
            }
        }
    }

    /**
     * Los rechazos que el agente puede entender y resolver se dicen en claro. Se
     * decide por el `code` del contrato (§5.7), nunca leyendo el texto del backend.
     */
    private fun mensajeDeFallo(e: Exception): String = when ((e as? IamException)?.codigo) {
        "profile_not_found" -> "AeriaOne has no officer with that ID. Check it and try again."
        "device_not_enrolled" -> "AeriaOne does not recognise this phone. Contact your administrator."
        "profile_suspended" -> "This officer profile is suspended. Contact your administrator."
        "profile_revoked" -> "This officer profile has been revoked. Contact your administrator."
        // No es errata ni fallo de red: el perfil ya se enrolo una vez y repetirlo lo
        // tiene que autorizar un admin (iam-session.md §4.2). Con el alta por QR, el
        // permiso ES un QR nuevo (§4.4).
        "reenrollment_not_authorized" ->
            "This officer ID has already been enrolled. Ask your supervisor for a new " +
                "enrollment QR for this phone."
        // §4.4: el QR ya no vale. Reintentar no lo arregla: hace falta otro.
        "enrollment_code_invalid", "enrollment_code_expired", "enrollment_code_required" ->
            "This enrollment QR is no longer valid. Ask your supervisor for a new QR."
        else -> e.message ?: "Unexpected error"
    }

    /**
     * Pasos 1 y 2: quien es el agente, y AeriaOne comprueba que existe. La caducidad
     * del QR solo se avisa: decide el backend, el reloj del telefono puede ir mal.
     */
    private suspend fun presentarAlAgente(userId: String, invitacion: InvitacionDeAlta?) {
        enCurso(AGENTE_IDENTIDAD)
        val ahora = System.currentTimeMillis()
        when {
            invitacion == null ->
                aviso(AGENTE_IDENTIDAD, "$userId · typed by the officer, must exist in AeriaOne")
            invitacion.codigo != null && invitacion.caducaEnMillis in 1 until ahora ->
                aviso(AGENTE_IDENTIDAD, "$userId · from the enrollment QR, which may have expired")
            else -> hecho(AGENTE_IDENTIDAD, "$userId · from the enrollment QR")
        }
    }

    /** Paso 6: el segundo par, que nunca es el del telefono. Al reintentar se conserva. */
    private suspend fun generarClaveDelAgente(userId: String) {
        enCurso(AGENTE_CLAVE)
        val nivel = withContext(Dispatchers.Default) { credential.generarClave(userId) }
        describirNivelDeClave(AGENTE_CLAVE, nivel, sufijo = " · separate from the device key")
    }

    /** Paso 7. */
    private suspend fun crearPeticionDelAgente(userId: String, tenant: String): String {
        enCurso(AGENTE_PETICION)
        val csr = withContext(Dispatchers.Default) { credential.crearCsr(userId, tenant) }
        hecho(AGENTE_PETICION, ALGORITMO_VISIBLE)
        return csr
    }

    /**
     * Pasos 8 a 11: el terminal firma la peticion, AeriaOne emite el certificado y
     * declara la relacion agente-telefono, y se instala. A partir de aqui falta el PIN.
     *
     * Con QR: el codigo se borra en cuanto llega el 201 o el 200, y la credencial se
     * comprueba contra la CA de agentes del QR antes de instalarla (§4.4).
     */
    private suspend fun entregarPeticionDelAgente(csr: String, userId: String, invitacion: InvitacionDeAlta?) {
        enCurso(AGENTE_ENTREGA)
        val deviceId = enrollment.deviceId()
        val alta = withContext(Dispatchers.IO) {
            val firma = credential.firmarSolicitud(csr)
            val alta = iam.enrolarAgente(csr, userId, deviceId, firma, invitacion?.codigo)
            if (invitacion != null) {
                invitaciones.olvidarCodigo()
                invitacion.comprobarCaDeAgentes(alta.caDeAgentesPem, alta.cadenaPem)
            }
            credential.instalarCertificado(alta.cadenaPem, userId)
            // Con QR, el ancla solo si es la que fija el QR: con ella se acepta a las W1.
            alta.anclaDePerifericosPem
                ?.takeIf { invitacion == null || invitacion.esLaCaDeTerminales(it) }
                ?.let(enrollment::instalarAnclaDePerifericos)
            alta
        }
        hecho(AGENTE_ENTREGA, "Issued by AeriaOne · bound to $deviceId")
        identity.credencialCompletada(userId, alta.perfil)
    }

    private fun describirNivelDeClave(indice: Int, nivel: NivelClave, sufijo: String) {
        when (nivel) {
            NivelClave.STRONGBOX -> hecho(indice, "StrongBox — dedicated chip$sufijo")
            NivelClave.TEE -> hecho(indice, "Trusted execution environment$sufijo")
            // Una clave en software no identifica a nadie: cualquiera que saque una
            // copia del telefono se la lleva. Se informa y sigue, porque aceptarla o
            // no es politica del backend, no nuestra.
            NivelClave.SOFTWARE -> aviso(indice, "Software only — reported to AeriaOne$sufijo")
            NivelClave.DESCONOCIDO -> aviso(indice, "Protection level unknown$sufijo")
        }
    }

    private suspend fun enCurso(indice: Int) {
        actualizarPaso(indice) { it.copy(estado = PasoEstado.EN_CURSO, detalle = null) }
        // Los pasos rapidos pasarian en un fotograma. Esta pausa no disimula nada:
        // hace legible una secuencia que el agente tiene que poder seguir.
        delay(PAUSA_ENTRE_PASOS_MILLIS)
    }

    private fun hecho(indice: Int, detalle: String) {
        actualizarPaso(indice) { it.copy(estado = PasoEstado.HECHO, detalle = detalle) }
    }

    private fun aviso(indice: Int, detalle: String) {
        actualizarPaso(indice) { it.copy(estado = PasoEstado.AVISO, detalle = detalle) }
    }

    private fun marcarFalloEnPasoActual(mensaje: String) {
        val indice = _uiState.value.pasos.indexOfFirst { it.estado == PasoEstado.EN_CURSO }
        if (indice >= 0) {
            actualizarPaso(indice) { it.copy(estado = PasoEstado.FALLIDO, detalle = mensaje) }
        }
        _uiState.update { it.copy(mensajeError = mensaje, iniciada = false) }
    }

    private fun actualizarPaso(indice: Int, cambio: (PasoAlta) -> PasoAlta) {
        _uiState.update { estado ->
            estado.copy(
                pasos = estado.pasos.mapIndexed { i, paso -> if (i == indice) cambio(paso) else paso },
            )
        }
    }

    companion object {
        /** La misma regla que aplica el panel al dar de alta (iam-session.md §9.1). */
        private val FORMATO_DE_AGENTE = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")

        const val ERROR_DE_FORMATO =
            "Use 3 to 63 letters, digits, dots and hyphens, starting and ending with a letter or digit."

        private const val DATOS = 0
        private const val POSTURA = 1
        private const val CLAVE = 2
        private const val PETICION = 3
        private const val ENTREGA = 4
        private const val SERVIDOR = 5
        private const val IDENTIDAD = 6

        private const val AGENTE_IDENTIDAD = 0
        private const val AGENTE_CLAVE = 1
        private const val AGENTE_PETICION = 2
        private const val AGENTE_ENTREGA = 3

        private const val PAUSA_ENTRE_PASOS_MILLIS = 250L

        /** Las dos peticiones usan la misma curva y el mismo algoritmo de firma. */
        private const val ALGORITMO_VISIBLE = "PKCS#10 · ECDSA P-256 · SHA-256"

        /** Tenant de las altas sin QR (depuracion). Con QR viene en el propio QR. */
        private const val TENANT_SIN_QR = "QPD"

        /** Rechazos que no arregla un reintento: hace falta otro QR (§4.4). */
        private val CODIGOS_DE_QR_GASTADO = setOf(
            "enrollment_code_invalid",
            "enrollment_code_expired",
            "enrollment_code_required",
            "reenrollment_not_authorized",
        )
    }
}

private val PASOS_TERMINAL = listOf(
    PasoAlta("Device information"),
    PasoAlta("Security posture"),
    PasoAlta("Key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
    PasoAlta("Verify AeriaOne server"),
    PasoAlta("Device identity"),
)

private val PASOS_AGENTE = listOf(
    PasoAlta("Officer identity"),
    PasoAlta("Officer key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
)
