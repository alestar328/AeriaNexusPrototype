package com.delta.aeria_nexus_prototype.data.model

/** Estado del ciclo de vida de un incidente. */
enum class IncidentStatus(val label: String) {
    ACTIVE("Active"),
    DRAFT("Draft"),
    NEEDS_REVIEW("Needs Review"),
    SUBMITTED("Submitted"),
    COMPLETED("Completed"),
}

enum class Priority(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
}

/** Estado de sincronizacion de la evidencia con el servidor central. */
enum class SyncState(val label: String) {
    LOCAL_ONLY("Local Only"),
    PENDING_SYNC("Pending Sync"),
    SYNCING("Syncing"),
    VERIFIED("Synced"),
    FAILED("Failed"),
}

enum class EvidenceType {
    VIDEO, PHOTO, AUDIO, WITNESS_UPLOAD,
}

/** Dispositivo de captura: camara facial (Falcon Lens) o corporal (Falcon Core). */
enum class EvidenceSource(val label: String) {
    FALCON_LENS("Falcon Lens"),
    FALCON_CORE("Falcon Core"),
}

/** Nivel de confianza de la IA en cada parrafo del reporte generado. */
enum class ConfidenceLevel(val label: String) {
    HIGH("High confidence"),
    MEDIUM("Medium confidence"),
    LOW("Low confidence — needs review"),
}

enum class TimelineEntryType {
    CREATED, RECORDING_START, RECORDING_END, PHOTO, AUDIO,
    WITNESS_QR, WITNESS_UPLOAD, ENDED, SYSTEM,
}

/** Clasificacion que el agente asigna a cada pieza de evidencia capturada. */
enum class EvidenceClass(val label: String) {
    EVIDENCE("Evidence"),
    SEIZED_PROPERTY("Seized Property"),
    FOUND_PROPERTY("Found Property"),
    DAMAGED_PROPERTY("Damaged Property"),
    VEHICLE("Vehicle"),
    PERSON("Person"),
    WITNESS_STATEMENT("Witness Statement"),
    SCENE_CONTEXT("Scene Context"),
    OTHER("Other"),
}

/** Estado de una evidencia dentro del reporte RMS. */
enum class ReportEvidenceStatus(val label: String) {
    COLLECTED("Collected"),
    LOGGED("Logged"),
    SUBMITTED("Submitted"),
}
