package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.NivelClave
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

data class PasoAlta(
    val titulo: String,
    val estado: PasoEstado = PasoEstado.PENDIENTE,
    val detalle: String? = null,
)

data class EnrollmentUiState(
    val iniciada: Boolean = false,
    val pasos: List<PasoAlta> = PASOS_INICIALES,
    val deviceId: String? = null,
    /** El alta llego hasta donde puede llegar sin backend. */
    val esperandoCertificado: Boolean = false,
    val mensajeError: String? = null,
)

/**
 * Asistente de alta del terminal (workflow 12).
 *
 * Los 21 pasos del catalogo se agrupan en seis que el agente pueda seguir. No es
 * cosmetica: el alta puede tardar y puede fallar, y si falla hay que poder decir
 * en cual de las seis cosas fallo sin leer un log.
 */
class EnrollmentViewModel(
    private val enrollment: EnrollmentRepository,
    private val identity: IdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EnrollmentUiState())
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    init {
        // Si ya se genero la peticion en un arranque anterior, se retoma donde
        // estaba en vez de volver a crear una clave nueva.
        if (enrollment.csrGuardado() != null) {
            _uiState.update {
                it.copy(
                    iniciada = true,
                    esperandoCertificado = true,
                    deviceId = enrollment.deviceId(),
                    pasos = PASOS_INICIALES.map { paso ->
                        paso.copy(estado = PasoEstado.HECHO)
                    },
                )
            }
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
                generarClave()
                val csr = crearPeticion()
                entregar(csr)
            } catch (e: Exception) {
                marcarFalloEnPasoActual(e.message ?: "Unexpected error")
            }
        }
    }

    /** Paso 3 del catalogo. */
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

    /** Paso 10. */
    private suspend fun generarClave() {
        enCurso(CLAVE)
        val nivel = withContext(Dispatchers.Default) { enrollment.generarClave() }
        val atestacion = enrollment.certificadosDeAtestacion()
        val sufijo = if (atestacion > 1) " · attestation chain of $atestacion" else " · no attestation"
        when (nivel) {
            NivelClave.STRONGBOX -> hecho(CLAVE, "StrongBox — dedicated chip$sufijo")
            NivelClave.TEE -> hecho(CLAVE, "Trusted execution environment$sufijo")
            // Una clave en software no identifica a un terminal: cualquiera que
            // saque una copia del telefono se la lleva. Se informa y sigue, porque
            // aceptarla o no es politica del backend, no nuestra.
            NivelClave.SOFTWARE -> aviso(CLAVE, "Software only — reported to AeriaOne$sufijo")
            NivelClave.DESCONOCIDO -> aviso(CLAVE, "Protection level unknown$sufijo")
        }
    }

    /** Paso 11. */
    private suspend fun crearPeticion(): String {
        enCurso(PETICION)
        val deviceId = requireNotNull(_uiState.value.deviceId)
        val csr = withContext(Dispatchers.Default) { enrollment.crearCsr(deviceId, TENANT) }
        hecho(PETICION, "PKCS#10 · ECDSA P-256 · SHA-256")
        return csr
    }

    /** Paso 12. */
    private suspend fun entregar(csr: String) {
        enCurso(ENTREGA)
        withContext(Dispatchers.IO) { enrollment.guardarCsr(csr) }
        aviso(ENTREGA, "Held on device — no AeriaOne backend yet")
        _uiState.update { it.copy(esperandoCertificado = true) }
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

        const val PAUSA_ENTRE_PASOS_MILLIS = 250L

        /** Tenant del despliegue. Vendra del backend cuando exista. */
        const val TENANT = "QPD"
    }
}

private val PASOS_INICIALES = listOf(
    PasoAlta("Device information"),
    PasoAlta("Security posture"),
    PasoAlta("Device identity"),
    PasoAlta("Key pair in secure hardware"),
    PasoAlta("Certificate request"),
    PasoAlta("Submit to AeriaOne"),
)
