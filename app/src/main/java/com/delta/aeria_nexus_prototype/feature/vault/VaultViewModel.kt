package com.delta.aeria_nexus_prototype.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.VaultRepository
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
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
    // Cubre la derivacion de la contrasena, que tarda unas decimas de segundo.
    val trabajando: Boolean = false,
    val mensajeError: String? = null,
)

/**
 * Boveda de evidencia: crear la contrasena la primera vez, desbloquear para
 * revisar lo capturado y volver a bloquear al terminar.
 */
class VaultViewModel(private val vault: VaultRepository) : ViewModel() {

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
                _uiState.update {
                    it.copy(
                        configurada = configurada,
                        desbloqueada = desbloqueada,
                        evidencias = evidencias,
                    )
                }
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
        EvidenceVault.bloquear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.clearDecrypted() }
        }
    }

    private fun mostrarError(mensaje: String) {
        _uiState.update { it.copy(mensajeError = mensaje) }
    }
}
