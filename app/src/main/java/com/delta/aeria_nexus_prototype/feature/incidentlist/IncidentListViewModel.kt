package com.delta.aeria_nexus_prototype.feature.incidentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.model.IncidentStatus
import com.delta.aeria_nexus_prototype.data.model.OfficerIncident
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filtros disponibles en la lista de incidentes. */
enum class StatusFilter(val label: String, val status: IncidentStatus?) {
    ALL("All", null),
    DRAFT("Draft", IncidentStatus.DRAFT),
    REVIEW("Review", IncidentStatus.NEEDS_REVIEW),
    DONE("Done", IncidentStatus.COMPLETED),
}

data class IncidentListUiState(
    val query: String = "",
    val filter: StatusFilter = StatusFilter.ALL,
    val incidents: List<OfficerIncident> = emptyList(),
    val needsReviewCount: Int = 0,
)

class IncidentListViewModel(
    private val repositorio: IncidentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncidentListUiState())
    val uiState: StateFlow<IncidentListUiState> = _uiState.asStateFlow()

    init {
        // La lista del repositorio cambia cuando se cierra un incidente en
        // campo; al observarla, los nuevos aparecen sin salir de la pestana.
        viewModelScope.launch {
            repositorio.officerIncidents.collect { lista ->
                _uiState.update { estado ->
                    estado.copy(
                        incidents = filterIncidents(estado.query, estado.filter),
                        needsReviewCount = lista.count { it.status == IncidentStatus.NEEDS_REVIEW },
                    )
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, incidents = filterIncidents(query, it.filter)) }
    }

    fun onFilterChange(filter: StatusFilter) {
        _uiState.update { it.copy(filter = filter, incidents = filterIncidents(it.query, filter)) }
    }

    private fun filterIncidents(query: String, filter: StatusFilter): List<OfficerIncident> {
        val texto = query.trim().lowercase()
        return repositorio.officerIncidents.value.filter { incidente ->
            val coincideFiltro = filter.status == null || incidente.status == filter.status
            val coincideTexto = texto.isEmpty() ||
                incidente.type.lowercase().contains(texto) ||
                incidente.location.lowercase().contains(texto) ||
                incidente.id.lowercase().contains(texto) ||
                incidente.officerName.lowercase().contains(texto)
            coincideFiltro && coincideTexto
        }
    }
}
