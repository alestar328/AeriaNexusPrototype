package com.delta.aeria_nexus_prototype.feature.rmsform

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

/** Pasos del formulario RMS, en orden. */
enum class RmsStep(val number: Int, val label: String) {
    INFORMATION(1, "Info"),
    SUSPECTS(2, "Suspects"),
    SEIZED(3, "Seized"),
    MEDIA(4, "Media"),
}

data class RmsFormUiState(
    val incident: ReportIncident? = null,
    val step: RmsStep = RmsStep.INFORMATION,
    val isEditingNarrative: Boolean = false,
    val narrativeText: String = "",
    val isRegenerating: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
)

/** Formulario RMS de 4 pasos con narrativa autocompletada por la IA. */
class RmsFormViewModel(
    repositorio: IncidentRepository,
    incidentId: String,
) : ViewModel() {

    private val _uiState: MutableStateFlow<RmsFormUiState>
    val uiState: StateFlow<RmsFormUiState>

    init {
        val incidente = repositorio.findReportIncident(incidentId)
        _uiState = MutableStateFlow(
            RmsFormUiState(
                incident = incidente,
                // La narrativa completa se arma uniendo los parrafos de la IA.
                narrativeText = incidente?.narrative?.joinToString(" ") { it.text }.orEmpty(),
            ),
        )
        uiState = _uiState.asStateFlow()
    }

    fun goToStep(step: RmsStep) {
        _uiState.update { it.copy(step = step) }
    }

    fun toggleEditingNarrative() {
        _uiState.update { it.copy(isEditingNarrative = !it.isEditingNarrative) }
    }

    fun onNarrativeChange(text: String) {
        _uiState.update { it.copy(narrativeText = text) }
    }

    /** Simula la regeneracion de la narrativa por la IA. */
    fun regenerate() {
        if (_uiState.value.isRegenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRegenerating = true) }
            delay(1_600)
            _uiState.update { it.copy(isRegenerating = false) }
        }
    }

    /** Envia el reporte al RMS; la pantalla navega a la confirmacion al terminar. */
    fun submit() {
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            delay(1_500)
            _uiState.update { it.copy(submitted = true) }
        }
    }
}
