package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.BleeqUpSDK

private const val TAG = "AeriaGafasPuente"

/**
 * Lo que hay que hacer ANTES de poder mandarle nada a las gafas: arrancar el SDK
 * del fabricante y entregarle el aparato ya emparejado.
 *
 * Vive aqui, y no dentro de quien lo usa, porque lo necesitan dos: la sonda de
 * depuracion [GafasApPrueba] y el canal de mando [GafasCommandRepository]. Tener
 * dos copias de un apano por reflexion seria tener dos sitios que arreglar el dia
 * que el fabricante recompile su AAR.
 */
object GafasSdkPuente {

    private var sdkIniciado = false

    /**
     * Cierto mientras la sonda de depuracion `GafasSondaEstado` tiene el canal.
     *
     * El SDK guarda un solo oyente estatico, asi que si la pantalla FALCON LENS
     * abre su GATT mientras la sonda esta midiendo, los dos se pisan y las
     * escrituras empiezan a fallar con `write characteristic error`. Con esto la
     * pantalla se aparta en vez de competir. Solo lo pone la sonda, que unicamente
     * existe en compilaciones de depuracion: en release siempre vale false.
     */
    @Volatile
    var sondaTieneElCanal = false

    /**
     * Pais que se le declara al SDK al encender el punto de acceso de las gafas.
     *
     * **No es un ajuste tecnico, es una decision del cliente que sigue sin tomarse.**
     * Las gafas solo ofrecen su AP en 5745 MHz (canal 149), que el dominio
     * regulatorio europeo no permite, y el SDK mete `Locale.getDefault().country`
     * en la trama sin exponerlo por parametro: con el telefono en `ES` el firmware
     * responde `Open WiFi failed` y no hay video que traer. Declarar otro pais lo
     * enciende, pero eso es **emitir en 5,8 GHz en Europa**.
     *
     * Por eso vive null y a la vista en vez de escondido en una constante: quien lo
     * ponga esta tomando esa decision. Hoy solo lo pone la depuracion, con
     * `--es gafas_pais US`. Ver el DEVLOG del 2026-09-09 (3).
     */
    @Volatile
    var paisParaElPuntoDeAcceso: String? = null

    /**
     * Arranca el SDK. Su `init` **no valida la clave**: pone su bandera interna a
     * cierto y responde "Certification successful" sin tocar la red. No hace falta
     * licencia de partner, pero sin esta llamada todas las ordenes responden
     * "Authentication error".
     */
    fun iniciarSdk(context: Context) {
        if (sdkIniciado) return
        sdkIniciado = true
        BleeqUpSDK.init(ContextoConPermisosDeAndroid11(context.applicationContext), CLAVE_SDK) { codigo, mensaje ->
            Log.i(TAG, "init del SDK -> codigo=$codigo mensaje=$mensaje")
        }
    }

    /**
     * Construye el [BleeqUpDevice] del aparato ya emparejado y lo mete en el
     * registro interno del SDK, que normalmente solo llena el escaneo.
     *
     * Hace falta porque **las gafas no se anuncian por BLE nunca**: comprobado con
     * escaneo propio en LOW_LATENCY y con anuncio extendido, con el audio conectado
     * y sin el. El `startScan` del SDK no puede encontrarlas, pero estan emparejadas
     * y una conexion GATT dirigida abre en 30 ms.
     *
     * Va por reflexion y no por gusto: las dos piezas que hacen falta son `internal`
     * de Kotlin (`initializeName`, con el sufijo del modulo) y un accesor sintetico
     * que genera R8 (`access$getDevices$p`). Ninguna se puede nombrar desde Kotlin.
     *
     * **Es fragil a proposito y lo dice el log**: si el fabricante recompila el AAR,
     * estos nombres pueden cambiar y esto deja de funcionar de golpe. La salida
     * limpia es pedirles un `connect(mac)` que no pase por el escaneo.
     */
    fun aparatoEmparejado(context: Context): BleeqUpDevice? {
        val mac = AppContainer.gafasRepository.macElegida()?.uppercase()
        if (mac == null) {
            Log.e(TAG, "Todavia no se han elegido las gafas en la pantalla FalconOne")
            return null
        }
        val bt = context.getSystemService(BluetoothManager::class.java)
        val remoto = runCatching { bt.adapter.getRemoteDevice(mac) }.getOrNull()
        if (remoto == null) {
            Log.e(TAG, "No hay aparato emparejado con la MAC configurada")
            return null
        }
        return try {
            // El constructor tambien es internal; en el .class es publico.
            val aparato = BleeqUpDevice::class.java.getDeclaredConstructor(
                android.bluetooth.BluetoothDevice::class.java,
                android.bluetooth.BluetoothGatt::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).apply { isAccessible = true }.newInstance(remoto, null, 0, "")
            BleeqUpDevice::class.java
                .getMethod("initializeName\$bleequplibrary_release", Context::class.java)
                .invoke(aparato, context)
            val accesor = BleeqUpDeviceManager::class.java
                .getDeclaredMethod("access\$getDevices\$p")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val registro = accesor.invoke(null) as MutableMap<String, BleeqUpDevice>
            registro[aparato.address] = aparato
            Log.i(TAG, "registrado '${aparato.name}' bond=${aparato.bondState} sin escanear")
            aparato
        } catch (e: Exception) {
            // Si esto salta, el AAR cambio de nombres internos. No se disimula.
            Log.e(TAG, "El puente por reflexion con el SDK ya no vale: ${e.message}", e)
            null
        }
    }

    /**
     * Context para el SDK que arregla un fallo suyo en Android 11 o anterior.
     *
     * Antes de conectar, reconectar, desconectar o tocar el WiFi, el SDK hace
     * `checkSelfPermission(BLUETOOTH_CONNECT)` y, si no esta concedido, responde
     * "no conectado" **sin llegar a abrir el GATT** (visto desensamblando
     * `BleeqUpDeviceManager.connect`). Pero `BLUETOOTH_CONNECT` no existe hasta
     * Android 12: en Android 11 esa comprobacion devuelve DENIED siempre. Medido el
     * 2026-09-15 en el Redmi Note 8 Pro (API 30): "The FalconOne is not responding" al
     * instante, con cero clientes GATT en el sistema. En el Samsung (API 35) funciona.
     *
     * En esas versiones el permiso que de verdad protege el Bluetooth es `BLUETOOTH`
     * (y `BLUETOOTH_ADMIN` para buscar), que se conceden al instalar. Aqui se
     * contesta por ellos y por nada mas. Desde Android 12 no se toca nada.
     * La salida limpia es que el fabricante compruebe `SDK_INT` en su AAR.
     */
    private class ContextoConPermisosDeAndroid11(base: Context) : ContextWrapper(base) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val equivalente = when (permission) {
                    Manifest.permission.BLUETOOTH_CONNECT -> Manifest.permission.BLUETOOTH
                    Manifest.permission.BLUETOOTH_SCAN -> Manifest.permission.BLUETOOTH_ADMIN
                    else -> null
                }
                if (equivalente != null) return super.checkPermission(equivalente, pid, uid)
            }
            return super.checkPermission(permission, pid, uid)
        }
    }

    /** El SDK la exige pero no la mira; cualquier cadena no vacia le vale. */
    private const val CLAVE_SDK = "aeria-nexus"

    /** Lo que tarda un GATT lento en un sitio con ruido. */
    const val ESPERA_CONEXION_MS = 15_000L
}
