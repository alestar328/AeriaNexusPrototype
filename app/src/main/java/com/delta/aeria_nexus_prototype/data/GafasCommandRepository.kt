package com.delta.aeria_nexus_prototype.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpCommandManager
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GafasCommand"

/** Estado del canal de mando BLE con las gafas. */
enum class GafasControlState { DESCONECTADO, CONECTANDO, LISTO, ERROR }

/** Lo que las gafas cuentan de si mismas cuando se les pregunta. */
data class GafasEstadoCamara(
    val anchoVideo: Int = 0,
    val altoVideo: Int = 0,
    /** Bytes libres en la tarjeta, o -1 mientras no se sepa. */
    val bytesLibres: Long = -1L,
)

/**
 * Canal de mando de las gafas BleeqUp Ranger: arrancar y parar la grabacion, hacer
 * una foto y preguntar por la camara y la tarjeta.
 *
 * Es el hermano de mando de [GafasRepository], que solo observa el enlace de audio
 * para pintar el indicador de la barra. Son dos enlaces distintos con el mismo
 * aparato: aquel es Bluetooth clasico (HFP/A2DP, lo trae el sistema) y este es un
 * GATT propietario que se abre y se cierra desde esta pantalla.
 *
 * ## Lo que este canal NO puede decir
 *
 * **No hay ninguna orden para preguntar si las gafas estan grabando.** El SDK trae
 * `startRecord` y `stopRecord`, pero nada que devuelva el estado, asi que [grabando]
 * es solo lo que hemos mandado nosotros. Si el agente pulsa el boton fisico de las
 * gafas, este valor se queda desfasado y la pantalla miente hasta la siguiente
 * orden. Por eso la pantalla no promete "REC" como hecho, sino como ultima orden
 * dada.
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

    private var aparato: BleeqUpDevice? = null

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
     * **Sin confirmar contra el aparato**: no se sabe si `onVideoInfo` llega al
     * empezar la grabacion o al terminarla, asi que de momento solo se enseña el
     * nombre y no se toca [grabando] con el. En cuanto se vea el orden real de los
     * avisos con las gafas delante, esto puede corregir el estado.
     */
    private fun escucharCamara(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerCameraCallback(
            device,
            object : BleeqUpCommandManager.CameraListener {
                override fun onVideoInfo(info: String) {
                    Log.i(TAG, "aviso de video: $info")
                    _mensajes.tryEmit("Video en las gafas: $info")
                }

                override fun onPhotoInfo(info: String) {
                    Log.i(TAG, "aviso de foto: $info")
                    _mensajes.tryEmit("Foto en las gafas: $info")
                }
            },
        )
    }

    /** Abre el canal de mando. No hace nada si ya esta abierto o abriendose. */
    fun conectar() {
        if (_estado.value == GafasControlState.CONECTANDO ||
            _estado.value == GafasControlState.LISTO
        ) {
            return
        }
        _estado.value = GafasControlState.CONECTANDO
        GafasSdkPuente.iniciarSdk(context)
        val emparejado = GafasSdkPuente.aparatoEmparejado(context)
        if (emparejado == null) {
            _estado.value = GafasControlState.ERROR
            _mensajes.tryEmit("No se encuentra el aparato emparejado")
            return
        }
        BleeqUpDeviceManager.registerCallback(oyente)
        BleeqUpDeviceManager.connect(emparejado, GafasSdkPuente.ESPERA_CONEXION_MS) { abierto ->
            // Aqui solo se sabe si el GATT se abrio; las ordenes esperan a READY.
            if (!abierto) {
                _estado.value = GafasControlState.ERROR
                _mensajes.tryEmit("No responden las gafas")
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
        val device = aparato
        if (device != null) {
            runCatching { BleeqUpCommandManager.unregisterCameraCallback(device) }
            runCatching { BleeqUpDeviceManager.disconnect(device) }
                .onFailure { Log.w(TAG, "no se pudo soltar el GATT: ${it.message}") }
        }
        olvidar()
    }

    /** Empieza a grabar en la tarjeta de las gafas. */
    fun iniciarGrabacion() {
        val device = listasParaOrdenes() ?: return
        BleeqUpCommandManager.startRecord(device) { ok, mensaje ->
            Log.i(TAG, "startRecord -> ok=$ok $mensaje")
            if (ok) _grabando.value = true
            _mensajes.tryEmit(if (ok) "Grabando en las gafas" else "No arranco: $mensaje")
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
            _mensajes.tryEmit(if (ok) "Grabacion detenida" else "No paro: $mensaje")
        }
    }

    /** Dispara una foto, que se guarda tambien en la tarjeta de las gafas. */
    fun hacerFoto() {
        val device = listasParaOrdenes() ?: return
        BleeqUpCommandManager.takePhoto(device) { ok, mensaje ->
            Log.i(TAG, "takePhoto -> ok=$ok $mensaje")
            _mensajes.tryEmit(if (ok) "Foto hecha" else "No se hizo la foto: $mensaje")
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
                _camara.value = _camara.value.copy(bytesLibres = almacen.free)
            } else {
                Log.w(TAG, "getStorageInfo fallo: $mensaje")
            }
        }
    }

    /** El aparato si el canal acepta ordenes, y si no un aviso para el agente. */
    private fun listasParaOrdenes(): BleeqUpDevice? {
        val device = aparato
        if (device == null || _estado.value != GafasControlState.LISTO) {
            _mensajes.tryEmit("Las gafas no estan conectadas")
            return null
        }
        return device
    }

    private fun olvidar() {
        aparato = null
        _estado.value = GafasControlState.DESCONECTADO
        // El estado de grabacion solo valia mientras hubiera enlace: sin canal no
        // hay forma de saber si siguen grabando, y afirmarlo seria inventarselo.
        _grabando.value = false
        _camara.value = GafasEstadoCamara()
    }
}
