package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.NivelClave
import com.delta.aeria_nexus_prototype.data.identity.PropositoDelReto
import com.delta.aeria_nexus_prototype.data.identity.RetoRepository
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
    val deviceId: String? = null,
    val userId: String? = null,
    /** La fase llego hasta donde puede llegar sin backend. */
    val esperandoCertificado: Boolean = false,
    val mensajeError: String? = null,
)

/**
 * Asistente de alta (workflows 12 y 3).
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
    private val retos: RetoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EnrollmentUiState())
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    init {
        // El alta se reanuda por la etapa que falte en vez de rehacer una clave que
        // ya existe: generar otra invalidaria la peticion que la CA esta firmando.
        when {
            credential.solicitudGuardada() != null -> mostrarEsperaDeCredencial()
            enrollment.altaCompleta() -> iniciarFaseDelAgente()
            enrollment.csrGuardado() != null -> mostrarEsperaDelTerminal()
        }
    }

    fun iniciar() {
        if (_uiState.value.iniciada) return
        _uiState.update { it.copy(iniciada = true, mensajeError = null) }
        identity.altaEnCurso()

        viewModelScope.launch {
            try {
                recogerDatos()
                mirarPostura()
                asignarIdentidad()
                generarClaveDelTerminal()
                val csr = crearPeticionDelTerminal()
                entregarPeticionDelTerminal(csr)
            } catch (e: Exception) {
                marcarFalloEnPasoActual(e.message ?: "Unexpected error")
            }
        }
    }

    /** Workflow 12, paso 3. */
    private suspend fun recogerDatos() {
        enCurso(DATOS)
        val atributos = withContext(Dispatchers.IO) { enrollment.atributos() }
        hecho(
            DATOS,
            "${atributos.manufacturer} ${atributos.model} · Android ${atributos.androidRelease} · " +
                "patch ${atributos.securityPatch}",
        )
    }

    /** Pasos 5 y 6: se informa, no se decide. La elegibilidad es del backend (paso 4). */
    private suspend fun mirarPostura() {
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
    }

    /** Pasos 8 y 9, hoy locales. */
    private suspend fun asignarIdentidad() {
        enCurso(IDENTIDAD)
        val deviceId = enrollment.deviceId()
        _uiState.update { it.copy(deviceId = deviceId) }
        hecho(IDENTIDAD, deviceId)
    }

    /**
     * Paso 10.
     *
     * El reto de atestacion sale del backend si lo hay. Cuando no lo hay se dice en
     * la propia pantalla: una cadena de atestacion sin reto ajeno esta bien formada
     * y no prueba nada de frescura, y esa diferencia no se puede quedar en un log.
     */
    private suspend fun generarClaveDelTerminal() {
        enCurso(CLAVE)
        val reto = retos.consumir(PropositoDelReto.ATESTACION_TERMINAL)
        val resultado = withContext(Dispatchers.Default) { enrollment.generarClave(reto?.bytes) }
        val atestacion = enrollment.certificadosDeAtestacion()
        val sufijo = when {
            atestacion <= 1 -> " · no attestation"
            resultado.conRetoDelBackend ->
                " · attestation chain of $atestacion, challenged by ${reto?.emisor}"
            else -> " · attestation chain of $atestacion, SELF-CHALLENGED (proves no freshness)"
        }
        describirNivelDeClave(CLAVE, resultado.nivel, sufijo)
    }

    /** Paso 11. */
    private suspend fun crearPeticionDelTerminal(): String {
        enCurso(PETICION)
        val deviceId = requireNotNull(_uiState.value.deviceId)
        val csr = withContext(Dispatchers.Default) { enrollment.crearCsr(deviceId, TENANT) }
        hecho(PETICION, ALGORITMO_VISIBLE)
        return csr
    }

    /** Paso 12. */
    private suspend fun entregarPeticionDelTerminal(csr: String) {
        enCurso(ENTREGA)
        withContext(Dispatchers.IO) { enrollment.guardarCsr(csr) }
        aviso(ENTREGA, "Held on device — no AeriaOne backend yet")
        mostrarEsperaDelTerminal()
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
            it.copy(
                fase = FaseAlta.AGENTE,
                iniciada = true,
                esperandoCertificado = false,
                mensajeError = null,
                pasos = PASOS_AGENTE,
                deviceId = enrollment.deviceId(),
                userId = userId,
            )
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

    /** Pasos 1 y 2: hoy no hay IAM que valide nada, y se dice. */
    private suspend fun presentarAlAgente(userId: String) {
        enCurso(AGENTE_IDENTIDAD)
        aviso(AGENTE_IDENTIDAD, "$userId · provided locally, no AeriaOne IAM yet")
    }

    /** Paso 6: el segundo par, que nunca es el del telefono. */
    private suspend fun generarClaveDelAgente() {
        enCurso(AGENTE_CLAVE)
        val nivel = withContext(Dispatchers.Default) { credential.generarClave() }
        describirNivelDeClave(AGENTE_CLAVE, nivel, sufijo = " · separate from the device key")
    }

    /** Paso 7. */
    private suspend fun crearPeticionDelAgente(userId: String): ByteArray {
        enCurso(AGENTE_PETICION)
        val csr = withContext(Dispatchers.Default) { credential.crearCsr(userId, TENANT) }
        hecho(AGENTE_PETICION, ALGORITMO_VISIBLE)
        return csr
    }

    /** Paso 8, con la firma del terminal dentro. Ver [CredentialRepository]. */
    private suspend fun entregarPeticionDelAgente(csr: ByteArray, userId: String) {
        enCurso(AGENTE_ENTREGA)
        val deviceId = enrollment.deviceId()
        withContext(Dispatchers.IO) {
            credential.entregarSolicitud(
                csrDelAgente = csr,
                userId = userId,
                tenant = TENANT,
                deviceId = deviceId,
            )
        }
        aviso(AGENTE_ENTREGA, "Held on device · countersigned by $deviceId")
        mostrarEsperaDeCredencial()
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

    private fun mostrarEsperaDelTerminal() {
        _uiState.update {
            it.copy(
                fase = FaseAlta.TERMINAL,
                iniciada = true,
                esperandoCertificado = true,
                deviceId = enrollment.deviceId(),
                pasos = if (it.fase == FaseAlta.TERMINAL && it.iniciada) {
                    it.pasos
                } else {
                    PASOS_TERMINAL.map { paso -> paso.copy(estado = PasoEstado.HECHO) }
                },
            )
        }
    }

    private fun mostrarEsperaDeCredencial() {
        _uiState.update {
            it.copy(
                fase = FaseAlta.AGENTE,
                iniciada = true,
                esperandoCertificado = true,
                deviceId = enrollment.deviceId(),
                userId = identity.status.value.identity?.userId,
                pasos = if (it.fase == FaseAlta.AGENTE && it.iniciada) {
                    it.pasos
                } else {
                    PASOS_AGENTE.map { paso -> paso.copy(estado = PasoEstado.HECHO) }
                },
            )
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
        const val IDENTIDAD = 2
        const val CLAVE = 3
        const val PETICION = 4
        const val ENTREGA = 5

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
    PasoAlta("Device identity"),
    PasoAlta("Key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
)

private val PASOS_AGENTE = listOf(
    PasoAlta("Officer identity"),
    PasoAlta("Officer key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
)
