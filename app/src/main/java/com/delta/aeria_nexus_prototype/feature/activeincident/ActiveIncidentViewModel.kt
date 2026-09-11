package com.delta.aeria_nexus_prototype.feature.activeincident

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.AgoraRepository
import com.delta.aeria_nexus_prototype.data.BodycamRepository
import com.delta.aeria_nexus_prototype.data.IncidentRepository
import com.delta.aeria_nexus_prototype.data.LocalEvidenceRepository
import com.delta.aeria_nexus_prototype.data.ProxyRepository
import com.delta.aeria_nexus_prototype.data.model.ActiveIncident
import com.delta.aeria_nexus_prototype.data.model.EvidenceClass
import com.delta.aeria_nexus_prototype.data.model.EvidenceRecord
import com.delta.aeria_nexus_prototype.data.model.EvidenceType
import com.delta.aeria_nexus_prototype.data.model.SyncState
import com.delta.aeria_nexus_prototype.data.model.TimelineEntry
import com.delta.aeria_nexus_prototype.data.model.TimelineEntryType
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.upload.EvidenceUploader
import java.io.File
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
    // El agente pulso END con una nota de audio grabando: el incidente se cierra
    // en cuanto clasifique esa nota, no antes.
    val endAfterClassify: Boolean = false,
    // Cierre terminado. La pantalla no navega hasta verlo en true: cerrar puede
    // implicar cifrar una nota de audio en curso, y salir antes cancelaria ese
    // trabajo y dejaria la nota sin enlazar al incidente.
    val incidentEnded: Boolean = false,
    // Camara del telefono abierta: el fichero donde graba CameraX. Null = cerrada.
    val phoneVideoFile: File? = null,
    // Al abrirse, grabar sin esperar al boton: es la vuelta del SOS que corto una grabacion.
    val phoneCameraAutoStart: Boolean = false,
    // El ViewModel pide parar la grabacion a la pantalla, que es donde vive CameraX.
    val stopPhoneRecordingRequested: Boolean = false,
    // Abrir el livestream propio del SOS lanzado desde la camara.
    val openSosLivestream: Boolean = false,
)

/**
 * Maneja el incidente en curso: temporizadores, grabacion, fotos, notas de
 * audio, QR de testigos y cierre del incidente.
 */
class ActiveIncidentViewModel(
    private val repositorio: IncidentRepository,
    private val bodycamRepository: BodycamRepository,
    private val localEvidence: LocalEvidenceRepository,
    private val uploader: EvidenceUploader,
    private val proxies: ProxyRepository,
    private val agora: AgoraRepository,
) : ViewModel() {

    /**
     * Manda a Nexus la captura recien cifrada. Se encola y vuelve: la transferencia
     * corre en el scope de la aplicacion, no en el del ViewModel, porque salir de la
     * pantalla no debe cortar una subida de 40 MB. Sube el .fev, nunca el original.
     */
    private fun entregar(sellada: EvidenceCrypto.Sealed?, evidenceId: String, label: String) {
        val s = sellada ?: return   // sin cifrar no hay nada que entregar
        uploader.enqueue(s, evidenceId, activeIncident.value?.id, label)
    }

    /** True cuando no hay bodycam: video y foto se capturan con el telefono. */
    val usesPhoneCapture: Boolean get() = !bodycamRepository.isConnected

    // Destino de la captura en curso con la camara del telefono.
    private var pendingCapture: LocalEvidenceRepository.MediaTarget? = null

    // SOS pedido desde la camara del telefono. Sale cuando la pantalla suelta la
    // camara: el livestream la necesita y mientras tanto la tiene CameraX.
    private var sosAlSoltarCamara = false

    // El SOS corto una grabacion: al terminar el SOS se vuelve a grabar sola.
    private var reanudarTrasSos = false

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
        // Vuelta del SOS que corto una grabacion: la camara se abre y graba sola. En
        // plena emergencia nadie se acuerda de volver a pulsar RECORD.
        viewModelScope.launch {
            agora.sosActive.collect { activo ->
                if (activo || !reanudarTrasSos) return@collect
                reanudarTrasSos = false
                openPhoneCamera(autoStart = true)
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
        val video = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            type = EvidenceType.VIDEO,
            label = "Video recording — ${activeDevices()}",
            time = IncidentRepository.nowTime(),
            duration = duracion,
            device = activeDevices(),
            hash = IncidentRepository.fakeHash(),
            sync = SyncState.LOCAL_ONLY,
        )
        _uiState.update { it.copy(recordingSeconds = 0, pendingEvidence = video) }
    }

    /** Foto con la bodycam conectada: el comando BT dispara su camara. */
    fun capturePhoto() {
        bodycamRepository.sendCommand("PHOTO")
        registerPendingPhoto(mediaUri = null)
    }

    // ── Captura con el telefono (sin bodycam) ────────────────────────────────
    // La foto la hace la app de camara del sistema y el video CameraX, dentro de la
    // app; las dos escriben en la carpeta privada. Aqui se prepara el destino y, al
    // cerrar la captura, se cifra y se registra la evidencia. Lo que se guarda en
    // mediaUri es el nombre del .fev dentro de la boveda.

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
        // El cifrado y el hash real se hacen al cerrar la captura, fuera del hilo
        // principal (seal suspende).
        viewModelScope.launch {
            val sellada = localEvidence.seal(destino)
            registerPendingPhoto(
                mediaUri = sellada?.file?.name,
                hash = IncidentRepository.evidenceHash(sellada?.plainSha256),
                sellada = sellada,
            )
        }
    }

    /**
     * Abre la camara del telefono (CameraX, a 1080p) con su destino ya preparado. La
     * app de camara del sistema no servia para video: su intent no deja pedir
     * resolucion ni fps.
     */
    fun openPhoneCamera(autoStart: Boolean = false) {
        val destino = localEvidence.createVideoTarget() ?: return
        pendingCapture = destino
        _uiState.update { it.copy(phoneVideoFile = destino.file, phoneCameraAutoStart = autoStart) }
    }

    /** Cerrar la camara sin haber grabado: el destino se queda vacio y se descarta. */
    fun closePhoneCamera() {
        pendingCapture?.let(localEvidence::discard)
        pendingCapture = null
        _uiState.update { it.copy(phoneVideoFile = null, phoneCameraAutoStart = false) }
    }

    fun onPhoneRecordingStarted() {
        repositorio.updateActiveIncident { it.copy(isRecording = true) }
        _uiState.update { it.copy(phoneCameraAutoStart = false) }
        addTimelineEntry("Recording started — phone camera", TimelineEntryType.RECORDING_START)
    }

    /**
     * CameraX cerro el fichero: por STOP, por un SOS o porque la pantalla se fue. En
     * los tres casos lo grabado es evidencia y sigue el mismo camino.
     */
    fun onPhoneRecordingFinalized(hayVideo: Boolean) {
        repositorio.updateActiveIncident { it.copy(isRecording = false) }
        _uiState.update {
            it.copy(phoneVideoFile = null, stopPhoneRecordingRequested = false, recordingSeconds = 0)
        }
        val destino = pendingCapture
        pendingCapture = null
        if (destino == null) return
        if (!hayVideo) {
            localEvidence.discard(destino)
            return
        }
        viewModelScope.launch { registrarVideoDelTelefono(destino) }
    }

    /**
     * SOS desde la camara del telefono. El livestream necesita la camara y la tiene
     * CameraX: primero se cierra lo que se estuviera grabando, que queda como evidencia
     * normal, y el SOS sale en cuanto la pantalla suelta la camara. Lo que dure el SOS
     * lo graba el backend en la nube (docs/BACKEND-PROXY-AND-SOS.md §2).
     */
    fun sosFromPhoneCamera() {
        sosAlSoltarCamara = true
        if (activeIncident.value?.isRecording == true) {
            reanudarTrasSos = true
            _uiState.update { it.copy(stopPhoneRecordingRequested = true) }
        } else {
            closePhoneCamera()
        }
    }

    /** La pantalla ya solto la camara: si habia un SOS esperando, sale ahora. */
    fun onPhoneCameraReleased() {
        if (!sosAlSoltarCamara) return
        sosAlSoltarCamara = false
        addTimelineEntry("SOS raised — phone camera handed to the livestream", TimelineEntryType.SYSTEM)
        agora.activateSos(repositorio.officerProfile.officerNum)
        _uiState.update { it.copy(openSosLivestream = true) }
    }

    fun onSosLivestreamOpened() {
        _uiState.update { it.copy(openSosLivestream = false) }
    }

    /**
     * Cifra el video, abre la hoja de clasificacion y encarga su proxy. Al cifrar se
     * conserva el claro porque de el sale el proxy; ProxyRepository lo borra al
     * terminar y entrega los dos, primero el proxy y luego el original. La duracion
     * se lee antes, mientras el fichero sigue siendo un MP4 legible.
     */
    private suspend fun registrarVideoDelTelefono(destino: LocalEvidenceRepository.MediaTarget) {
        val duracion = localEvidence.mediaDuration(destino)
        val sellada = localEvidence.seal(destino, conservarClaro = true)
        addTimelineEntry(
            "Video recorded — ${duracion ?: "saved"} (phone camera)",
            TimelineEntryType.RECORDING_END,
        )
        val video = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            type = EvidenceType.VIDEO,
            label = "Video recording — ${activeDevices()}",
            time = IncidentRepository.nowTime(),
            duration = duracion,
            device = activeDevices(),
            hash = IncidentRepository.evidenceHash(sellada?.plainSha256),
            sync = SyncState.LOCAL_ONLY,
            mediaUri = sellada?.file?.name,
        )
        _uiState.update { it.copy(pendingEvidence = video) }
        if (sellada != null) {
            proxies.procesar(destino, sellada, video.id, activeIncident.value?.id, video.label)
        }
    }

    /** Crea la evidencia de foto pendiente y abre la hoja de clasificacion. */
    private fun registerPendingPhoto(
        mediaUri: String?,
        hash: String = IncidentRepository.fakeHash(),
        sellada: EvidenceCrypto.Sealed? = null,
    ) {
        val grabando = activeIncident.value?.isRecording == true
        val foto = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            type = EvidenceType.PHOTO,
            label = "Photo captured",
            time = IncidentRepository.nowTime(),
            hash = hash,
            sync = SyncState.LOCAL_ONLY,
            linkedTimestamp = if (grabando) formatSeconds(_uiState.value.recordingSeconds) else null,
            mediaUri = mediaUri,
        )
        addTimelineEntry("Photo captured", TimelineEntryType.PHOTO)
        _uiState.update { it.copy(pendingEvidence = foto) }
        entregar(sellada, foto.id, foto.label)
    }

    /**
     * Nota de audio con el microfono del telefono. Requiere el permiso
     * RECORD_AUDIO ya concedido.
     */
    fun toggleAudioNote() {
        if (_uiState.value.isAudioRecording) {
            viewModelScope.launch { stopAudioNote() }
        } else {
            if (!localEvidence.startAudioRecording()) return
            _uiState.update { it.copy(isAudioRecording = true) }
            addTimelineEntry("Audio note recording started", TimelineEntryType.AUDIO)
        }
    }

    /**
     * Cierra la nota en curso, la cifra y la deja pendiente de clasificar, igual
     * que una foto o un video. Es suspend porque el cierre del incidente tiene que
     * esperarla: una nota sin enlazar seria una grabacion que existe pero que
     * nadie encuentra.
     */
    private suspend fun stopAudioNote() {
        val segundos = _uiState.value.audioSeconds
        val duracion = "${segundos / 60}m ${segundos % 60}s"
        _uiState.update { it.copy(isAudioRecording = false, audioSeconds = 0) }
        // Si stop() descarto la grabacion (demasiado corta), no hay evidencia.
        val capturada = localEvidence.stopAudioRecordingSealed() ?: return
        val nota = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            type = EvidenceType.AUDIO,
            label = "Officer audio note",
            time = IncidentRepository.nowTime(),
            duration = duracion,
            hash = IncidentRepository.evidenceHash(capturada.sealed?.plainSha256),
            sync = SyncState.LOCAL_ONLY,
            mediaUri = capturada.sealed?.file?.name,
        )
        addTimelineEntry("Audio note added — $duracion", TimelineEntryType.AUDIO)
        // La entrega a Nexus no espera a la clasificacion, igual que en foto y video.
        entregar(capturada.sealed, nota.id, nota.label)
        _uiState.update { it.copy(pendingEvidence = nota) }
    }

    fun generateWitnessQr() {
        _uiState.update { it.copy(showWitnessQr = true, qrSecondsLeft = QR_VALID_SECONDS) }
        addTimelineEntry("Witness QR generated", TimelineEntryType.WITNESS_QR)
        repositorio.updateActiveIncident { it.copy(witnessCount = it.witnessCount + 1) }
    }

    fun dismissWitnessQr() {
        _uiState.update { it.copy(showWitnessQr = false) }
    }

    /** Guarda la evidencia pendiente con la clasificacion elegida. */
    fun classifyPendingEvidence(clase: EvidenceClass) {
        val pendiente = _uiState.value.pendingEvidence ?: return
        val prefijo = when (pendiente.type) {
            EvidenceType.VIDEO -> "Video"
            EvidenceType.AUDIO -> "Audio note"
            else -> "Photo"
        }
        addEvidence(pendiente.copy(classification = clase, label = "$prefijo — ${clase.label}"))
        _uiState.update { it.copy(pendingEvidence = null) }
        endIfWaitingForClassification()
    }

    /** Guarda la evidencia pendiente sin clasificar. */
    fun skipClassification() {
        _uiState.value.pendingEvidence?.let { addEvidence(it) }
        _uiState.update { it.copy(pendingEvidence = null) }
        endIfWaitingForClassification()
    }

    /**
     * Cierra el incidente. Si el agente dejo una nota de audio grabando, primero la
     * cierra y le da la hoja de clasificacion: antes seguia grabando sin dueno y
     * acababa en la boveda sin aparecer en el incidente, y despues se guardaba sin
     * que nadie pudiera clasificarla.
     */
    fun endIncident() {
        viewModelScope.launch {
            if (_uiState.value.isAudioRecording) {
                stopAudioNote()
                // Sin nota pendiente (grabacion descartada por corta) no hay nada
                // que esperar y el incidente se cierra igual.
                if (_uiState.value.pendingEvidence != null) {
                    _uiState.update { it.copy(endAfterClassify = true) }
                    return@launch
                }
            }
            closeIncident()
        }
    }

    /** Cierra el incidente que estaba esperando a que se clasificara la ultima nota. */
    private fun endIfWaitingForClassification() {
        if (!_uiState.value.endAfterClassify) return
        _uiState.update { it.copy(endAfterClassify = false) }
        closeIncident()
    }

    private fun closeIncident() {
        addTimelineEntry("Incident ended — moved to draft", TimelineEntryType.ENDED)
        repositorio.endActiveIncident()
        _uiState.update { it.copy(incidentEnded = true) }
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
