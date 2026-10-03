package com.delta.aeria_nexus_prototype.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.RawEvidenceRepository
import com.delta.aeria_nexus_prototype.data.VaultRepository
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.identity.CambioDePin
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.local.RawEvidenceEntity
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import com.delta.aeria_nexus_prototype.data.transcript.TranscripcionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VaultUiState(
    val configurada: Boolean = false,
    val desbloqueada: Boolean = false,
    val evidencias: List<VaultRepository.VaultItem> = emptyList(),
    /** Transcripciones traidas de la bodycam, que no tienen evidencia en el telefono. */
    val transcripcionesBodycam: List<VaultRepository.BodycamTranscript> = emptyList(),
    /** Transcripcion abierta en el dialogo de lectura. */
    val transcripcionAbierta: TranscripcionJson.Leida? = null,
    /** Importado de un periferico y todavia sin incidente. */
    val sinCategorizar: List<RawEvidenceEntity> = emptyList(),
    /** Incidentes del agente, para poder elegir uno al categorizar. */
    val incidentes: List<OfficerIncident> = emptyList(),
    /** Pieza cuyo dialogo de categorizacion esta abierto. */
    val categorizando: RawEvidenceEntity? = null,
    /** Paso de «Forgot password» en curso, o null si no se esta reiniciando la boveda. */
    val reinicio: PasoReinicio? = null,
    /** Digitos del PIN tecleados para autorizar el reinicio. */
    val pinReinicio: String = "",
    // Cubre la derivacion de la contrasena, que tarda unas decimas de segundo.
    val trabajando: Boolean = false,
    val mensajeError: String? = null,
)

/** Reiniciar la boveda: primero el aviso de lo que se pierde, despues el PIN del agente. */
enum class PasoReinicio { AVISO, PIN }

/**
 * Boveda de evidencia: crear la contrasena la primera vez, desbloquear para
 * revisar lo capturado y volver a bloquear al terminar.
 */
class VaultViewModel(
    private val vault: VaultRepository,
    private val enBruto: RawEvidenceRepository,
    private val incidentes: IncidentRepository,
    private val auditoria: AuditoriaLocal,
    private val identidad: IdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        // El estado de la boveda es global (una captura puede cifrarse con la
        // pantalla cerrada), asi que la pantalla lo observa en vez de copiarlo.
        viewModelScope.launch {
            combine(EvidenceVault.configurada, EvidenceVault.desbloqueada) { configurada, desbloqueada ->
                configurada to desbloqueada
            }.collect { (configurada, desbloqueada) ->
                val evidencias = if (desbloqueada) withContext(Dispatchers.IO) { vault.list() } else emptyList()
                val deBodycam = if (desbloqueada) withContext(Dispatchers.IO) { vault.listBodycamTranscripts() } else emptyList()
                _uiState.update {
                    it.copy(
                        configurada = configurada,
                        desbloqueada = desbloqueada,
                        evidencias = evidencias,
                        transcripcionesBodycam = deBodycam,
                        // Al bloquear no puede quedar texto descifrado en pantalla.
                        transcripcionAbierta = it.transcripcionAbierta.takeIf { desbloqueada },
                    )
                }
            }
        }
    }

    init {
        // Room emite sola cuando se categoriza algo, asi que la lista se vacia sin
        // que la pantalla tenga que refrescarse a mano.
        viewModelScope.launch {
            enBruto.sinCategorizar.collect { pendientes ->
                _uiState.update { it.copy(sinCategorizar = pendientes) }
            }
        }
        viewModelScope.launch {
            incidentes.officerIncidents.collect { lista ->
                _uiState.update { it.copy(incidentes = lista) }
            }
        }
    }

    fun abrirTranscripcion(ruta: String) {
        viewModelScope.launch {
            val leida = vault.readTranscript(ruta)
            _uiState.update {
                it.copy(
                    transcripcionAbierta = leida,
                    mensajeError = if (leida == null) "Could not open this transcript" else null,
                )
            }
        }
    }

    fun cerrarTranscripcion() {
        _uiState.update { it.copy(transcripcionAbierta = null) }
    }

    fun pedirCategorizacion(fila: RawEvidenceEntity) {
        _uiState.update { it.copy(categorizando = fila, mensajeError = null) }
    }

    fun cancelarCategorizacion() {
        _uiState.update { it.copy(categorizando = null) }
    }

    /**
     * Categoriza la pieza abierta en el dialogo. Con [incidentId] nulo se crea un
     * incidente nuevo, fechado en la grabacion.
     */
    fun categorizar(clasificacion: EvidenceClass, etiqueta: String, incidentId: String?) {
        val fila = _uiState.value.categorizando ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(trabajando = true, mensajeError = null) }
            val destino = enBruto.categorizar(fila.fileName, clasificacion, etiqueta, incidentId)
            _uiState.update {
                it.copy(
                    trabajando = false,
                    categorizando = null,
                    mensajeError = if (destino == null) "Could not categorize this evidence" else null,
                )
            }
        }
    }

    /** Crea la boveda la primera vez y la deja abierta. */
    fun crearBoveda(contrasena: String, repetida: String) {
        if (contrasena.length < EvidenceVault.LONGITUD_MINIMA) {
            mostrarError("Password must be at least ${EvidenceVault.LONGITUD_MINIMA} characters")
            return
        }
        if (contrasena != repetida) {
            mostrarError("Passwords do not match")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(trabajando = true, mensajeError = null) }
            // Generar el par RSA y derivar la clave bloquean el hilo: fuera del principal.
            val creada = withContext(Dispatchers.Default) { EvidenceVault.configurar(contrasena) }
            if (creada) auditoria.registrar(TipoEvento.BOVEDA_CREADA)
            _uiState.update {
                it.copy(
                    trabajando = false,
                    mensajeError = if (creada) null else "Could not create the vault",
                )
            }
        }
    }

    fun desbloquear(contrasena: String) {
        if (contrasena.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(trabajando = true, mensajeError = null) }
            val abierta = withContext(Dispatchers.Default) { EvidenceVault.desbloquear(contrasena) }
            auditoria.registrar(
                if (abierta) TipoEvento.BOVEDA_ABIERTA else TipoEvento.BOVEDA_CONTRASENA_INCORRECTA,
            )
            _uiState.update {
                it.copy(
                    trabajando = false,
                    mensajeError = if (abierta) null else "Wrong password",
                )
            }
        }
    }

    /** Cierra la boveda y borra las copias descifradas que quedaron en cache. */
    fun bloquear() {
        if (EvidenceVault.desbloqueada.value) {
            auditoria.registrar(TipoEvento.BOVEDA_CERRADA, listOf("reason" to "MANUAL"))
        }
        EvidenceVault.bloquear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.clearDecrypted() }
        }
    }

    fun pedirReinicio() {
        _uiState.update { it.copy(reinicio = PasoReinicio.AVISO, mensajeError = null) }
    }

    fun aceptarAvisoDeReinicio() {
        _uiState.update { it.copy(reinicio = PasoReinicio.PIN) }
    }

    fun cancelarReinicio() {
        _uiState.update { it.copy(reinicio = null, pinReinicio = "", mensajeError = null) }
    }

    fun escribirDigitoDeReinicio(digito: Char) {
        val estado = _uiState.value
        if (estado.trabajando || estado.pinReinicio.length >= IdentityRepository.PIN_LENGTH) return

        val escrito = estado.pinReinicio + digito
        _uiState.update { it.copy(pinReinicio = escrito, mensajeError = null) }
        if (escrito.length == IdentityRepository.PIN_LENGTH) reiniciarConPin(escrito)
    }

    fun borrarDigitoDeReinicio() {
        if (_uiState.value.trabajando) return
        _uiState.update { it.copy(pinReinicio = it.pinReinicio.dropLast(1), mensajeError = null) }
    }

    /**
     * Reinicia la boveda si [pin] es el del agente. El PIN no abre ninguna evidencia:
     * solo impide que cualquiera con el telefono en la mano le borre al agente lo
     * que puede ver. Los fallos cuentan contra el mismo limite que el desbloqueo.
     */
    private fun reiniciarConPin(pin: String) {
        _uiState.update { it.copy(trabajando = true) }
        viewModelScope.launch {
            // Comprobar el PIN deriva la clave con PBKDF2: fuera del hilo principal.
            val comprobacion = withContext(Dispatchers.Default) {
                identidad.comprobarPinActual(pin, via = "vault_reset")
            }
            val error = when (comprobacion) {
                CambioDePin.OK -> if (borrarBoveda()) null else "Could not reset the vault"
                CambioDePin.PIN_ACTUAL_INCORRECTO ->
                    "Wrong PIN. ${identidad.status.value.attemptsLeft} attempts left."
                // Con la tanda agotada la sesion ya se ha cerrado y la app vuelve a la
                // pantalla de bloqueo: este estado apenas llega a verse.
                else -> "Too many attempts."
            }
            _uiState.update {
                it.copy(
                    trabajando = false,
                    pinReinicio = "",
                    mensajeError = error,
                    // Con la boveda borrada la pantalla pasa sola a crear la nueva.
                    reinicio = it.reinicio.takeIf { error != null },
                )
            }
        }
    }

    private suspend fun borrarBoveda(): Boolean = withContext(Dispatchers.IO) {
        // Se cuenta antes de borrar: despues ya no hay boveda con la que comparar.
        val ilegibles = vault.list().count { it.openable }
        val borrada = EvidenceVault.reiniciar()
        if (borrada) {
            vault.clearDecrypted()
            auditoria.registrar(
                TipoEvento.BOVEDA_REINICIADA,
                listOf("unreadable_on_device" to ilegibles.toString()),
            )
        }
        borrada
    }

    private fun mostrarError(mensaje: String) {
        _uiState.update { it.copy(mensajeError = mensaje) }
    }
}
