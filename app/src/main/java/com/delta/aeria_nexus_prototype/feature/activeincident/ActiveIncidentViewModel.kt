package com.delta.aeria_nexus_prototype.feature.activeincident

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.LocalEvidenceRepository
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActiveIncidentUiState(
    val incidentSeconds: Int = 0,
    val recordingSeconds: Int = 0,
    val isAudioRecording: Boolean = false,
    val audioSeconds: Int = 0,
    // Evidencia recien capturada a la espera de clasificacion.
    val pendingEvidence: EvidenceRecord? = null,
    val showWitnessQr: Boolean = false,
    val qrSecondsLeft: Int = 0,
)

/**
 * Maneja el incidente en curso: temporizadores, grabacion, fotos, notas de
 * audio, QR de testigos y cierre del incidente.
 */
class ActiveIncidentViewModel(
    private val repositorio: IncidentRepository,
    private val bodycamRepository: BodycamRepository,
    private val localEvidence: LocalEvidenceRepository,
) : ViewModel() {

    /** True cuando no hay bodycam: video y foto se capturan con el telefono. */
    val usesPhoneCapture: Boolean get() = !bodycamRepository.isConnected

    // Destino de la captura en curso con la camara del telefono.
    private var pendingCapture: LocalEvidenceRepository.MediaTarget? = null

    val activeIncident: StateFlow<ActiveIncident?> = repositorio.activeIncident

    private val _uiState = MutableStateFlow(ActiveIncidentUiState())
    val uiState: StateFlow<ActiveIncidentUiState> = _uiState.asStateFlow()

    init {
        // Un solo reloj avanza todos los contadores; se cancela con el ViewModel.
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                advanceTimers()
            }
        }
    }

    private fun advanceTimers() {
        val grabandoVideo = activeIncident.value?.isRecording == true
        _uiState.update { estado ->
            estado.copy(
                incidentSeconds = estado.incidentSeconds + 1,
                recordingSeconds = if (grabandoVideo) estado.recordingSeconds + 1 else estado.recordingSeconds,
                audioSeconds = if (estado.isAudioRecording) estado.audioSeconds + 1 else estado.audioSeconds,
                qrSecondsLeft = if (estado.showWitnessQr) maxOf(0, estado.qrSecondsLeft - 1) else estado.qrSecondsLeft,
            )
        }
    }

    /** Dispositivos activos: FC segun la conexion Bluetooth real de la bodycam,
     *  FL segun el perfil (gafas aun sin integrar) y siempre el telefono (AN). */
    private fun activeDevices(): String {
        val perfil = repositorio.officerProfile
        return buildList {
            if (bodycamRepository.isConnected) add("FC")
            if (perfil.flConnected) add("FL")
            add("AN")
        }.joinToString(" + ")
    }

    /** Grabacion con la bodycam conectada: los comandos BT la inician y detienen. */
    fun toggleRecording() {
        val incidente = activeIncident.value ?: return
        if (incidente.isRecording) {
            bodycamRepository.sendCommand("REC_STOP")
            stopRecording()
        } else {
            bodycamRepository.sendCommand("REC_START")
            repositorio.updateActiveIncident { it.copy(isRecording = true) }
            addTimelineEntry("Recording started — ${activeDevices()} active", TimelineEntryType.RECORDING_START)
        }
    }

    private fun stopRecording() {
        val segundos = _uiState.value.recordingSeconds
        val duracion = "${segundos / 60}m ${segundos % 60}s"
        repositorio.updateActiveIncident { it.copy(isRecording = false) }
        addTimelineEntry("Recording ended — $duracion captured", TimelineEntryType.RECORDING_END)
        addEvidence(
            EvidenceRecord(
                id = UUID.randomUUID().toString(),
                type = EvidenceType.VIDEO,
                label = "Video recording — ${activeDevices()}",
                time = IncidentRepository.nowTime(),
                duration = duracion,
                device = activeDevices(),
                classification = EvidenceClass.EVIDENCE,
                hash = IncidentRepository.fakeHash(),
                sync = SyncState.LOCAL_ONLY,
            ),
        )
        _uiState.update { it.copy(recordingSeconds = 0) }
    }

    /** Foto con la bodycam conectada: el comando BT dispara su camara. */
    fun capturePhoto() {
        bodycamRepository.sendCommand("PHOTO")
        registerPendingPhoto(mediaUri = null)
    }

    // ── Captura con el telefono (sin bodycam) ────────────────────────────────
    // La app de camara del sistema escribe directo en el album localIncidents;
    // aqui solo se prepara el destino y se registra la evidencia al volver.

    /** Prepara el destino de una foto con el telefono y devuelve su Uri. */
    fun preparePhonePhoto(): Uri? {
        val destino = localEvidence.createPhotoTarget()
        pendingCapture = destino
        return destino?.uri
    }

    /** Resultado de la foto con el telefono; con exito abre la clasificacion. */
    fun onPhonePhotoResult(success: Boolean) {
        val destino = pendingCapture
        pendingCapture = null
        if (destino == null) return
        if (!success) {
            localEvidence.discard(destino)
            return
        }
        localEvidence.publish(destino)
        registerPendingPhoto(mediaUri = destino.uri.toString())
    }

    /** Prepara el destino de un video con el telefono y devuelve su Uri. */
    fun preparePhoneVideo(): Uri? {
        val destino = localEvidence.createVideoTarget()
        pendingCapture = destino
        return destino?.uri
    }

    /** Resultado del video con el telefono; con exito lo registra como evidencia. */
    fun onPhoneVideoResult(success: Boolean) {
        val destino = pendingCapture
        pendingCapture = null
        if (destino == null) return
        if (!success) {
            localEvidence.discard(destino)
            return
        }
        localEvidence.publish(destino)
        val duracion = localEvidence.mediaDuration(destino)
        addTimelineEntry(
            "Video recorded — ${duracion ?: "saved"} (phone camera)",
            TimelineEntryType.RECORDING_END,
        )
        addEvidence(
            EvidenceRecord(
                id = UUID.randomUUID().toString(),
                type = EvidenceType.VIDEO,
                label = "Video recording — ${activeDevices()}",
                time = IncidentRepository.nowTime(),
                duration = duracion,
                device = activeDevices(),
                classification = EvidenceClass.EVIDENCE,
                hash = IncidentRepository.fakeHash(),
                sync = SyncState.LOCAL_ONLY,
                mediaUri = destino.uri.toString(),
            ),
        )
    }

    /** Crea la evidencia de foto pendiente y abre la hoja de clasificacion. */
    private fun registerPendingPhoto(mediaUri: String?) {
        val grabando = activeIncident.value?.isRecording == true
        val foto = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            type = EvidenceType.PHOTO,
            label = "Photo captured",
            time = IncidentRepository.nowTime(),
            hash = IncidentRepository.fakeHash(),
            sync = SyncState.LOCAL_ONLY,
            linkedTimestamp = if (grabando) formatSeconds(_uiState.value.recordingSeconds) else null,
            mediaUri = mediaUri,
        )
        addTimelineEntry("Photo captured", TimelineEntryType.PHOTO)
        _uiState.update { it.copy(pendingEvidence = foto) }
    }

    /**
     * Nota de audio con el microfono del telefono (grabacion real en
     * localIncidents). Requiere el permiso RECORD_AUDIO ya concedido.
     */
    fun toggleAudioNote() {
        if (_uiState.value.isAudioRecording) {
            val segundos = _uiState.value.audioSeconds
            val duracion = "${segundos / 60}m ${segundos % 60}s"
            val destino = localEvidence.stopAudioRecording()
            _uiState.update { it.copy(isAudioRecording = false, audioSeconds = 0) }
            // Si stop() descarto la grabacion (demasiado corta), no hay evidencia.
            if (destino == null) return
            addEvidence(
                EvidenceRecord(
                    id = UUID.randomUUID().toString(),
                    type = EvidenceType.AUDIO,
                    label = "Officer audio note",
                    time = IncidentRepository.nowTime(),
                    duration = duracion,
                    classification = EvidenceClass.EVIDENCE,
                    hash = IncidentRepository.fakeHash(),
                    sync = SyncState.LOCAL_ONLY,
                    mediaUri = destino.uri.toString(),
                ),
            )
            addTimelineEntry("Audio note added — $duracion", TimelineEntryType.AUDIO)
        } else {
            if (!localEvidence.startAudioRecording()) return
            _uiState.update { it.copy(isAudioRecording = true) }
            addTimelineEntry("Audio note recording started", TimelineEntryType.AUDIO)
        }
    }

    fun generateWitnessQr() {
        _uiState.update { it.copy(showWitnessQr = true, qrSecondsLeft = QR_VALID_SECONDS) }
        addTimelineEntry("Witness QR generated", TimelineEntryType.WITNESS_QR)
        repositorio.updateActiveIncident { it.copy(witnessCount = it.witnessCount + 1) }
    }

    fun dismissWitnessQr() {
        _uiState.update { it.copy(showWitnessQr = false) }
    }

    /** Guarda la foto pendiente con la clasificacion elegida. */
    fun classifyPendingEvidence(clase: EvidenceClass) {
        val pendiente = _uiState.value.pendingEvidence ?: return
        addEvidence(pendiente.copy(classification = clase, label = "Photo — ${clase.label}"))
        _uiState.update { it.copy(pendingEvidence = null) }
    }

    /** Guarda la foto pendiente sin clasificar. */
    fun skipClassification() {
        _uiState.value.pendingEvidence?.let { addEvidence(it) }
        _uiState.update { it.copy(pendingEvidence = null) }
    }

    fun endIncident() {
        addTimelineEntry("Incident ended — moved to draft", TimelineEntryType.ENDED)
        repositorio.endActiveIncident()
    }

    override fun onCleared() {
        // Libera el microfono si la pantalla se destruye grabando una nota.
        localEvidence.stopAudioRecording()
    }

    private fun addEvidence(evidencia: EvidenceRecord) {
        repositorio.updateActiveIncident { incidente ->
            val evidencias = incidente.evidence + evidencia
            incidente.copy(evidence = evidencias, evidenceCount = evidencias.size)
        }
    }

    private fun addTimelineEntry(evento: String, tipo: TimelineEntryType) {
        repositorio.updateActiveIncident { incidente ->
            incidente.copy(
                timeline = incidente.timeline + TimelineEntry(
                    id = UUID.randomUUID().toString(),
                    time = IncidentRepository.nowTime(),
                    event = evento,
                    type = tipo,
                ),
            )
        }
    }

    companion object {
        // El QR de testigo expira a los 24 minutos, igual que el prototipo web.
        private const val QR_VALID_SECONDS = 24 * 60

        /** Formatea segundos como MM:SS, o HH:MM:SS a partir de una hora. */
        fun formatSeconds(total: Int): String {
            val horas = total / 3600
            val minutos = (total % 3600) / 60
            val segundos = total % 60
            return if (horas > 0) {
                "%02d:%02d:%02d".format(horas, minutos, segundos)
            } else {
                "%02d:%02d".format(minutos, segundos)
            }
        }
    }
}
