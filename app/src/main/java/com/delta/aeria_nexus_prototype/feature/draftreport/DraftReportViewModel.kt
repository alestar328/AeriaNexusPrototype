package com.delta.aeria_nexus_prototype.feature.draftreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.model.ReportIncident
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DraftReportUiState(
    val incident: ReportIncident? = null,
    val selectedSegmentId: String? = null,
    val isEditing: Boolean = false,
    // Textos modificados por el agente, por id de parrafo.
    val editedTexts: Map<String, String> = emptyMap(),
    val isRegenerating: Boolean = false,
    val isApproving: Boolean = false,
    val approved: Boolean = false,
)

/** Borrador de reporte generado por IA: revision, edicion y aprobacion. */
class DraftReportViewModel(
    repositorio: IncidentRepository,
    incidentId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DraftReportUiState(incident = repositorio.findReportIncident(incidentId)),
    )
    val uiState: StateFlow<DraftReportUiState> = _uiState.asStateFlow()

    /** Selecciona o deselecciona un parrafo para ver su evidencia enlazada. */
    fun toggleSegment(segmentId: String) {
        _uiState.update {
            it.copy(selectedSegmentId = if (it.selectedSegmentId == segmentId) null else segmentId)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedSegmentId = null) }
    }

    fun toggleEditing() {
        _uiState.update { it.copy(isEditing = !it.isEditing) }
    }

    fun onSegmentTextChange(segmentId: String, text: String) {
        _uiState.update { it.copy(editedTexts = it.editedTexts + (segmentId to text)) }
    }

    /** Simula la regeneracion del reporte por la IA. */
    fun regenerate() {
        if (_uiState.value.isRegenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRegenerating = true) }
            delay(1_800)
            _uiState.update { it.copy(isRegenerating = false) }
        }
    }

    /** Aprueba el borrador; la pantalla navega al formulario RMS al terminar. */
    fun approve() {
        if (_uiState.value.isApproving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isApproving = true) }
            delay(1_000)
            _uiState.update { it.copy(approved = true) }
        }
    }
}
