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
import java.util.UUID
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
    private val sosNotifier: SosNotifier,
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

    // Identidad del SOS en curso ante el backend, que lo graba en la nube: un id por
    // emergencia hace idempotentes sus tres avisos (ver SosNotifier).
    private var sosId = ""
    private var ultimoLatidoBackendMillis = 0L

    // Uid con el que este telefono entro al canal. El backend graba el SOS por uid.
    private var miUid = 0

    // Claves de sesion SOS ya vistas, para ignorar los reenvios del heartbeat.
    private val seenSosKeys = mutableSetOf<String>()

    // Bodycams que estan publicando video en el canal, por uid. Que una bodycam
    // transmita ES su senal de SOS: asi funciona su boton fisico 133 y tambien el
    // comando STREAM_START enviado desde el telefono. Es un conjunto y no un flag
    // porque cada unidad tiene su uid y dos pueden estar en SOS a la vez.
    // Los callbacks de Agora llegan todos por el mismo hilo, como uidsEnEscucha.
    private val bodycamsEmitiendo = mutableSetOf<Int>()

    // Bodycams con el PTT abierto, por uid. El aviso se enciende con la primera y
    // se apaga cuando se calla la ultima.
    private val bodycamsHablando = mutableSetOf<Int>()

    private val _remoteAgents = MutableStateFlow<Map<Int, RemoteAgent>>(emptyMap())
    val remoteAgents: StateFlow<Map<Int, RemoteAgent>> = _remoteAgents.asStateFlow()

    private val _connectedUsers = MutableStateFlow(0)
    val connectedUsers: StateFlow<Int> = _connectedUsers.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Estado real del canal, el que pinta la barra superior. Hasta el 2026-09-15 la
    // barra decia ONLINE fija: un telefono que llevaba horas fuera del canal seguia
    // en verde mientras su PTT y su SOS no llegaban a nadie.
    private val _estadoCanal = MutableStateFlow(EstadoCanal.CONECTANDO)
    val estadoCanal: StateFlow<EstadoCanal> = _estadoCanal.asStateFlow()

    // Reentrada en curso tras perder el canal; una sola a la vez.
    private var reentradaJob: Job? = null

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

    // Alguna bodycam del sistema tiene el PTT abierto y su voz esta sonando en el
    // canal. Se deduce del estado de su audio remoto, igual que el SOS
    // de la bodycam se deduce de su video: la camara no manda nada por el data
    // stream, asi que su propio audio es el unico anuncio que llega a todos.
    //
    // Es un aviso, NO una alarma: el PTT es trafico de radio rutinario y no debe
    // usar el popup del SOS. Ver PttAvisoOverlay.
    private val _bodycamHablando = MutableStateFlow(false)
    val bodycamHablando: StateFlow<Boolean> = _bodycamHablando.asStateFlow()

    // PENDIENTE: identidad del oficial que habla.
    //
    // Desde el 2026-09-14 cada bodycam entra con su propio uid, asi que ya se sabe
    // QUE unidad habla, pero no QUIEN la lleva: eso lo sabe la atadura, que solo
    // conoce el telefono emparejado. Por eso el aviso sigue siendo generico y este
    // flujo se queda preparado sin alimentar.
    //
    // Cuando haya autenticacion y usuarios de prueba reales, el camino mas corto NO
    // pasa por tocar la bodycam (que no publica en el data stream): es el TELEFONO
    // EMPAREJADO quien lo anuncia, porque ya sabe los tres datos que hacen falta:
    //   1. la atadura (bwcId, userId, sujeto) → quien lleva esa camara
    //   2. el estado del PTT por Bluetooth → BTN_PTT_ON/OFF y el campo "ptt" del STATUS
    //   3. el data stream ya abierto, donde manda su GPS cada 3 s
    // Bastaria con un mensaje del tipo que ya existe ("emergency"/"emergency_cancel"):
    //   {"type":"ptt","officer":<sujeto>,"bwc":<bwcId>,"ts":<millis>}
    // y atenderlo en handleStreamMessage() para rellenar este flujo.
    //
    // El aviso generico debe SEGUIR funcionando aunque eso llegue: si el telefono del
    // agente esta apagado o fuera de alcance BT, el anuncio no sale pero el audio si,
    // y los demas tienen que enterarse igual.
    private val _oficialHablando = MutableStateFlow<String?>(null)
    val oficialHablando: StateFlow<String?> = _oficialHablando.asStateFlow()

    // El PTT de ESTE telefono: el agente mantiene pulsado el boton de Operations y
    // su voz sale al canal. Es el mismo servicio de radio que el boton F2 de la
    // bodycam, para el agente que no la lleva puesta o la tiene en la mochila.
    private val _pttPropioActivo = MutableStateFlow(false)
    val pttPropioActivo: StateFlow<Boolean> = _pttPropioActivo.asStateFlow()

    // Companeros con el PTT abierto DESDE SU TELEFONO, por uid → numero de oficial.
    // Las bodycams no entran aqui: su PTT se detecta por su audio
    // (bodycamHablando), porque no publican nada en el data stream.
    private val _pttsRemotos = MutableStateFlow<Map<Int, String>>(emptyMap())
    val pttsRemotos: StateFlow<Map<Int, String>> = _pttsRemotos.asStateFlow()

    // Uids cuyo audio esta abierto porque se esta viendo su livestream. Hay que
    // llevar la cuenta: sin ella, el "ptt_off" de un agente al que ademas se le
    // esta viendo el video lo dejaria mudo en mitad de su propia emergencia.
    private val uidsEnEscucha = mutableSetOf<Int>()

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
            _estadoCanal.value = EstadoCanal.CONECTADO
            _connectedUsers.value = 1
            sendCurrentLocation()
            // Tras volver a entrar, el canal nuevo no sabe nada del SOS que seguia
            // en pie: hay que publicar otra vez la camara y reanunciarlo. Fuera del
            // hilo de Agora, que no admite llamadas al motor desde sus callbacks.
            if (_sosActive.value) {
                scope.launch {
                    startCameraPublish()
                    sendSosSignal()
                }
            }
        }

        /**
         * Los cortes cortos los arregla Agora solo (RECONNECTING y vuelta a
         * CONNECTED). Lo que no arregla es FAILED: tras unos 20 min sin red da la
         * conexion por perdida y ya no lo intenta mas. Medido el 2026-09-15 en el
         * Samsung: fuera del canal desde la 01:44 hasta reiniciar la app.
         */
        override fun onConnectionStateChanged(state: Int, reason: Int) {
            Log.i(TAG, "Canal: estado $state motivo $reason")
            _isConnected.value = state == Constants.CONNECTION_STATE_CONNECTED
            _estadoCanal.value = when (state) {
                Constants.CONNECTION_STATE_CONNECTED -> EstadoCanal.CONECTADO
                Constants.CONNECTION_STATE_CONNECTING -> EstadoCanal.CONECTANDO
                Constants.CONNECTION_STATE_RECONNECTING -> EstadoCanal.RECONECTANDO
                else -> EstadoCanal.DESCONECTADO
            }
            if (state == Constants.CONNECTION_STATE_FAILED) alPerderElCanal()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            // Al volver a entrar se reciben de nuevo todos los que ya estaban; a
            // quien se estuviera viendo en livestream hay que volver a oirle.
            if (uid in uidsEnEscucha) engine?.muteRemoteAudioStream(uid, false)
            // Una bodycam es un dispositivo, no un agente: no debe inflar el
            // contador de usuarios.
            if (!esBodycam(uid)) _connectedUsers.value++
            // Excepcion al autoSubscribeAudio=false: a la bodycam se la escucha
            // siempre, sin aceptar ningun livestream. Es el PTT — el agente pulsa
            // F2 y su voz tiene que llegar a TODOS los telefonos del sistema, que
            // es justo lo que el Bluetooth no puede hacer porque solo alcanza al
            // movil emparejado. No reabre el problema que cerraba
            // autoSubscribeAudio=false (que cada voz sonase en todo el canal):
            // la bodycam solo publica audio mientras el PTT esta abierto.
            if (esBodycam(uid)) escucharBodycam(uid)
            // Reenviamos posicion y SOS activo para que el recien llegado nos
            // vea de inmediato, sin esperar al siguiente heartbeat.
            sendCurrentLocation()
            if (_sosActive.value) sendSosSignal()
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            if (!esBodycam(uid) && _connectedUsers.value > 1) _connectedUsers.value--
            // Quien se va del canal con el PTT abierto no llega a mandar su
            // "ptt_off": sin esto su banda se quedaria puesta para siempre.
            uidsEnEscucha.remove(uid)
            cerrarPttRemoto(uid)
            if (esBodycam(uid)) {
                onBodycamStreamChanged(uid, streaming = false)
                // Si se va del canal con el PTT abierto no llega ningun cambio de
                // estado de audio, y el aviso se quedaria colgado para siempre.
                marcarBodycamHablando(uid, hablando = false)
                if (bodycamsHablando.isEmpty()) _oficialHablando.value = null
            }
            // Un emisor que se desconecta equivale a un livestream cortado.
            _remoteVideoStopped.value = uid
            // Solo se quita el marcador si el agente salio del canal a proposito.
            // En una caida transitoria (tunel, sin cobertura) se conserva la
            // ultima posicion conocida y el mapa lo pinta como "sin senal".
            if (reason == Constants.USER_OFFLINE_QUIT) {
                _remoteAgents.update { it - uid }
            }
        }

        /**
         * El PTT de la bodycam visto desde fuera: mientras su pista de audio siga
         * viva, hay un companero con el microfono abierto.
         *
         * Solo STOPPED y FAILED cierran el aviso. FROZEN (3) NO: medido con la W1
         * el 2026-09-08, el estado va y viene entre DECODING y FROZEN cada pocos
         * segundos con el PTT perfectamente abierto — basta un silencio del agente
         * para que el flujo se congele. Tratar FROZEN como "ya no habla" haria
         * parpadear la banda durante toda la transmision.
         */
        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            if (!esBodycam(uid)) return
            marcarBodycamHablando(
                uid,
                hablando = when (state) {
                    Constants.REMOTE_AUDIO_STATE_STOPPED,
                    Constants.REMOTE_AUDIO_STATE_FAILED,
                    -> false
                    else -> true
                },
            )
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            when (state) {
                Constants.REMOTE_VIDEO_STATE_STOPPED,
                Constants.REMOTE_VIDEO_STATE_FAILED,
                -> {
                    _remoteVideoStopped.value = uid
                    if (esBodycam(uid)) onBodycamStreamChanged(uid, streaming = false)
                }

                Constants.REMOTE_VIDEO_STATE_STARTING,
                Constants.REMOTE_VIDEO_STATE_DECODING,
                -> {
                    if (_remoteVideoStopped.value == uid) _remoteVideoStopped.value = null
                    if (esBodycam(uid)) onBodycamStreamChanged(uid, streaming = true)
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

            // Cada telefono entra con un uid aleatorio, como en Falcon One, pero por
            // encima del rango reservado a servicios (bodycams y grabador en la nube,
            // ver esBodycam): coincidir con el grabador romperia la grabacion.
            miUid = Random.nextInt(PRIMER_UID_TELEFONO, Int.MAX_VALUE)
            rtcEngine.joinChannel(null, CHANNEL_ID, miUid, opcionesDelCanal())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar la red tactica", e)
            engine = null
            started = false
            return
        }

        startLocationSharingIfPermitted()
        startLocationHeartbeat()
    }

    private fun opcionesDelCanal() = ChannelMediaOptions().apply {
        channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
        // El audio ajeno se escucha solo al aceptar un livestream; con
        // auto-subscribe cada voz publicada sonaria en todo el canal.
        // Unica excepcion: las bodycams, que se suscriben a mano en
        // onUserJoined porque su audio ES el PTT. Ver escucharBodycam().
        autoSubscribeAudio = false
        autoSubscribeVideo = true
        publishMicrophoneTrack = false
        publishCameraTrack = false
    }

    /**
     * Agora ha dado el canal por perdido. Todo lo que dependia de estar dentro se
     * cae aqui, en vez de quedarse mintiendo, y se empieza a volver a entrar.
     */
    private fun alPerderElCanal() {
        Log.w(TAG, "Canal perdido: se vuelve a entrar")
        _connectedUsers.value = 0
        // Un PTT abierto ya no llega a nadie. Se cierra con el zumbido, que es lo
        // unico que le dice al agente que ha dejado de oirsele. No se manda
        // ptt_off: no hay canal por donde mandarlo.
        if (_pttPropioActivo.value) {
            _pttPropioActivo.value = false
            if (!_sosActive.value) scope.launch { apagarMicrofono() }
            PttTones.denegado()
        }
        // De los demas no van a llegar ni ptt_off ni onUserOffline: se limpian
        // las bandas y los SOS de bodycam, o se quedarian puestos para siempre.
        // Al volver a entrar, quien siga hablando o emitiendo vuelve a avisar.
        _pttsRemotos.value = emptyMap()
        bodycamsHablando.clear()
        _bodycamHablando.value = false
        _oficialHablando.value = null
        bodycamsEmitiendo.toList().forEach { onBodycamStreamChanged(it, streaming = false) }
        reentrar()
    }

    /**
     * Sale y vuelve a entrar con el mismo motor y el mismo uid: el backend graba el
     * SOS por uid, y cambiarlo a mitad de una emergencia partiria la grabacion.
     */
    private fun reentrar() {
        if (reentradaJob?.isActive == true) return
        reentradaJob = scope.launch {
            while (isActive && !_isConnected.value) {
                delay(REENTRADA_MILLIS)
                val rtcEngine = engine ?: return@launch
                if (_isConnected.value) return@launch
                rtcEngine.leaveChannel()
                val resultado = rtcEngine.joinChannel(null, CHANNEL_ID, miUid, opcionesDelCanal())
                Log.i(TAG, "Reentrando al canal: $resultado")
                // Un join aceptado sigue su curso solo: si vuelve a fallar, el
                // callback de FAILED lanza otra reentrada.
                if (resultado == 0) return@launch
            }
        }
    }

    /** Solo debug: dispara la perdida del canal sin esperar 20 min sin red. */
    fun simularFalloDelCanalDebug() {
        _isConnected.value = false
        _estadoCanal.value = EstadoCanal.DESCONECTADO
        alPerderElCanal()
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
        sosId = UUID.randomUUID().toString()
        ultimoLatidoBackendMillis = System.currentTimeMillis()
        sosNotifier.inicio(sosId, CHANNEL_ID, miUid, officer, lastLatitude, lastLongitude)
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
        sosNotifier.fin(sosId, "cancelled")
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
        uidsEnEscucha.add(uid)
        rtcEngine.muteRemoteVideoStream(uid, false)
        rtcEngine.muteRemoteAudioStream(uid, false)
    }

    /** Deja de escuchar al agente [uid] y libera su vista al salir de la pantalla. */
    fun stopWatching(uid: Int) {
        val rtcEngine = engine ?: return
        uidsEnEscucha.remove(uid)
        // A la bodycam se la sigue oyendo aunque se cierre su livestream: su audio
        // es el PTT, que vive por su cuenta y no se apaga al dejar de ver el video.
        // Por lo mismo tampoco se silencia a un agente que este hablando por su PTT:
        // cerrar su video no cierra su radio.
        if (!esBodycam(uid) && uid !in _pttsRemotos.value) {
            rtcEngine.muteRemoteAudioStream(uid, true)
        }
        rtcEngine.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    /**
     * Abre la escucha del audio de la bodycam [uid] y la deja abierta. Es el
     * canal del PTT: la bodycam solo publica voz mientras el agente tiene el
     * microfono abierto, asi que suscribirse de forma permanente no mete ruido.
     */
    private fun escucharBodycam(uid: Int) {
        engine?.muteRemoteAudioStream(uid, false)
    }

    /** True si el sistema ya concedio el microfono; el PTT no puede abrirse sin el. */
    fun tienePermisoMicrofono(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Abre el microfono de ESTE telefono y lo publica al canal: el PTT propio,
     * el mismo servicio de radio que el boton F2 de la bodycam pero para el agente
     * que no la lleva encima. Se mantiene abierto mientras dure la pulsacion.
     *
     * Devuelve false si no se pudo abrir (sin permiso de microfono o sin motor);
     * la pantalla lo usa para no encender el indicador de "transmitiendo" cuando
     * en realidad no sale voz — el error que la bodycam ya pago una vez.
     */
    fun iniciarPtt(officer: String): Boolean {
        ensureStarted()
        // Sin motor o sin permiso el PTT no se abre, y el agente tiene que OIRLO:
        // el indicador de "transmitiendo" no se enciende, pero nadie mira la
        // pantalla mientras habla por radio.
        val rtcEngine = engine ?: run { PttTones.denegado(); return false }
        if (_pttPropioActivo.value) return true
        // Fuera del canal la voz no llega a nadie: mejor el zumbido que un ON AIR
        // hablandole al vacio, que es exactamente lo que paso el 2026-09-15.
        if (!_isConnected.value) {
            PttTones.denegado()
            return false
        }
        if (!tienePermisoMicrofono()) {
            PttTones.denegado()
            return false
        }

        // El tono arranca antes de publicar el microfono: asi el pitido se queda
        // en el telefono del que habla y no sale al canal; del solape que quede se
        // encarga el cancelador de eco de Agora.
        PttTones.abrir()

        // El telefono es receptor estricto: fuera del SOS la captura esta apagada
        // y el volumen de grabacion a cero. Hay que deshacer las tres cosas.
        rtcEngine.enableLocalAudio(true)
        rtcEngine.adjustRecordingSignalVolume(100)
        rtcEngine.muteLocalAudioStream(false)
        rtcEngine.updateChannelMediaOptions(
            ChannelMediaOptions().apply { publishMicrophoneTrack = true },
        )
        _pttPropioActivo.value = true

        // Publicar no basta: los demas entran con autoSubscribeAudio = false y no
        // oirian nada. Es el mismo problema que resuelve escucharBodycam(), pero
        // aqui el uid es aleatorio y no se puede cablear, asi que hay que
        // anunciarse por el data stream para que abran la escucha.
        sendJson(
            JSONObject()
                .put("type", "ptt_on")
                .put("officer", officer)
                .put("ts", System.currentTimeMillis()),
        )
        return true
    }

    /** Cierra el PTT propio: avisa a los demas y vuelve a apagar el microfono. */
    fun terminarPtt() {
        if (!_pttPropioActivo.value) return
        _pttPropioActivo.value = false
        sendJson(
            JSONObject()
                .put("type", "ptt_off")
                .put("ts", System.currentTimeMillis()),
        )
        // El SOS manda: si esta emitiendo, la voz sigue publicada como parte del
        // livestream y apagar el microfono aqui dejaria la emergencia muda.
        if (_sosActive.value) return
        apagarMicrofono()
        // Despues de apagarlo, no antes: el tono de cierre solo suena cuando el
        // microfono esta cerrado de verdad. Por eso NO suena en el caso de arriba
        // — con el SOS emitiendo la voz sigue saliendo, y decirle al agente que ha
        // soltado seria justo la mentira que hay que evitar.
        PttTones.cerrar()
    }

    /** Devuelve el microfono al estado de receptor estricto. */
    private fun apagarMicrofono() {
        val rtcEngine = engine ?: return
        rtcEngine.updateChannelMediaOptions(
            ChannelMediaOptions().apply { publishMicrophoneTrack = false },
        )
        rtcEngine.muteLocalAudioStream(true)
        rtcEngine.enableLocalAudio(false)
        rtcEngine.adjustRecordingSignalVolume(0)
    }

    /**
     * Unico sitio donde cambia [_bodycamHablando], y a proposito: el tono de
     * recepcion tiene que sonar en el FLANCO de cada bodycam, no en cada aviso.
     * onRemoteAudioStateChanged repite "true" cada pocos segundos mientras dura la
     * transmision (el estado va y viene entre DECODING y FROZEN con el PTT
     * perfectamente abierto). Un pitido por aviso seria un chasquido continuo
     * encima de la voz del companero.
     */
    private fun marcarBodycamHablando(uid: Int, hablando: Boolean) {
        val cambio = if (hablando) bodycamsHablando.add(uid) else bodycamsHablando.remove(uid)
        if (!cambio) return
        _bodycamHablando.value = bodycamsHablando.isNotEmpty()
        if (hablando) PttTones.entra() else PttTones.sale()
    }

    /**
     * Cierra el PTT del agente [uid]: quita su banda y vuelve a silenciarlo, salvo
     * que se le este viendo el livestream (ese audio no lo abrio el PTT) o sea la
     * bodycam, cuya escucha es permanente.
     */
    private fun cerrarPttRemoto(uid: Int) {
        // Solo suena si ese agente estaba hablando: aqui se entra tambien por
        // onUserOffline, que llama por cualquiera que se va del canal.
        if (uid in _pttsRemotos.value) PttTones.sale()
        _pttsRemotos.update { it - uid }
        if (esBodycam(uid) || uid in uidsEnEscucha) return
        engine?.muteRemoteAudioStream(uid, true)
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
        // El PTT puede seguir pulsado cuando se cancela el SOS: la camara se corta,
        // la voz no. Quitar el microfono aqui dejaria al agente hablando en vacio.
        val mantenerMicrofono = _pttPropioActivo.value
        rtcEngine.updateChannelMediaOptions(
            ChannelMediaOptions().apply {
                publishCameraTrack = false
                publishMicrophoneTrack = mantenerMicrofono
            },
        )
        rtcEngine.muteLocalVideoStream(true)
        if (!mantenerMicrofono) {
            rtcEngine.muteLocalAudioStream(true)
            rtcEngine.enableLocalAudio(false)
            rtcEngine.adjustRecordingSignalVolume(0)
        }
        rtcEngine.stopPreview()
    }

    /**
     * Traduce el video de la bodycam a un flujo SOS: al empezar a publicar se
     * emite la alerta (la bodycam no manda mensajes por el data stream, asi
     * que esta es la unica via para que TODOS los telefonos se enteren), y al
     * apagarse se emite la cancelacion para cerrar popups y avisar el corte.
     */
    private fun onBodycamStreamChanged(uid: Int, streaming: Boolean) {
        val cambio = if (streaming) bodycamsEmitiendo.add(uid) else bodycamsEmitiendo.remove(uid)
        if (!cambio) return
        val ahora = System.currentTimeMillis()
        if (streaming) {
            _incomingSos.tryEmit(
                SosAlert(
                    sessionKey = "$uid@$ahora",
                    officer = BODYCAM_OFFICER,
                    uid = uid,
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
                    uid = uid,
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
                if (_sosActive.value) {
                    sendSosSignal()
                    latidoAlBackend()
                }
            }
        }
    }

    /**
     * El backend corta la grabacion si deja de oir latidos, pero le basta uno cada
     * 10 s: el del canal va cada 3 s, y mandarlo tambien por HTTP gastaria datos y
     * bateria justo durante la emergencia.
     */
    private fun latidoAlBackend() {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoLatidoBackendMillis < BACKEND_HEARTBEAT_MILLIS) return
        ultimoLatidoBackendMillis = ahora
        sosNotifier.latido(sosId)
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
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        if (rtcEngine.sendStreamMessage(dataStreamId, bytes) >= 0) return
        // Tras volver a entrar al canal el stream puede haber dejado de valer: se
        // crea otro y se reintenta una vez, o el SOS y los PTT no saldrian.
        dataStreamId = rtcEngine.createDataStream(
            DataStreamConfig().apply {
                syncWithAudio = false
                ordered = true
            },
        )
        val resultado = rtcEngine.sendStreamMessage(dataStreamId, bytes)
        if (resultado < 0) Log.w(TAG, "No se pudo enviar por el data stream: $resultado")
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

            "ptt_on" -> {
                // Un companero abre su microfono desde el telefono. Su uid es
                // aleatorio (a diferencia del de una bodycam, que cae en su rango),
                // asi que la suscripcion no puede estar cableada: se abre al oir
                // el anuncio.
                engine?.muteRemoteAudioStream(remoteUid, false)
                // El tono va antes de apuntarlo, para no sonar dos veces si
                // llegase un "ptt_on" repetido del mismo agente.
                if (remoteUid !in _pttsRemotos.value) PttTones.entra()
                _pttsRemotos.update { it + (remoteUid to mensaje.optString("officer")) }
            }

            "ptt_off" -> cerrarPttRemoto(remoteUid)

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

        // Espera entre intentos de volver a entrar al canal cuando Agora lo da por perdido.
        private const val REENTRADA_MILLIS = 5_000L

        // Mismo canal que la app Flutter: ambas versiones se ven entre si.
        private const val CHANNEL_ID = "falcon_group_channel"

        // Cada bodycam entra al canal con un uid sacado de su identidad (BWC-896E
        // entra como 10000 + 0x896E), calculado en BodycamIdentity.uidAgora de
        // BodyCamServer. Aqui solo hace falta el rango, que queda por debajo del
        // grabador en la nube (90000-99999).
        private const val PRIMER_UID_BODYCAM = 10_000
        private const val ULTIMO_UID_BODYCAM = 89_999

        // Uid con el que entraban TODAS las bodycams antes del 2026-09-14. Se sigue
        // reconociendo para que una unidad sin actualizar no deje de disparar el SOS
        // en los telefonos: una emergencia perdida es peor que dos unidades que se
        // pisan. Quitarlo cuando no quede ninguna W1 con la version antigua.
        private const val UID_BODYCAM_ANTIGUO = 9001

        /** True si [uid] es una bodycam y no un telefono ni el grabador en la nube. */
        fun esBodycam(uid: Int): Boolean =
            uid == UID_BODYCAM_ANTIGUO || uid in PRIMER_UID_BODYCAM..ULTIMO_UID_BODYCAM

        // Nombre que muestran las alertas SOS originadas por la bodycam.
        private const val BODYCAM_OFFICER = "BODYCAM"

        private const val LOCATION_SEND_INTERVAL_MILLIS = 1_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 3_000L
        private const val BACKEND_HEARTBEAT_MILLIS = 10_000L

        // Por debajo quedan los uids de servicios: las bodycams (ver esBodycam) y
        // 90000-99999 el grabador en la nube (docs/BACKEND-PROXY-AND-SOS.md §2.3).
        private const val PRIMER_UID_TELEFONO = 100_000
    }
}

/** Si este telefono esta dentro del canal de Agora, que es lo que decide si se le oye. */
enum class EstadoCanal { CONECTANDO, CONECTADO, RECONECTANDO, DESCONECTADO }

/** Lee un double opcional del JSON; null si el campo no viene en el mensaje. */
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key)) optDouble(key).takeUnless { it.isNaN() } else null
