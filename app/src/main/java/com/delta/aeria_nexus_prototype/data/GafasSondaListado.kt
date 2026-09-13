package com.delta.aeria_nexus_prototype.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.BleeqUpWifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AeriaSondaListado"

/**
 * Lista por WiFi lo que hay en la tarjeta de las gafas, con TODOS los campos.
 *
 * Viene a resolver una cosa concreta que se repitio 3 de 3 veces al parar una
 * grabacion: `stopRecord` devuelve un nombre de fichero y el aviso de camara
 * anuncia **otro distinto** unos 0,8 s despues. O son dos ficheros, o el firmware
 * nombra dos veces lo mismo, y eso cambia lo que hay que descargar y entregar al
 * backend. El listado por WiFi es lo unico que lo puede zanjar.
 *
 * De paso deja ver el `fileType`, que es donde el SDK mete el origen de la captura
 * (`appcapture` / `handcapture` / `aicapture`). Ojo: leyendo el AAR, esos tres
 * valores **son tipos de FOTO** y el mapeo publico del SDK los colapsa todos a
 * `image`, asi que para video no se espera marca de origen.
 *
 * ## Por que ata el proceso entero a la red de las gafas
 *
 * El SDK hace sus peticiones con su propio OkHttp y no sabe nada de la red del AP,
 * asi que sin `bindProcessToNetwork` saldrian por los datos moviles y no llegarian
 * a ningun sitio. La app de verdad **no hace esto** —ataria tambien a Agora, las
 * subidas y el mapa a una red sin internet—, por eso vive aqui y se deshace en
 * cuanto termina.
 *
 * ## Como se lanza
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es gafas_listar Aeria123 --es gafas_pais US
 *
 * La clave son exactamente 8 letras o digitos. El pais importa: con codigo europeo
 * el AP se enciende en el canal 149 y el telefono no puede unirse.
 */
object GafasSondaListado {

    private var enMarcha = false

    fun arrancar(context: Context, alcance: CoroutineScope, clave: String, pais: String?) {
        if (enMarcha) {
            Log.w(TAG, "ya hay un listado en curso")
            return
        }
        if (!clave.matches(Regex("^[A-Za-z0-9]{8}$"))) {
            Log.e(TAG, "clave '$clave' invalida: exactamente 8 letras o digitos")
            return
        }
        enMarcha = true
        GafasSdkPuente.sondaTieneElCanal = true
        // Se para tambien el bucle de reintento, no solo el canal: si no, seguiria
        // llamando a conectar() cada pocos segundos contra la bandera de arriba.
        AppContainer.gafasCommandRepository.soltarCanal()

        if (pais != null) {
            java.util.Locale.setDefault(
                java.util.Locale.Builder().setLanguage("en").setRegion(pais).build(),
            )
        }
        Log.i(TAG, "pais en uso: '${java.util.Locale.getDefault().country}'")
        volcarConstantesDeTipo()

        GafasSdkPuente.iniciarSdk(context)
        val emparejado = GafasSdkPuente.aparatoEmparejado(context)
        if (emparejado == null) {
            terminar()
            return
        }
        BleeqUpDeviceManager.registerCallback(oyente(context, alcance, clave))
        BleeqUpDeviceManager.connect(emparejado, GafasSdkPuente.ESPERA_CONEXION_MS) { abierto ->
            if (!abierto) {
                Log.e(TAG, "el GATT no se abrio")
                terminar()
            }
        }
    }

    /**
     * Los tres tipos validos para `getFileList` son constantes privadas del SDK sin
     * inicializador visible en el bytecode, asi que su valor solo se sabe en
     * caliente. Se leen por reflexion y se escriben en el log.
     */
    private fun volcarConstantesDeTipo() {
        runCatching {
            val clase = Class.forName("com.bleequp.bleequplibrary.BleeqUpCommandResultKt")
            for (nombre in listOf("getFileTypeAll", "getFileTypeVideo", "getFileTypeImage")) {
                val valor = clase.getMethod(nombre).invoke(null)
                Log.i(TAG, "$nombre = '$valor'")
            }
        }.onFailure { Log.e(TAG, "no se pudieron leer los tipos: ${it.message}") }
    }

    private fun tipoTodos(): String = runCatching {
        Class.forName("com.bleequp.bleequplibrary.BleeqUpCommandResultKt")
            .getMethod("getFileTypeAll")
            .invoke(null) as String
    }.getOrDefault("all")

    private fun oyente(
        context: Context,
        alcance: CoroutineScope,
        clave: String,
    ) = object : BleeqUpDeviceManager.BluetoothListener {
        override fun onConnected(device: BleeqUpDevice) = Unit

        override fun onReady(device: BleeqUpDevice) {
            Log.i(TAG, "READY: se enciende el AP")
            alcance.launch { encenderYListar(context, device, clave) }
        }

        override fun onDisconnected(device: BleeqUpDevice) {
            Log.i(TAG, "GATT cerrado")
        }

        override fun onError(device: BleeqUpDevice, code: Int, message: String) {
            Log.e(TAG, "error del SDK codigo=$code: $message")
        }

        override fun onBondStateChanged(device: BluetoothDevice, state: Int) = Unit
    }

    private suspend fun encenderYListar(context: Context, device: BleeqUpDevice, clave: String) {
        val credenciales = encenderAp(device, clave)
        if (credenciales == null) {
            terminar()
            return
        }
        val (ssid, passphrase) = credenciales
        val media = AppContainer.gafasMediaRepository
        if (!media.conectar(ssid, passphrase)) {
            Log.e(TAG, "el telefono no pudo unirse al AP '$ssid'")
            terminar()
            return
        }
        val ip = media.pasarela
        Log.i(TAG, "unido al AP; pasarela=$ip")

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val red = media.redDeLasGafas
        connectivity.bindProcessToNetwork(red)
        try {
            if (ip == null) {
                Log.e(TAG, "el AP no dio pasarela; no se puede listar")
            } else {
                listar(device, ip)
                // El callback del SDK es asincrono: hay que seguir atados a la red
                // mientras responde, o la peticion muere a medias.
                delay(ESPERA_DE_RESPUESTA_MILLIS)
            }
        } finally {
            connectivity.bindProcessToNetwork(null)
            media.desconectar()
            BleeqUpWifiManager.turnOffWifi(device) { ok, mensaje ->
                Log.i(TAG, "AP apagado ok=$ok $mensaje")
            }
            terminar()
        }
    }

    private suspend fun encenderAp(device: BleeqUpDevice, clave: String): Pair<String, String>? {
        var resultado: Pair<String, String>? = null
        var respondio = false
        BleeqUpWifiManager.turnOnWifi(device, clave) { ok, wifi, mensaje ->
            if (ok) {
                Log.i(TAG, "AP encendido ssid='${wifi.name}' clave='${wifi.password}'")
                resultado = wifi.name to wifi.password
            } else {
                Log.e(TAG, "el AP no encendio: $mensaje")
            }
            respondio = true
        }
        var esperado = 0L
        while (!respondio && esperado < ESPERA_DEL_AP_MILLIS) {
            delay(PASO_DE_ESPERA_MILLIS)
            esperado += PASO_DE_ESPERA_MILLIS
        }
        return resultado
    }

    private fun listar(device: BleeqUpDevice, ip: String) {
        val tipo = tipoTodos()
        Log.i(TAG, "getFileList ip='$ip' tipo='$tipo'")
        BleeqUpWifiManager.getFileList(device, ip, tipo) { ok, lista, mensaje ->
            if (!ok) {
                Log.e(TAG, "getFileList fallo: $mensaje")
                return@getFileList
            }
            Log.i(TAG, "===== ${lista.size} ficheros en las gafas =====")
            for (fichero in lista) {
                Log.i(
                    TAG,
                    "nombre='${fichero.fileName}' tipo='${fichero.fileType}' " +
                        "miniatura='${fichero.fileThumbName}' bytes=${fichero.fileSize} " +
                        "duracion=${fichero.fileDuration} instante=${fichero.fileTime} " +
                        "resolucion='${fichero.fileResolution}'",
                )
            }
            Log.i(TAG, "===== fin del listado =====")
        }
    }

    private fun terminar() {
        enMarcha = false
        GafasSdkPuente.sondaTieneElCanal = false
        // Se devuelve el canal al rele del SOS, que es quien lo necesita de verdad.
        AppContainer.gafasCommandRepository.mantenerCanal()
    }

    /** Lo que tarda el aparato en levantar su punto de acceso. */
    private const val ESPERA_DEL_AP_MILLIS = 20_000L

    /** Margen para que el SDK conteste el listado antes de soltar la red. */
    private const val ESPERA_DE_RESPUESTA_MILLIS = 15_000L

    private const val PASO_DE_ESPERA_MILLIS = 250L
}
