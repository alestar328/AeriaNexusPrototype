package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.model.RemoteAgent
import com.delta.aeria_nexus_prototype.data.model.SosAlert
import com.delta.aeria_nexus_prototype.data.model.SosCancel
import android.view.TextureView
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.VideoCanvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Red tactica entre agentes sobre Agora RTC (port del CallService de Falcon One).
 *
 * Todos los telefonos se unen al mismo canal y comparten mensajes JSON por el
 * data stream: la posicion propia (cada segundo al moverse, con un heartbeat
 * cada 3 segundos para quien esta quieto) y las senales de SOS con su
 * cancelacion. Agora nunca devuelve al emisor sus propios mensajes, por eso el
 * que dispara un SOS no recibe su propia alerta.
 *
 * Fuera del SOS el telefono es receptor estricto: el microfono queda apagado
 * fisicamente y nada se publica. Solo al emitir un SOS se publican camara y
 * voz al canal (livestream), y los receptores que aceptan la alerta lo ven.
 */
class AgoraRepository(
    private val context: Context,
    private val locationRepository: LocationRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var engine: RtcEngine? = null
    private var dataStreamId = -1
    private var started = false
    private var sharingLocation = false

    // Ultima posicion propia conocida; viaja en cada mensaje de ubicacion y SOS.
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastLocationSentAt = 0L

    // Inicio de la sesion SOS activa. Se reutiliza en cada reenvio del heartbeat
    // para que los receptores detecten que es la misma emergencia y no otra.
    private var sosStartedAtMillis = 0L
    private var sosOfficer = ""
    private var sosHeartbeatJob: Job? = null

    // Claves de sesion SOS ya vistas, para ignorar los reenvios del heartbeat.
    private val seenSosKeys = mutableSetOf<String>()

    // True mientras la bodycam (uid 9001) publica video en el canal. Que la
    // bodycam transmita ES su senal de SOS: asi funciona su boton fisico 133
    // y tambien el comando STREAM_START enviado desde el telefono.
    private var bodycamStreamActive = false

    private val _remoteAgents = MutableStateFlow<Map<Int, RemoteAgent>>(emptyMap())
    val remoteAgents: StateFlow<Map<Int, RemoteAgent>> = _remoteAgents.asStateFlow()

    private val _connectedUsers = MutableStateFlow(0)
    val connectedUsers: StateFlow<Int> = _connectedUsers.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _sosActive = MutableStateFlow(false)
    val sosActive: StateFlow<Boolean> = _sosActive.asStateFlow()

    private val _incomingSos = MutableSharedFlow<SosAlert>(extraBufferCapacity = 8)
    val incomingSos: SharedFlow<SosAlert> = _incomingSos.asSharedFlow()

    private val _incomingSosCancel = MutableSharedFlow<SosCancel>(extraBufferCapacity = 8)
    val incomingSosCancel: SharedFlow<SosCancel> = _incomingSosCancel.asSharedFlow()

    // Uid del agente remoto cuyo video acaba de cortarse (dejo de publicar o se
    // desconecto); null cuando no hay corte. La pantalla de livestream lo
    // observa para mostrar el aviso de "Signal cut" sobre el ultimo cuadro.
    private val _remoteVideoStopped = MutableStateFlow<Int?>(null)
    val remoteVideoStopped: StateFlow<Int?> = _remoteVideoStopped.asStateFlow()

    // Cortes de senal SOS que el mapa muestra como aviso fijo, por uid del
    // emisor. Solo se alimenta con cancelaciones recibidas por el data stream:
    // como Agora nunca devuelve al emisor sus propios mensajes, el agente que
    // corto su SOS no ve el aviso en su propio mapa, solo las demas unidades.
    private val _sosSignalCuts = MutableStateFlow<Map<Int, SosCancel>>(emptyMap())
    val sosSignalCuts: StateFlow<Map<Int, SosCancel>> = _sosSignalCuts.asStateFlow()

    // SOS vigentes de otros agentes, por uid del emisor. Un SOS entra al
    // recibirse y solo sale con su cancelacion: aunque el receptor descarte el
    // popup, el mapa sigue mostrando al agente en emergencia mientras dure.
    private val _activeSosAlerts = MutableStateFlow<Map<Int, SosAlert>>(emptyMap())
    val activeSosAlerts: StateFlow<Map<Int, SosAlert>> = _activeSosAlerts.asStateFlow()

    // Placa del oficial que emitio el SOS de cada uid. Se conserva aunque la
    // emergencia termine: la pantalla de livestream la consulta para mostrar
    // la ficha del agente que se esta viendo.
    private val _sosOfficers = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** Placa del oficial detras del SOS del agente [uid], si llego en la alerta. */
    fun sosOfficer(uid: Int): String? = _sosOfficers.value[uid]

    private val eventHandler = object : IRtcEngineEventHandler() {

        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            _isConnected.value = true
            _connectedUsers.value = 1
            sendCurrentLocation()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            // La bodycam (uid fijo 9001) es un dispositivo, no un agente: no
            // debe inflar el contador de usuarios.
            if (uid != BODYCAM_UID) _connectedUsers.value++
            // Reenviamos posicion y SOS activo para que el recien llegado nos
            // vea de inmediato, sin esperar al siguiente heartbeat.
            sendCurrentLocation()
            if (_sosActive.value) sendSosSignal()
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            if (uid != BODYCAM_UID && _connectedUsers.value > 1) _connectedUsers.value--
            if (uid == BODYCAM_UID) onBodycamStreamChanged(streaming = false)
            // Un emisor que se desconecta equivale a un livestream cortado.
            _remoteVideoStopped.value = uid
            // Solo se quita el marcador si el agente salio del canal a proposito.
            // En una caida transitoria (tunel, sin cobertura) se conserva la
            // ultima posicion conocida y el mapa lo pinta como "sin senal".
            if (reason == Constants.USER_OFFLINE_QUIT) {
                _remoteAgents.update { it - uid }
            }
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            when (state) {
                Constants.REMOTE_VIDEO_STATE_STOPPED,
                Constants.REMOTE_VIDEO_STATE_FAILED,
                -> {
                    _remoteVideoStopped.value = uid
                    if (uid == BODYCAM_UID) onBodycamStreamChanged(streaming = false)
                }

                Constants.REMOTE_VIDEO_STATE_STARTING,
                Constants.REMOTE_VIDEO_STATE_DECODING,
                -> {
                    if (_remoteVideoStopped.value == uid) _remoteVideoStopped.value = null
                    if (uid == BODYCAM_UID) onBodycamStreamChanged(streaming = true)
                }
            }
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray) {
            handleStreamMessage(uid, data)
        }

        override fun onError(err: Int) {
            Log.w(TAG, "Error de Agora: $err")
        }
    }

    /**
     * Crea el motor y se une al canal. Idempotente: se puede llamar desde
     * cualquier pantalla sin riesgo de doble conexion. El canal funciona en
     * modo sin token (igual que la app Flutter, para que la bodycam se una).
     */
    fun ensureStarted() {
        if (started) {
            startLocationSharingIfPermitted()
            return
        }
        if (BuildConfig.AGORA_APP_ID.isEmpty()) {
            Log.w(TAG, "AGORA_APP_ID vacio en local.properties: red tactica deshabilitada")
            return
        }
        started = true

        try {
            // Sin apply: RtcEngineConfig tiene su propio getContext(), y dentro
            // de un apply "context" resolveria a ese getter (null) en lugar del
            // context de la app; el motor devolveria null silenciosamente.
            val config = RtcEngineConfig()
            config.mContext = context
            config.mAppId = BuildConfig.AGORA_APP_ID
            config.mEventHandler = eventHandler
            val rtcEngine = RtcEngine.create(config)
            engine = rtcEngine

            // Receptor estricto por defecto (igual que Falcon One): el audio se
            // habilita solo para reproducir, con el microfono silenciado Y con
            // su captura apagada. Solo publicar un livestream lo enciende.
            rtcEngine.enableAudio()
            rtcEngine.muteLocalAudioStream(true)
            rtcEngine.enableLocalAudio(false)
            rtcEngine.adjustRecordingSignalVolume(0)
            rtcEngine.setDefaultAudioRoutetoSpeakerphone(true)
            // Video habilitado para poder ver livestreams; el propio va mudo.
            rtcEngine.enableVideo()
            rtcEngine.muteLocalVideoStream(true)

            dataStreamId = rtcEngine.createDataStream(
                DataStreamConfig().apply {
                    syncWithAudio = false
                    ordered = true
                },
            )

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                // El audio ajeno se escucha solo al aceptar un livestream; con
                // auto-subscribe cada voz publicada sonaria en todo el canal.
                autoSubscribeAudio = false
                autoSubscribeVideo = true
                publishMicrophoneTrack = false
                publishCameraTrack = false
            }
            // Cada telefono entra con un uid aleatorio, como en Falcon One.
            rtcEngine.joinChannel(null, CHANNEL_ID, Random.nextInt(1, Int.MAX_VALUE), options)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la red tactica", e)
            engine = null
            started = false
            return
        }

        startLocationSharingIfPermitted()
        startLocationHeartbeat()
    }

    /** Llamar cuando el usuario concede el permiso de ubicacion. */
    fun onLocationPermissionGranted() {
        startLocationSharingIfPermitted()
    }

    /**
     * Emite el SOS a los demas dispositivos y publica la camara y la voz de
     * este telefono al canal, para que quien acepte la alerta vea en vivo lo
     * que el agente esta enfocando. [officer] es el numero del oficial emisor.
     */
    fun activateSos(officer: String) {
        ensureStarted()
        if (_sosActive.value) return
        sosOfficer = officer
        sosStartedAtMillis = System.currentTimeMillis()
        _sosActive.value = true
        startCameraPublish()
        sendSosSignal()
        startSosHeartbeat()
    }

    /** Quita del mapa el aviso de corte de senal del agente [uid]. */
    fun dismissSignalCut(uid: Int) {
        _sosSignalCuts.update { it - uid }
    }

    /** Cancela el SOS propio: corta el livestream y avisa a los receptores. */
    fun cancelSos() {
        if (!_sosActive.value) return
        sosHeartbeatJob?.cancel()
        sosHeartbeatJob = null
        _sosActive.value = false
        sendSosCancel()
        stopCameraPublish()
    }

    // Las vistas de video son TextureView y no SurfaceView: dentro de Compose,
    // el SurfaceView se dibuja en una capa aparte detras de la ventana y el
    // fondo de la pantalla lo tapa (se ve negro); el TextureView se compone
    // como una vista normal y no sufre ese problema.

    /** Conecta la vista donde se previsualiza la camara propia durante el SOS. */
    fun attachLocalVideo(view: TextureView) {
        engine?.setupLocalVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, 0))
    }

    /** Conecta la vista donde se reproduce el livestream del agente [uid]. */
    fun attachRemoteVideo(view: TextureView, uid: Int) {
        engine?.setupRemoteVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    /** Suscribe el video y la voz del agente [uid] para verlo en vivo. */
    fun startWatching(uid: Int) {
        val rtcEngine = engine ?: return
        if (_remoteVideoStopped.value == uid) _remoteVideoStopped.value = null
        rtcEngine.muteRemoteVideoStream(uid, false)
        rtcEngine.muteRemoteAudioStream(uid, false)
    }

    /** Deja de escuchar al agente [uid] y libera su vista al salir de la pantalla. */
    fun stopWatching(uid: Int) {
        val rtcEngine = engine ?: return
        rtcEngine.muteRemoteAudioStream(uid, true)
        rtcEngine.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    /**
     * Publica camara trasera y microfono al canal. La trasera es la que enfoca
     * la escena (comportamiento bodycam), no la de selfie.
     */
    private fun startCameraPublish() {
        val rtcEngine = engine ?: return
        // El microfono esta apagado fisicamente fuera del livestream: hay que
        // reactivar su captura y volumen antes de despublicar el silencio.
        rtcEngine.enableLocalAudio(true)
        rtcEngine.adjustRecordingSignalVolume(100)
        rtcEngine.setCameraCapturerConfiguration(
            CameraCapturerConfiguration(CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR),
        )
        rtcEngine.startPreview()
        rtcEngine.muteLocalVideoStream(false)
        rtcEngine.muteLocalAudioStream(false)
        rtcEngine.updateChannelMediaOptions(
            ChannelMediaOptions().apply {
                publishCameraTrack = true
                publishMicrophoneTrack = true
            },
        )
    }

    /** Vuelve al modo receptor estricto: nada de este telefono sale al canal. */
    private fun stopCameraPublish() {
        val rtcEngine = engine ?: return
        rtcEngine.updateChannelMediaOptions(
            ChannelMediaOptions().apply {
                publishCameraTrack = false
                publishMicrophoneTrack = false
            },
        )
        rtcEngine.muteLocalVideoStream(true)
        rtcEngine.muteLocalAudioStream(true)
        rtcEngine.enableLocalAudio(false)
        rtcEngine.adjustRecordingSignalVolume(0)
        rtcEngine.stopPreview()
    }

    /**
     * Traduce el video de la bodycam a un flujo SOS: al empezar a publicar se
     * emite la alerta (la bodycam no manda mensajes por el data stream, asi
     * que esta es la unica via para que TODOS los telefonos se enteren), y al
     * apagarse se emite la cancelacion para cerrar popups y avisar el corte.
     */
    private fun onBodycamStreamChanged(streaming: Boolean) {
        if (streaming == bodycamStreamActive) return
        bodycamStreamActive = streaming
        val ahora = System.currentTimeMillis()
        if (streaming) {
            _incomingSos.tryEmit(
                SosAlert(
                    sessionKey = "$BODYCAM_UID@$ahora",
                    officer = BODYCAM_OFFICER,
                    uid = BODYCAM_UID,
                    startedAtMillis = ahora,
                    // La bodycam no emite GPS; el agente que la lleva comparte
                    // su posicion desde el telefono como cualquier otro.
                    latitude = null,
                    longitude = null,
                ),
            )
        } else {
            // Este corte no se fija en el mapa (sosSignalCuts): la bodycam no
            // emite GPS, asi que no hay posicion donde anclar el aviso.
            _incomingSosCancel.tryEmit(
                SosCancel(
                    officer = BODYCAM_OFFICER,
                    uid = BODYCAM_UID,
                    timestampMillis = ahora,
                    latitude = null,
                    longitude = null,
                ),
            )
        }
    }

    private fun startLocationSharingIfPermitted() {
        if (sharingLocation || !started) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        sharingLocation = true

        scope.launch {
            locationRepository.locationUpdates().collect { posicion ->
                lastLatitude = posicion.latitude
                lastLongitude = posicion.longitude
                // El envio se limita a uno por segundo para no saturar el data
                // stream en movimiento rapido; el heartbeat cubre lo demas.
                val ahora = System.currentTimeMillis()
                if (ahora - lastLocationSentAt >= LOCATION_SEND_INTERVAL_MILLIS) {
                    lastLocationSentAt = ahora
                    sendCurrentLocation()
                }
            }
        }
    }

    /**
     * Reenvia la ultima posicion cada 3 segundos. Los mensajes del data stream
     * son efimeros: sin este heartbeat, un agente quieto desapareceria para
     * cualquier companero que se conecte despues.
     */
    private fun startLocationHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                sendCurrentLocation()
            }
        }
    }

    /**
     * Reanuncia el SOS activo cada 3 segundos con la misma marca de inicio,
     * para que un dispositivo que abra la app a mitad de la emergencia tambien
     * la reciba. Los receptores descartan los reenvios por sessionKey.
     */
    private fun startSosHeartbeat() {
        sosHeartbeatJob?.cancel()
        sosHeartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                if (_sosActive.value) sendSosSignal()
            }
        }
    }

    private fun sendCurrentLocation() {
        val lat = lastLatitude ?: return
        val lng = lastLongitude ?: return
        if (!_isConnected.value) return
        val payload = JSONObject()
            .put("type", "location")
            .put("lat", lat)
            .put("lng", lng)
            .put("ts", System.currentTimeMillis())
        sendJson(payload)
    }

    private fun sendSosSignal() {
        val payload = JSONObject()
            .put("type", "emergency")
            .put("officer", sosOfficer)
            .put("ts", sosStartedAtMillis)
        lastLatitude?.let { payload.put("lat", it) }
        lastLongitude?.let { payload.put("lng", it) }
        sendJson(payload)
    }

    private fun sendSosCancel() {
        val payload = JSONObject()
            .put("type", "emergency_cancel")
            .put("officer", sosOfficer)
            .put("ts", System.currentTimeMillis())
        lastLatitude?.let { payload.put("lat", it) }
        lastLongitude?.let { payload.put("lng", it) }
        sendJson(payload)
    }

    private fun sendJson(payload: JSONObject) {
        val rtcEngine = engine ?: return
        if (dataStreamId < 0) return
        rtcEngine.sendStreamMessage(dataStreamId, payload.toString().toByteArray(Charsets.UTF_8))
    }

    /** Decodifica un mensaje JSON de otro participante y actualiza el estado. */
    private fun handleStreamMessage(remoteUid: Int, data: ByteArray) {
        val mensaje = try {
            JSONObject(String(data, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }

        when (mensaje.optString("type")) {
            "location" -> {
                val agente = RemoteAgent(
                    uid = remoteUid,
                    latitude = mensaje.optDouble("lat"),
                    longitude = mensaje.optDouble("lng"),
                    lastSeenMillis = System.currentTimeMillis(),
                )
                if (!agente.latitude.isNaN() && !agente.longitude.isNaN()) {
                    _remoteAgents.update { it + (remoteUid to agente) }
                }
            }

            "emergency" -> {
                // Un SOS nuevo del mismo agente invalida su aviso de corte
                // anterior: la emergencia vigente es la que importa.
                _sosSignalCuts.update { it - remoteUid }
                _sosOfficers.update { it + (remoteUid to mensaje.optString("officer")) }
                val startedAt = mensaje.optLong("ts")
                val sessionKey = "$remoteUid@$startedAt"
                val alerta = SosAlert(
                    sessionKey = sessionKey,
                    officer = mensaje.optString("officer"),
                    uid = remoteUid,
                    startedAtMillis = startedAt,
                    latitude = mensaje.optDoubleOrNull("lat"),
                    longitude = mensaje.optDoubleOrNull("lng"),
                )
                // El SOS queda fijado para el mapa mientras no se cancele; los
                // reenvios del heartbeat solo refrescan el mismo valor.
                _activeSosAlerts.update { it + (remoteUid to alerta) }
                // Solo la primera vez que se ve una sesion se alerta al usuario;
                // los reenvios del heartbeat de esa misma sesion se ignoran.
                if (seenSosKeys.add(sessionKey)) {
                    _incomingSos.tryEmit(alerta)
                }
            }

            "emergency_cancel" -> {
                val cancelacion = SosCancel(
                    officer = mensaje.optString("officer"),
                    uid = remoteUid,
                    timestampMillis = mensaje.optLong("ts"),
                    latitude = mensaje.optDoubleOrNull("lat"),
                    longitude = mensaje.optDoubleOrNull("lng"),
                )
                _activeSosAlerts.update { it - remoteUid }
                _incomingSosCancel.tryEmit(cancelacion)
                _sosSignalCuts.update { it + (remoteUid to cancelacion) }
            }
        }
    }

    companion object {
        private const val TAG = "AgoraRepository"

        // Mismo canal que la app Flutter: ambas versiones se ven entre si.
        private const val CHANNEL_ID = "falcon_group_channel"

        // Uid fijo con el que la bodycam entra al canal cuando transmite.
        const val BODYCAM_UID = 9001

        // Nombre que muestran las alertas SOS originadas por la bodycam.
        private const val BODYCAM_OFFICER = "BODYCAM"

        private const val LOCATION_SEND_INTERVAL_MILLIS = 1_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 3_000L
    }
}

/** Lee un double opcional del JSON; null si el campo no viene en el mensaje. */
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key)) optDouble(key).takeUnless { it.isNaN() } else null
