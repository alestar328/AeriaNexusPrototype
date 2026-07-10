package com.delta.aeria_nexus_prototype.data.model

/**
 * Modelos del flujo de reportes generados por IA: borrador con niveles de
 * confianza, sospechosos, evidencia enlazada y formulario RMS.
 */

data class Officer(
    val id: String,
    val badge: String,
    val name: String,
    val rank: String,
    val unit: String,
    val email: String,
)

/** Referencia al fragmento de video y transcripcion que respalda un parrafo. */
data class EvidenceRef(
    val clipStart: String,
    val clipEnd: String,
    val transcriptSnippet: String,
    val source: EvidenceSource,
)

/** Parrafo del reporte generado por IA. Sin evidencia enlazada requiere revision manual. */
data class NarrativeSegment(
    val id: String,
    val text: String,
    val confidence: ConfidenceLevel,
    val evidenceRef: EvidenceRef? = null,
)

data class Suspect(
    val id: String,
    val name: String,
    val dob: String,
    val gender: String,
    val height: String,
    val weight: String,
    val charges: List<String>,
)

data class ReportEvidence(
    val id: String,
    val type: String,
    val description: String,
    val source: EvidenceSource,
    val clipStart: String,
    val clipEnd: String,
    val status: ReportEvidenceStatus,
)

data class ReportIncident(
    val id: String,
    val caseNumber: String,
    val type: String,
    val typeCode: String,
    val status: IncidentStatus,
    val priority: Priority,
    val location: String,
    val date: String,
    val startTime: String,
    val duration: String,
    val officer: Officer,
    val involvedOfficers: List<Officer>,
    val narrative: List<NarrativeSegment>,
    val suspects: List<Suspect>,
    val evidence: List<ReportEvidence>,
    val supervisorNote: String? = null,
)
