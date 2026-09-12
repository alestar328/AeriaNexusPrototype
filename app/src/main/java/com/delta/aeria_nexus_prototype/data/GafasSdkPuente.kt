package com.delta.aeria_nexus_prototype.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.BleeqUpSDK
import com.delta.aeria_nexus_prototype.BuildConfig

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
     * Arranca el SDK. Su `init` **no valida la clave**: pone su bandera interna a
     * cierto y responde "Certification successful" sin tocar la red. No hace falta
     * licencia de partner, pero sin esta llamada todas las ordenes responden
     * "Authentication error".
     */
    fun iniciarSdk(context: Context) {
        if (sdkIniciado) return
        sdkIniciado = true
        BleeqUpSDK.init(context.applicationContext, CLAVE_SDK) { codigo, mensaje ->
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
        val mac = BuildConfig.GAFAS_MAC.uppercase()
        if (mac.isEmpty()) {
            Log.e(TAG, "GAFAS_MAC vacia en local.properties")
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

    /** El SDK la exige pero no la mira; cualquier cadena no vacia le vale. */
    private const val CLAVE_SDK = "aeria-nexus"

    /** Lo que tarda un GATT lento en un sitio con ruido. */
    const val ESPERA_CONEXION_MS = 15_000L
}
