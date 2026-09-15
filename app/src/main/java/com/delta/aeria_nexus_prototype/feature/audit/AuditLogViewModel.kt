package com.delta.aeria_nexus_prototype.feature.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.IntegridadDelDiario
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Una fila del diario, ya lista para pintar. */
data class FilaDeAuditoria(
    val orden: Long,
    val hora: String,
    val titulo: String,
    val familia: String,
    /** Los datos propios del evento, sin el contexto que se repite en todos. */
    val detalle: String,
    val sesion: String?,
)

data class AuditLogUiState(
    val cargando: Boolean = true,
    val filas: List<FilaDeAuditoria> = emptyList(),
    val integridad: IntegridadDelDiario? = null,
)

/**
 * Diario de auditoria en pantalla (workflow 61). Solo lectura.
 *
 * Existe porque un dato de seguridad que solo esta en un fichero no protege a nadie:
 * el agente, su supervisor o quien audite tienen que poder ver que se registro y si la
 * cadena sigue intacta, sin sacar el fichero con adb.
 */
class AuditLogViewModel(private val auditoria: AuditoriaLocal) : ViewModel() {

    private val _uiState = MutableStateFlow(AuditLogUiState())
    val uiState: StateFlow<AuditLogUiState> = _uiState.asStateFlow()

    init {
        recargar()
    }

    fun recargar() {
        _uiState.update { it.copy(cargando = true) }
        viewModelScope.launch {
            // Verificar recalcula una HMAC por evento en el Keystore: fuera del hilo principal.
            val (eventos, integridad) = withContext(Dispatchers.IO) { auditoria.leer() }
            val filas = eventos.asReversed().map { evento -> fila(evento.orden, evento.json) }
            _uiState.update { AuditLogUiState(cargando = false, filas = filas, integridad = integridad) }
        }
    }

    private fun fila(orden: Long, json: String): FilaDeAuditoria {
        val objeto = runCatching { JSONObject(json) }.getOrNull()
            ?: return FilaDeAuditoria(orden, "", "Unreadable event", "", "", null)
        val tipo = objeto.optString("type")
        val detalle = objeto.keys().asSequence()
            .filterNot { it in CAMPOS_DE_CONTEXTO }
            .mapNotNull { clave -> objeto.optString(clave).takeIf { it.isNotEmpty() && it != "null" }?.let { "$clave: $it" } }
            .joinToString("  ·  ")
        return FilaDeAuditoria(
            orden = orden,
            hora = runCatching { HORA.format(Instant.parse(objeto.optString("ts"))) }.getOrDefault(""),
            titulo = TipoEvento.entries.firstOrNull { it.name == tipo }?.texto ?: tipo,
            familia = objeto.optString("family").uppercase(),
            detalle = detalle,
            sesion = objeto.optString("session").takeIf { it.isNotEmpty() && it != "null" }?.take(8),
        )
    }

    private companion object {
        val CAMPOS_DE_CONTEXTO = setOf(
            "id", "ts", "uptime_ms", "type", "family", "actor", "tenant", "device", "app_instance", "release", "session",
        )
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
