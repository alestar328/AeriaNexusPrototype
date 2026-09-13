package com.delta.aeria_nexus_prototype.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpCommandManager
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.KeyEvent
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
import kotlinx.coroutines.launch

private const val TAG = "GafasCommand"

/** Estado del canal de mando BLE con las gafas. */
enum class GafasControlState { DESCONECTADO, CONECTANDO, LISTO, ERROR }

/** Lo que las gafas cuentan de si mismas cuando se les pregunta. */
data class GafasEstadoCamara(
    val anchoVideo: Int = 0,
    val altoVideo: Int = 0,
    /** Kilobytes libres en la tarjeta (el SDK no los da en bytes), o -1 si no se sabe. */
    val kilobytesLibres: Long = -1L,
)

/**
 * Canal de mando de las gafas BleeqUp Ranger: arrancar y parar la grabacion, hacer
 * una foto y preguntar por la camara y la tarjeta.
 *
 * Es el hermano de mando de [GafasRepository], que solo observa el enlace de audio
 * para pintar el indicador de la barra. Son dos enlaces distintos con el mismo
 * aparato: aquel es Bluetooth clasico (HFP/A2DP, lo trae el sistema) y este es un
 * GATT propietario que **mantiene abierto `BodycamService`** todo el turno, porque
 * el SOS de la bodycam tiene que encontrarlo ya abierto (ver [ReleGafas]). Antes
 * nacia y moria con la pantalla FALCON LENS, que ahora es solo un indicador.
 *
 * ## Como se sabe si estan grabando
 *
 * **No hay ninguna orden para preguntarselo**: el SDK trae `startRecord` y
 * `stopRecord` pero nada que devuelva el estado, ni publico ni interno (comprobado
 * sobre el AAR el 2026-09-13). Asi que [grabando] no se consulta, se **mantiene**
 * con las tres cosas que el aparato cuenta solo:
 *
 * 1. Lo que le hemos mandado nosotros.
 * 2. El **boton fisico** del agente. Medido: el gesto largo del boton derecho
 *    conmuta la grabacion, y el corto hace una foto. Sin esto, en cuanto el agente
 *    tocaba las gafas el valor se quedaba desfasado y la pantalla mentia.
 * 3. El **aviso de video**, que llega cuando las gafas CIERRAN un fichero. Ese es
 *    el unico dato duro de los tres: si hay aviso, la grabacion termino, venga de
 *    donde venga la orden. Por eso manda sobre los otros dos.
 *
 * ## Lo que graban las gafas y lo que llega a Nexus
 *
 * El video se queda en la tarjeta de las gafas. Traerlo exige su punto de acceso
 * WiFi (ver [GafasMediaRepository]), y **las gafas lo apagan mientras graban**: no
 * se puede descargar hasta que la grabacion pare.
 */
class GafasCommandRepository(private val context: Context) {

    private val _estado = MutableStateFlow(GafasControlState.DESCONECTADO)
    val estado: StateFlow<GafasControlState> = _estado.asStateFlow()

    private val _grabando = MutableStateFlow(false)
    val grabando: StateFlow<Boolean> = _grabando.asStateFlow()

    private val _camara = MutableStateFlow(GafasEstadoCamara())
    val camara: StateFlow<GafasEstadoCamara> = _camara.asStateFlow()

    /**
     * Respuestas cortas de las gafas, para enseñarselas al agente. Es un flujo de
     * eventos y no estado: un mismo mensaje repetido tiene que volver a verse.
     */
    private val _mensajes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val mensajes: SharedFlow<String> = _mensajes.asSharedFlow()

    /**
     * Nombre de cada video que las gafas CIERRAN, venga la orden de donde venga.
     *
     * Es el aviso que dispara todo lo de despues: ese fichero se queda en la
     * tarjeta de las gafas y hay que ir a por el por su WiFi. Ver [ReleGafas].
     */
    private val _videosCerrados = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val videosCerrados: SharedFlow<String> = _videosCerrados.asSharedFlow()

    private var aparato: BleeqUpDevice? = null

    // El aparato del intento EN CURSO, que todavia no ha dado READY. Sin guardarlo,
    // abandonar un intento colgado dejaba al SDK conectando por su cuenta y su
    // onConnected tardio se mezclaba con el intento siguiente: se llegaron a ver
    // tres "GATT abierto" en el mismo milisegundo.
    private var aparatoEnIntento: BleeqUpDevice? = null

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // True mientras alguien quiera el canal abierto de forma permanente. El bucle
    // reintenta hasta que vuelva a false: el GATT de las gafas se cae solo (medido
    // el 2026-09-13, se solto a los 22 minutos sin que nadie lo pidiera), asi que
    // abrirlo una vez y confiar no vale para algo que tiene que responder a un SOS.
    @Volatile private var canalDeseado = false
    private var trabajoDelEnlace: Job? = null

    /**
     * El SDK guarda **un solo** oyente en una variable estatica: registrar otro
     * sustituye a este. La sonda [GafasApPrueba] tambien registra el suyo, asi que
     * no se puede usar las dos cosas a la vez; la sonda solo existe en depuracion.
     */
    private val oyente = object : BleeqUpDeviceManager.BluetoothListener {
        override fun onConnected(device: BleeqUpDevice) {
            Log.i(TAG, "GATT abierto; esperando READY")
        }

        override fun onReady(device: BleeqUpDevice) {
            // CONNECTED todavia no acepta ordenes: READY es cuando el SDK ya tiene
            // MTU y notificaciones. Todo lo que se mande antes responde
            // "the device is not ready".
            aparato = device
            _estado.value = GafasControlState.LISTO
            escucharCamara(device)
            escucharBotones(device)
            refrescarEstado()
        }

        override fun onDisconnected(device: BleeqUpDevice) {
            Log.i(TAG, "GATT cerrado")
            olvidar()
        }

        override fun onError(device: BleeqUpDevice, code: Int, message: String) {
            Log.e(TAG, "error del SDK codigo=$code: $message")
            _estado.value = GafasControlState.ERROR
            _mensajes.tryEmit(message)
        }

        override fun onBondStateChanged(device: BluetoothDevice, state: Int) = Unit
    }

    /**
     * Avisa de los ficheros que las gafas van cerrando.
     *
     * Medido contra el aparato el 2026-09-13: **el aviso de video llega al PARAR**,
     * no al empezar (1,8 s despues del gesto de parada). Y llega por cualquier
     * grabacion, tambien las que no pedimos nosotros. Asi que un aviso de video es
     * la prueba de que la grabacion termino, y corrige [grabando] sin preguntar.
     */
    private fun escucharCamara(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerCameraCallback(
            device,
            object : BleeqUpCommandManager.CameraListener {
                override fun onVideoInfo(info: String) {
                    Log.i(TAG, "aviso de video: $info")
                    _grabando.value = false
                    // Este nombre es el bueno. El que devuelve stopRecord NO EXISTE
                    // en la tarjeta: comprobado contra el listado por WiFi, 3 de 3.
                    _videosCerrados.tryEmit(info)
                    _mensajes.tryEmit("Video on the glasses: $info")
                }

                override fun onPhotoInfo(info: String) {
                    Log.i(TAG, "aviso de foto: $info")
                    _mensajes.tryEmit("Photo on the glasses: $info")
                }
            },
        )
    }

    /**
     * Sigue los botones fisicos de las gafas para no quedarse desfasado.
     *
     * El manager pidio que el agente no maneje los perifericos desde la app, asi
     * que esto **no es una forma de mandar**: es la forma de enterarse si los toca
     * de todos modos. Sin ello, un agente que arranque la grabacion a mano dejaria
     * a la app diciendo que las gafas estan paradas.
     *
     * Semantica medida el 2026-09-13, con el gesto repetido tres veces: derecho
     * corto hace una **foto**, derecho largo **conmuta la grabacion**, e izquierdo
     * corto no toca la camara. Son dos muestras del gesto largo, asi que el aviso
     * de video sigue siendo quien tiene la ultima palabra.
     */
    private fun escucharBotones(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerButtonCallback(
            device,
            object : BleeqUpCommandManager.ButtonListener {
                override fun onLeftButtonEvent(evento: KeyEvent) {
                    Log.i(TAG, "boton izquierdo de las gafas: $evento")
                }

                override fun onRightButtonEvent(evento: KeyEvent) {
                    Log.i(TAG, "boton derecho de las gafas: $evento")
                    if (evento != KeyEvent.long) return
                    val grabandoAhora = !_grabando.value
                    _grabando.value = grabandoAhora
                    _mensajes.tryEmit(
                        if (grabandoAhora) {
                            "Recording started from the glasses"
                        } else {
                            "Recording stopped from the glasses"
                        },
                    )
                }
            },
        )
    }

    /**
     * Pide que el canal este abierto y **se mantenga** abierto.
     *
     * Es lo que necesita [ReleGafas]: cuando el oficial pulsa grabar o el SOS en la
     * bodycam no hay tiempo de abrir un GATT, y fuera de la pantalla FALCON LENS
     * antes no habia enlace ninguno. Idempotente.
     */
    fun mantenerCanal() {
        if (canalDeseado) return
        canalDeseado = true
        trabajoDelEnlace = alcance.launch { bucleDelEnlace() }
    }

    /** Deja de querer el canal y lo cierra. */
    fun soltarCanal() {
        canalDeseado = false
        trabajoDelEnlace?.cancel()
        trabajoDelEnlace = null
        desconectar()
    }

    /**
     * Mantiene el canal vivo mientras se quiera, reintentando con espera creciente.
     *
     * El estado CONECTANDO necesita su propio limite: el SDK puede abrir el GATT y
     * no dar nunca READY, y sin READY no acepta ordenes. Medido el 2026-09-13, READY
     * tardo entre 0,3 y 5 segundos; pasado [ESPERA_DE_READY_MILLIS] se da por malo y
     * se suelta, porque un canal a medias no sirve para un SOS.
     */
    private suspend fun bucleDelEnlace() {
        var intentosFallidos = 0
        while (canalDeseado) {
            when (_estado.value) {
                GafasControlState.LISTO -> {
                    intentosFallidos = 0
                    delay(COMPROBACION_MILLIS)
                }

                GafasControlState.CONECTANDO -> {
                    delay(ESPERA_DE_READY_MILLIS)
                    if (_estado.value == GafasControlState.CONECTANDO) {
                        Log.w(TAG, "el SDK no dio READY a tiempo: se suelta y se reintenta")
                        desconectar()
                    }
                }

                GafasControlState.DESCONECTADO, GafasControlState.ERROR -> {
                    intentosFallidos++
                    conectar()
                    delay(esperaDeReintento(intentosFallidos))
                }
            }
        }
    }

    private fun esperaDeReintento(intento: Int): Long = when (intento) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        else -> 10_000L
    }

    /** Abre el canal de mando. No hace nada si ya esta abierto o abriendose. */
    fun conectar() {
        if (_estado.value == GafasControlState.CONECTANDO ||
            _estado.value == GafasControlState.LISTO
        ) {
            return
        }
        if (GafasSdkPuente.sondaTieneElCanal) {
            _estado.value = GafasControlState.ERROR
            _mensajes.tryEmit("The debug probe is holding the channel")
            return
        }
        _estado.value = GafasControlState.CONECTANDO
        GafasSdkPuente.iniciarSdk(context)
        val emparejado = GafasSdkPuente.aparatoEmparejado(context)
        if (emparejado == null) {
            _estado.value = GafasControlState.ERROR
            _mensajes.tryEmit("Paired device not found")
            return
        }
        aparatoEnIntento = emparejado
        BleeqUpDeviceManager.registerCallback(oyente)
        BleeqUpDeviceManager.connect(emparejado, GafasSdkPuente.ESPERA_CONEXION_MS) { abierto ->
            // Aqui solo se sabe si el GATT se abrio; las ordenes esperan a READY.
            if (!abierto) {
                _estado.value = GafasControlState.ERROR
                _mensajes.tryEmit("The glasses are not responding")
            }
        }
    }

    /**
     * Cierra el canal de mando.
     *
     * Soltar el GATT no es opcional: sin esto Android cachea el enlace, la siguiente
     * conexion sale "instantanea" reusando el viejo y las escrituras empiezan a
     * fallar con `write characteristic error`.
     */
    fun desconectar() {
        // Si el intento nunca llego a READY, 'aparato' es null pero el SDK sigue
        // conectando: hay que soltarlo igual o se queda un GATT huerfano abierto.
        val device = aparato ?: aparatoEnIntento
        if (device != null) {
            runCatching { BleeqUpCommandManager.unregisterCameraCallback(device) }
            runCatching { BleeqUpCommandManager.unregisterButtonCallback(device) }
            runCatching { BleeqUpDeviceManager.disconnect(device) }
                .onFailure { Log.w(TAG, "no se pudo soltar el GATT: ${it.message}") }
        }
        olvidar()
    }

    /**
     * Empieza a grabar en la tarjeta de las gafas.
     *
     * [alTerminar] recibe si las gafas aceptaron la orden. Lo necesita el rele del
     * SOS, que tiene que dejar constancia de si obedecieron: el oficial no va a
     * estar mirando el telefono para comprobarlo.
     *
     * Ojo con el mensaje del SDK: medido el 2026-09-13, `startRecord` responde
     * `ok=true` con el texto "start record failure" y graba perfectamente. El que
     * vale es el `ok`; el texto no se le puede enseñar a nadie.
     */
    fun iniciarGrabacion(alTerminar: ((Boolean) -> Unit)? = null) {
        val device = listasParaOrdenes()
        if (device == null) {
            alTerminar?.invoke(false)
            return
        }
        // Medido el 2026-09-13: con el canal en mal estado, startRecord se manda y
        // su callback NO llega nunca. Sin este limite el rele se quedaba esperando
        // en silencio y la grabacion salia sin gafas sin que constara en ningun
        // sitio. El primero que llegue, respuesta o limite, es el que contesta.
        val yaRespondio = java.util.concurrent.atomic.AtomicBoolean(false)
        BleeqUpCommandManager.startRecord(device) { ok, mensaje ->
            Log.i(TAG, "startRecord -> ok=$ok $mensaje")
            if (!yaRespondio.compareAndSet(false, true)) return@startRecord
            if (ok) _grabando.value = true
            _mensajes.tryEmit(if (ok) "Recording on the glasses" else "Did not start: $mensaje")
            alTerminar?.invoke(ok)
        }
        alcance.launch {
            delay(ESPERA_DE_ORDEN_MILLIS)
            if (!yaRespondio.compareAndSet(false, true)) return@launch
            Log.e(TAG, "startRecord no contesto en $ESPERA_DE_ORDEN_MILLIS ms: se da por fallido")
            _mensajes.tryEmit("The glasses did not answer")
            // El canal esta en mal estado aunque diga LISTO: soltarlo hace que el
            // bucle lo rehaga, en vez de dejarlo roto hasta la siguiente grabacion.
            desconectar()
            alTerminar?.invoke(false)
        }
    }

    /** Para la grabacion y vuelve a preguntar cuanto queda en la tarjeta. */
    fun pararGrabacion() {
        val device = listasParaOrdenes() ?: return
        BleeqUpCommandManager.stopRecord(device) { ok, mensaje ->
            Log.i(TAG, "stopRecord -> ok=$ok $mensaje")
            if (ok) {
                _grabando.value = false
                refrescarEstado()
            }
            _mensajes.tryEmit(if (ok) "Recording stopped" else "Did not stop: $mensaje")
        }
    }

    /** Dispara una foto, que se guarda tambien en la tarjeta de las gafas. */
    fun hacerFoto() {
        val device = listasParaOrdenes() ?: return
        BleeqUpCommandManager.takePhoto(device) { ok, mensaje ->
            Log.i(TAG, "takePhoto -> ok=$ok $mensaje")
            _mensajes.tryEmit(if (ok) "Photo taken" else "Photo failed: $mensaje")
        }
    }

    /** Relee resolucion y espacio libre. Se llama al conectar y al parar de grabar. */
    fun refrescarEstado() {
        val device = aparato ?: return
        BleeqUpCommandManager.getCameraSetting(device) { ok, resolucion, mensaje ->
            if (ok) {
                _camara.value = _camara.value.copy(
                    anchoVideo = resolucion.width,
                    altoVideo = resolucion.height,
                )
            } else {
                Log.w(TAG, "getCameraSetting fallo: $mensaje")
            }
        }
        BleeqUpCommandManager.getStorageInfo(device) { ok, almacen, mensaje ->
            if (ok) {
                _camara.value = _camara.value.copy(kilobytesLibres = almacen.free)
            } else {
                Log.w(TAG, "getStorageInfo fallo: $mensaje")
            }
        }
    }

    /**
     * El aparato para las ordenes de WiFi, que las manda [BleeqUpWifiManager].
     *
     * Es el mismo GATT, pero otro objeto del SDK, asi que necesita el aparato en
     * crudo. Devuelve null si el canal no esta listo, que es cuando el SDK
     * responde "the device is not ready" a todo.
     */
    internal fun aparatoParaWifi(): BleeqUpDevice? =
        aparato.takeIf { _estado.value == GafasControlState.LISTO }

    /** El aparato si el canal acepta ordenes, y si no un aviso para el agente. */
    private fun listasParaOrdenes(): BleeqUpDevice? {
        val device = aparato
        if (device == null || _estado.value != GafasControlState.LISTO) {
            _mensajes.tryEmit("The glasses are not connected")
            return null
        }
        return device
    }

    private companion object {
        /** Cada cuanto comprueba el bucle que el canal sigue en pie. */
        const val COMPROBACION_MILLIS = 5_000L

        /** Lo que se le concede al SDK para pasar de GATT abierto a READY. */
        const val ESPERA_DE_READY_MILLIS = 10_000L

        /** Lo que se espera a que el SDK conteste una orden antes de darla por perdida. */
        const val ESPERA_DE_ORDEN_MILLIS = 6_000L
    }

    private fun olvidar() {
        aparato = null
        aparatoEnIntento = null
        _estado.value = GafasControlState.DESCONECTADO
        // El estado de grabacion solo valia mientras hubiera enlace: sin canal no
        // hay forma de saber si siguen grabando, y afirmarlo seria inventarselo.
        _grabando.value = false
        _camara.value = GafasEstadoCamara()
    }
}
