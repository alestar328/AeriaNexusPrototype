package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.AltaDeTerminal
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.DeviceAttributes
import com.delta.aeria_nexus_prototype.data.identity.DevicePosture
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IamClient
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.NivelClave
import com.delta.aeria_nexus_prototype.data.identity.PropositoDelReto
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
)

/**
 * Asistente de alta (workflows 12 y 3) contra el IAM de AeriaOne.
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(EnrollmentUiState())
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    init {
        // Con el telefono ya acreditado solo falta el agente, y se sigue por ahi en
        // vez de rehacer una clave que AeriaOne ya ha certificado.
        if (enrollment.altaCompleta()) iniciarFaseDelAgente()
    }

    /** Arranque del alta, y reintento de la fase que fallo. */
    fun iniciar() {
        if (_uiState.value.iniciada) return
        if (enrollment.altaCompleta()) {
            iniciarFaseDelAgente()
            return
        }
        _uiState.update {
            it.copy(fase = FaseAlta.TERMINAL, iniciada = true, mensajeError = null, pasos = PASOS_TERMINAL)
        }
        identity.altaEnCurso()

        viewModelScope.launch {
            try {
                val atributos = recogerDatos()
                val postura = mirarPostura()
                generarClaveDelTerminal()
                val csr = crearPeticionDelTerminal()
                val alta = entregarPeticionDelTerminal(csr, atributos, postura)
                instalarIdentidadDelTerminal(alta)
                iniciarFaseDelAgente()
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
    private suspend fun crearPeticionDelTerminal(): String {
        enCurso(PETICION)
        val csr = withContext(Dispatchers.Default) { enrollment.crearCsr(enrollment.deviceId(), TENANT) }
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

    /** Pasos 8 y 15: se adopta el Device ID asignado y se instala el certificado. */
    private suspend fun instalarIdentidadDelTerminal(alta: AltaDeTerminal) {
        enCurso(IDENTIDAD)
        withContext(Dispatchers.IO) {
            enrollment.adoptarDeviceId(alta.deviceId)
            enrollment.instalarCertificado(alta.cadenaPem)
        }
        // El telefono queda acreditado, pero el alta NO ha terminado: falta la
        // credencial del agente, que es el workflow 3.
        identity.altaDeTerminalCompletada(alta.deviceId)
        hecho(IDENTIDAD, "${alta.deviceId} · assigned by AeriaOne")
    }

    /**
     * Segunda mitad: la credencial del agente (workflow 3).
     *
     * Arranca sola en cuanto el terminal tiene certificado, sin boton. El agente
     * no elige aqui nada: en el modelo real es el IAM quien entrega la identidad
     * activa al proceso de credenciales (paso 1), y pedirle una confirmacion seria
     * inventarse una decision que no es suya.
     */
    private fun iniciarFaseDelAgente() {
        val userId = identity.status.value.identity?.userId ?: return
        _uiState.update {
            it.copy(fase = FaseAlta.AGENTE, iniciada = true, mensajeError = null, pasos = PASOS_AGENTE)
        }

        viewModelScope.launch {
            try {
                presentarAlAgente(userId)
                generarClaveDelAgente()
                val csr = crearPeticionDelAgente(userId)
                entregarPeticionDelAgente(csr, userId)
            } catch (e: Exception) {
                marcarFalloEnPasoActual(e.message ?: "Unexpected error")
            }
        }
    }

    /** Pasos 1 y 2: el agente todavia lo pone la app, y AeriaOne comprueba que existe. */
    private suspend fun presentarAlAgente(userId: String) {
        enCurso(AGENTE_IDENTIDAD)
        aviso(AGENTE_IDENTIDAD, "$userId · provided locally, must exist in AeriaOne")
    }

    /** Paso 6: el segundo par, que nunca es el del telefono. */
    private suspend fun generarClaveDelAgente() {
        enCurso(AGENTE_CLAVE)
        val nivel = withContext(Dispatchers.Default) { credential.generarClave() }
        describirNivelDeClave(AGENTE_CLAVE, nivel, sufijo = " · separate from the device key")
    }

    /** Paso 7. */
    private suspend fun crearPeticionDelAgente(userId: String): String {
        enCurso(AGENTE_PETICION)
        val csr = withContext(Dispatchers.Default) { credential.crearCsr(userId, TENANT) }
        hecho(AGENTE_PETICION, ALGORITMO_VISIBLE)
        return csr
    }

    /**
     * Pasos 8 a 11: el terminal firma la peticion, AeriaOne emite el certificado y
     * declara la relacion agente-telefono, y se instala. A partir de aqui falta el PIN.
     */
    private suspend fun entregarPeticionDelAgente(csr: String, userId: String) {
        enCurso(AGENTE_ENTREGA)
        val deviceId = enrollment.deviceId()
        withContext(Dispatchers.IO) {
            val firma = credential.firmarSolicitud(csr)
            val cadena = iam.enrolarAgente(csr, userId, deviceId, firma)
            credential.instalarCertificado(cadena, userId)
        }
        hecho(AGENTE_ENTREGA, "Issued by AeriaOne · bound to $deviceId")
        identity.credencialCompletada(userId)
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

    private companion object {
        const val DATOS = 0
        const val POSTURA = 1
        const val CLAVE = 2
        const val PETICION = 3
        const val ENTREGA = 4
        const val IDENTIDAD = 5

        const val AGENTE_IDENTIDAD = 0
        const val AGENTE_CLAVE = 1
        const val AGENTE_PETICION = 2
        const val AGENTE_ENTREGA = 3

        const val PAUSA_ENTRE_PASOS_MILLIS = 250L

        /** Las dos peticiones usan la misma curva y el mismo algoritmo de firma. */
        const val ALGORITMO_VISIBLE = "PKCS#10 · ECDSA P-256 · SHA-256"

        /** Tenant del despliegue. Vendra del backend cuando exista. */
        const val TENANT = "QPD"
    }
}

private val PASOS_TERMINAL = listOf(
    PasoAlta("Device information"),
    PasoAlta("Security posture"),
    PasoAlta("Key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
    PasoAlta("Device identity"),
)

private val PASOS_AGENTE = listOf(
    PasoAlta("Officer identity"),
    PasoAlta("Officer key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
)
