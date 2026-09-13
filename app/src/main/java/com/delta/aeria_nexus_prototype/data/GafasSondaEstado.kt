package com.delta.aeria_nexus_prototype.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpCommandManager
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AeriaSondaEstado"

/**
 * Sonda que responde a una sola pregunta: **cuanto puede saber el telefono de lo
 * que hacen las gafas por su cuenta.**
 *
 * Hace falta porque hoy [GafasCommandRepository.grabando] es solo la ultima orden
 * que dimos nosotros. Si el agente pulsa el boton fisico de las gafas, la pantalla
 * miente. El SDK no tiene ninguna orden que pregunte "estas grabando", pero al
 * desmontar el AAR aparecieron tres avisos que el aparato empuja solo y que no
 * estabamos escuchando. Esta sonda los engancha todos a la vez y los vuelca al
 * log para ver cuales llegan de verdad.
 *
 * ## Lo que engancha
 *
 * 1. **Botones fisicos** ([BleeqUpCommandManager.registerButtonCallback], publico):
 *    pulsacion corta, doble y larga, del boton izquierdo y del derecho.
 * 2. **Avisos de camara** ([BleeqUpCommandManager.registerCameraCallback], publico):
 *    se emiten por CUALQUIER captura, no solo por las que pedimos. Interesa el
 *    texto crudo: el parser del SDK distingue `appcapture`, `handcapture` y
 *    `aicapture`, asi que ahi deberia verse quien disparo la grabacion.
 * 3. **Bateria y carga** ([BleeqUpCommandManager.registerPowerCallback], publico).
 * 4. **Estado interno** (`registerStateCallback`, opcode 0x10104): un booleano que
 *    el aparato empuja. Es interno del SDK y su significado **no esta documentado
 *    ni verificado**; se lee por reflexion para averiguar que significa.
 * 5. **Botones de la bodycam**: se escucha [BodycamRepository.buttonEvents], que
 *    hoy no consume nadie, para ver llegar `BTN_REC_START` desde la W1.
 *
 * ## Quien tiene el canal
 *
 * El SDK guarda **un solo** oyente de conexion en una variable estatica, asi que
 * esta sonda y [GafasCommandRepository] no pueden tenerlo a la vez. Mientras la
 * sonda mide levanta [GafasSdkPuente.sondaTieneElCanal], y con eso la pantalla
 * FALCON LENS se aparta en vez de competir: sin esa bandera, entrar en la pantalla
 * a media medicion rompia las dos partes con `write characteristic error`.
 *
 * Es una sonda de depuracion: solo se llama desde `MainActivity` bajo
 * `BuildConfig.DEBUG`, igual que [GafasApPrueba].
 */
object GafasSondaEstado {

    private var enMarcha = false
    private var aparato: BleeqUpDevice? = null

    /**
     * Abre el canal y engancha todos los avisos.
     *
     * Con [conCiclo] la sonda ademas graba y para ella sola, con esperas, para ver
     * que avisos acompanan a una grabacion que SI hemos pedido nosotros. Sin el,
     * se queda escuchando y es el agente quien pulsa el boton fisico: eso es lo
     * que de verdad queremos observar.
     */
    fun arrancar(context: Context, alcance: CoroutineScope, conCiclo: Boolean) {
        if (enMarcha) {
            Log.w(TAG, "la sonda ya esta en marcha")
            return
        }
        enMarcha = true
        GafasSdkPuente.sondaTieneElCanal = true
        vigilarBotonesDeLaBodycam(alcance)

        // El SDK guarda un solo oyente estatico, asi que si la pantalla FALCON LENS
        // dejo el canal abierto hay que quitarselo antes: si no, los dos GATT se
        // pisan y las escrituras empiezan a fallar con "write characteristic error".
        AppContainer.gafasCommandRepository.desconectar()

        GafasSdkPuente.iniciarSdk(context)
        val emparejado = GafasSdkPuente.aparatoEmparejado(context)
        if (emparejado == null) {
            Log.e(TAG, "no se encuentra el aparato emparejado; se aborta")
            soltarElCanal()
            return
        }
        BleeqUpDeviceManager.registerCallback(oyenteDeConexion(alcance, conCiclo))
        BleeqUpDeviceManager.connect(emparejado, GafasSdkPuente.ESPERA_CONEXION_MS) { abierto ->
            if (!abierto) {
                Log.e(TAG, "el GATT no se abrio")
                soltarElCanal()
            }
        }
    }

    /**
     * Suelta el canal. Sin esto la siguiente conexion reusa un GATT cacheado.
     *
     * La bandera se baja SIEMPRE, tambien si ya no habia aparato: si se saliera
     * antes, [GafasSdkPuente.sondaTieneElCanal] se quedaria puesta para siempre y
     * la pantalla FALCON LENS no volveria a conectar en toda la vida del proceso.
     */
    fun parar() {
        val device = aparato
        if (device == null) {
            soltarElCanal()
            return
        }
        runCatching { BleeqUpCommandManager.unregisterCameraCallback(device) }
        runCatching { BleeqUpCommandManager.unregisterButtonCallback(device) }
        runCatching { BleeqUpCommandManager.unregisterPowerCallback(device) }
        runCatching { olvidarEstadoInterno(device) }
        runCatching { BleeqUpDeviceManager.disconnect(device) }
        aparato = null
        soltarElCanal()
        Log.i(TAG, "sonda parada")
    }

    /** Devuelve el canal a la pantalla FALCON LENS, que estaba apartada. */
    private fun soltarElCanal() {
        enMarcha = false
        GafasSdkPuente.sondaTieneElCanal = false
    }

    private fun oyenteDeConexion(
        alcance: CoroutineScope,
        conCiclo: Boolean,
    ) = object : BleeqUpDeviceManager.BluetoothListener {
        override fun onConnected(device: BleeqUpDevice) {
            Log.i(TAG, "GATT abierto; esperando READY")
        }

        override fun onReady(device: BleeqUpDevice) {
            // CONNECTED todavia no acepta ordenes ni registros: hasta READY el SDK
            // no tiene MTU ni notificaciones, y todo responde "device is not ready".
            aparato = device
            Log.i(TAG, "READY: se enganchan los avisos")
            escucharBotonesDeLasGafas(device)
            escucharAvisosDeCamara(device)
            escucharBateria(device)
            escucharEstadoInterno(device)
            preguntarAlmacenamiento(device)
            if (conCiclo) grabarYPararParaVerLosAvisos(device, alcance)
        }

        override fun onDisconnected(device: BleeqUpDevice) {
            Log.i(TAG, "GATT cerrado")
            aparato = null
            soltarElCanal()
        }

        override fun onError(device: BleeqUpDevice, code: Int, message: String) {
            Log.e(TAG, "error del SDK codigo=$code: $message")
        }

        override fun onBondStateChanged(device: BluetoothDevice, state: Int) = Unit
    }

    private fun escucharBotonesDeLasGafas(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerButtonCallback(
            device,
            object : BleeqUpCommandManager.ButtonListener {
                override fun onLeftButtonEvent(evento: KeyEvent) {
                    Log.i(TAG, "BOTON GAFAS izquierdo: $evento")
                }

                override fun onRightButtonEvent(evento: KeyEvent) {
                    Log.i(TAG, "BOTON GAFAS derecho: $evento")
                }
            },
        )
        Log.i(TAG, "enganchados los botones fisicos de las gafas")
    }

    /**
     * El texto llega crudo a proposito: dentro deberia venir `appcapture`,
     * `handcapture` o `aicapture`, que es lo que diria quien disparo la captura.
     */
    private fun escucharAvisosDeCamara(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerCameraCallback(
            device,
            object : BleeqUpCommandManager.CameraListener {
                override fun onVideoInfo(info: String) {
                    Log.i(TAG, "AVISO DE VIDEO: $info")
                }

                override fun onPhotoInfo(info: String) {
                    Log.i(TAG, "AVISO DE FOTO: $info")
                }
            },
        )
        Log.i(TAG, "enganchados los avisos de camara")
    }

    private fun escucharBateria(device: BleeqUpDevice) {
        BleeqUpCommandManager.registerPowerCallback(
            device,
            object : BleeqUpCommandManager.PowerListener {
                override fun onChargingStatus(cargando: Boolean) {
                    Log.i(TAG, "GAFAS cargando: $cargando")
                }

                override fun onBatteryLevel(nivel: Int) {
                    Log.i(TAG, "GAFAS bateria: $nivel%")
                }
            },
        )
        Log.i(TAG, "enganchada la bateria de las gafas")
    }

    /**
     * Engancha por reflexion el callback de estado interno del SDK.
     *
     * Es `internal` de Kotlin, asi que no se puede nombrar desde aqui, pero en el
     * .class es publico. Registra el opcode 0x10104 y entrega un booleano. **Que
     * significa ese booleano es justo lo que esta sonda viene a averiguar**, asi
     * que se vuelca tal cual junto a lo que este pasando alrededor.
     */
    private fun escucharEstadoInterno(device: BleeqUpDevice) {
        try {
            val tipoFuncion = Class.forName("kotlin.jvm.functions.Function1")
            val metodo = BleeqUpDevice::class.java
                .getMethod("registerStateCallback\$bleequplibrary_release", tipoFuncion)
            val oyente: (Boolean) -> Unit = { valor ->
                Log.i(TAG, "ESTADO INTERNO (opcode 0x10104) -> $valor")
            }
            metodo.invoke(device, oyente)
            Log.i(TAG, "enganchado el estado interno por reflexion")
        } catch (e: Exception) {
            // Si esto salta, el AAR cambio de nombres internos. No se disimula.
            Log.e(TAG, "no se pudo enganchar el estado interno: ${e.message}", e)
        }
    }

    private fun olvidarEstadoInterno(device: BleeqUpDevice) {
        BleeqUpDevice::class.java
            .getMethod("unRegisterSomState\$bleequplibrary_release")
            .invoke(device)
    }

    /**
     * Lee el almacenamiento entero, no solo los bytes libres.
     *
     * [GafasCommandRepository] solo mira `free` y la pantalla dice "0.0 GB libres"
     * en una tarjeta que acaba de escribir un mp4. Si `whole` tambien sale 0, el
     * problema es la lectura y no la tarjeta, y no hay nada que borrar.
     */
    private fun preguntarAlmacenamiento(device: BleeqUpDevice) {
        BleeqUpCommandManager.getStorageInfo(device) { ok, almacen, mensaje ->
            if (ok) {
                Log.i(TAG, "ALMACEN total=${almacen.whole} bytes libres=${almacen.free} bytes")
            } else {
                Log.w(TAG, "getStorageInfo fallo: $mensaje")
            }
        }
        BleeqUpCommandManager.getCameraSetting(device) { ok, resolucion, mensaje ->
            if (ok) {
                Log.i(TAG, "RESOLUCION ${resolucion.width}x${resolucion.height}")
            } else {
                Log.w(TAG, "getCameraSetting fallo: $mensaje")
            }
        }
    }

    /**
     * Graba unos segundos y para, para comparar los avisos de una grabacion pedida
     * por la app con los de una pedida a mano con el boton de las gafas.
     */
    private fun grabarYPararParaVerLosAvisos(device: BleeqUpDevice, alcance: CoroutineScope) {
        alcance.launch {
            delay(ESPERA_ANTES_DE_GRABAR_MILLIS)
            Log.i(TAG, "--- pidiendo startRecord desde la app ---")
            BleeqUpCommandManager.startRecord(device) { ok, mensaje ->
                Log.i(TAG, "startRecord -> ok=$ok mensaje='$mensaje'")
            }
            delay(DURACION_DE_LA_PRUEBA_MILLIS)
            Log.i(TAG, "--- pidiendo stopRecord desde la app ---")
            BleeqUpCommandManager.stopRecord(device) { ok, mensaje ->
                Log.i(TAG, "stopRecord -> ok=$ok mensaje='$mensaje'")
            }
        }
    }

    /**
     * `buttonEvents` no lo consume nadie en la app: es el cable que hoy esta
     * tendido y sin enchufar entre el boton fisico de la W1 y las gafas.
     */
    private fun vigilarBotonesDeLaBodycam(alcance: CoroutineScope) {
        alcance.launch {
            AppContainer.bodycamRepository.buttonEvents.collect { linea ->
                Log.i(TAG, "BODYCAM -> $linea")
            }
        }
    }

    /** Da tiempo a leer en el log que los avisos quedaron enganchados. */
    private const val ESPERA_ANTES_DE_GRABAR_MILLIS = 5_000L

    /** Suficiente para que las gafas cierren un fichero con contenido. */
    private const val DURACION_DE_LA_PRUEBA_MILLIS = 8_000L
}
