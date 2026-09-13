package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpDevice
import com.bleequp.bleequplibrary.BleeqUpDeviceManager
import com.bleequp.bleequplibrary.BleeqUpWifiManager
import com.delta.aeria_nexus_prototype.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AeriaGafasAP"

/**
 * Prueba minima de una sola cosa: **encender el punto de acceso de las gafas**.
 *
 * Es lo unico que faltaba para que [GafasMediaRepository] tuviera algo a lo que
 * unirse. El AP no existe hasta que alguien manda la orden por el GATT
 * propietario de las gafas, y esa orden la manda el SDK del fabricante
 * (`app/libs/bleequplibrary-release.aar`), no nosotros.
 *
 * ## Que hace, en orden
 *
 * 1. Inicializa el SDK. Su `init` **no valida la clave**: pone su bandera interna
 *    a cierto y responde "Certification successful" sin tocar la red. No hace
 *    falta licencia de partner para que funcione, pero todo lo demas del SDK
 *    devuelve "Authentication error" si no se llama antes.
 * 2. Le entrega al SDK el aparato ya emparejado, sin escanear (ver
 *    [GafasSdkPuente]): estas gafas no se anuncian por BLE nunca.
 * 3. Conecta su GATT propio (servicio `9B005FFE-…`) y espera a `onReady`, que es
 *    cuando el SDK ya tiene MTU y notificaciones. **`turnOnWifi` exige READY**:
 *    con el aparato solo emparejado por audio responde "the device is not ready".
 * 4. Manda `turnOnWifi` y **escribe en el log el SSID y la contrasena**.
 *
 * ## Lo que esta prueba responde
 *
 * Si sale `AP ENCENDIDO`, el SSID que devuelve es el nombre BLE del aparato y la
 * contrasena es la que le hemos pasado: ese es el par que le faltaba a
 * `GafasMediaRepository.conectar()`. Si sale cualquier otra cosa, lo dice el
 * mensaje del propio SDK y no hay que adivinarlo.
 *
 * ## Lo que NO hace
 *
 * No une el telefono a esa red —el SDK no toca WiFi, eso sigue siendo nuestro— ni
 * lista ni descarga nada. Es una sonda de debug y desaparece del release: solo se
 * llama desde `MainActivity` bajo `BuildConfig.DEBUG`.
 */
// Sonda de depuracion: llama a las APIs de Bluetooth sin comprobar permiso
// porque para cuando se lanza ya se ha concedido en Operations. Si faltara,
// el SecurityException sale en el log, que es donde se mira esta sonda.
@android.annotation.SuppressLint("MissingPermission")
object GafasApPrueba {

    /** Para no arrancar dos veces el SDK ni dos escaneos a la vez. */
    private var enMarcha = false

    /**
     * Si se pone, tras encender el AP el telefono **se une a el** con el mismo
     * SSID y clave que devuelven las gafas, usando el [GafasMediaRepository] de
     * verdad. Va encadenado a proposito: el AP se apaga solo a los pocos minutos
     * si nadie se conecta, asi que unirse "luego" no es una opcion.
     */
    var alcance: kotlinx.coroutines.CoroutineScope? = null

    /**
     * Enciende el AP de las gafas y deja el SSID y la clave en logcat.
     *
     * [password] tiene que ser de **exactamente 8 letras o digitos**: el SDK la
     * valida contra `^[A-Za-z0-9]{8}$` y la rechaza antes de mandar nada.
     */
    fun encender(context: Context, password: String) {
        if (enMarcha) {
            Log.w(TAG, "Ya hay una prueba en curso; se ignora esta")
            return
        }
        if (password != SOLO_ESTADO && !password.matches(Regex("^[A-Za-z0-9]{8}$"))) {
            Log.e(TAG, "Contrasena '$password' invalida: exactamente 8 letras o digitos")
            return
        }
        enMarcha = true

        Log.i(TAG, "1/4 init del SDK (la clave no se valida: la pone a cierto sin mirarla)")
        GafasSdkPuente.iniciarSdk(context)

        // Las gafas NO se anuncian por BLE, asi que el aparato se le entrega hecho
        // en vez de dejar que el SDK lo busque. Ver GafasSdkPuente.
        val aparato = GafasSdkPuente.aparatoEmparejado(context)
        if (aparato == null) {
            enMarcha = false
            return
        }

        BleeqUpDeviceManager.registerCallback(object : BleeqUpDeviceManager.BluetoothListener {
            override fun onConnected(device: BleeqUpDevice) {
                Log.i(TAG, "    GATT conectado con ${device.name}; esperando READY")
            }

            override fun onReady(device: BleeqUpDevice) {
                Log.i(TAG, "3/4 READY: ${device.name} (${device.address})")
                if (password == SOLO_ESTADO) preguntarEstado(device) else encenderAp(device, password)
            }

            override fun onDisconnected(device: BleeqUpDevice) {
                Log.w(TAG, "    GATT desconectado de ${device.name}")
                enMarcha = false
            }

            override fun onError(device: BleeqUpDevice, code: Int, message: String) {
                Log.e(TAG, "    ERROR del SDK en ${device.name} codigo=$code: $message")
                enMarcha = false
            }

            override fun onBondStateChanged(device: android.bluetooth.BluetoothDevice, state: Int) {
                Log.d(TAG, "    emparejamiento: estado=$state")
            }
        })

        Log.i(TAG, "2/4 conectando GATT con ${aparato.name} (${aparato.address}), sin escanear")
        BleeqUpDeviceManager.connect(aparato, ESPERA_MS) { ok ->
            // Aqui solo se sabe si el GATT se abrio. La orden se manda en
            // onReady, no aqui: CONNECTED todavia no acepta comandos.
            Log.i(TAG, "    connect -> $ok")
            if (!ok) enMarcha = false
        }
    }

    /**
     * Pregunta a las gafas por su estado sin cambiarles nada.
     *
     * Existe porque `turnOnWifi` responde "Open WiFi failed" —o sea, las gafas
     * contestan y **se niegan**— y el SDK no dice por que. Lo que se busca aqui:
     * si el AP ya estaba encendido, si hay alguien conectado a el, y si el resto
     * de ordenes funcionan (para separar "este comando falla" de "el aparato no
     * atiende").
     */
    private fun preguntarEstado(device: BleeqUpDevice) {
        BleeqUpWifiManager.getWifiSwitchStatus(device) { ok, encendido, mensaje ->
            Log.i(TAG, "  wifiSwitch  ok=$ok encendido=$encendido msg=$mensaje")
        }
        BleeqUpWifiManager.getWifiConnectionStatus(device) { ok, conectado, mensaje ->
            Log.i(TAG, "  wifiConn    ok=$ok conectado=$conectado msg=$mensaje")
        }
        com.bleequp.bleequplibrary.BleeqUpCommandManager.getStorageInfo(device) { ok, almacen, mensaje ->
            Log.i(TAG, "  storage     ok=$ok $almacen msg=$mensaje")
        }
        com.bleequp.bleequplibrary.BleeqUpCommandManager.getCameraSetting(device) { ok, res, mensaje ->
            Log.i(TAG, "  camara      ok=$ok $res msg=$mensaje")
        }
    }

    /**
     * Pais que se le manda a las gafas en la orden de encender el AP.
     *
     * El SDK no lo pide por parametro: lo saca de `Locale.getDefault().getCountry()`
     * y lo mete en la trama en 3 bytes. Es el dominio regulatorio del WiFi, asi
     * que un valor que el firmware no reconozca es un candidato serio a que
     * conteste "Open WiFi failed". Cambiarlo aqui cambia lo que lee el SDK.
     */
    var paisForzado: String? = null

    /**
     * Suelta el GATT al terminar.
     *
     * Sin esto, cada pasada de la sonda deja un `BluetoothGatt` abierto que nadie
     * cierra: Android los cachea, la siguiente conexion sale "instantanea"
     * reusando el enlace viejo y las escrituras empiezan a fallar con
     * `write characteristic error`. Costo tres ejecuciones averiguarlo.
     */
    private fun soltar(device: BleeqUpDevice) {
        runCatching { BleeqUpDeviceManager.disconnect(device) }
            .onFailure { Log.w(TAG, "No se pudo soltar el GATT: ${it.message}") }
    }

    private fun encenderAp(device: BleeqUpDevice, password: String) {
        val pais = paisForzado
        if (pais != null) {
            java.util.Locale.setDefault(java.util.Locale.Builder().setLanguage("en").setRegion(pais).build())
        }
        Log.i(
            TAG,
            "4/4 turnOnWifi clave='$password' pais='${java.util.Locale.getDefault().country}' " +
                "ssid_que_mandara='${device.name}'",
        )
        BleeqUpWifiManager.turnOnWifi(device, password) { ok, wifi, mensaje ->
            if (ok) {
                // El SSID sale del nombre BLE del aparato y la clave es la que
                // acabamos de mandar: este par es exactamente lo que necesita
                // WifiNetworkSpecifier en GafasMediaRepository.conectar().
                Log.w(TAG, "AP ENCENDIDO  SSID='${wifi.name}'  CLAVE='${wifi.password}'")
                unirse(device, wifi.name, wifi.password)
            } else {
                Log.e(TAG, "AP NO ENCENDIDO: $mensaje")
                soltar(device)
            }
            enMarcha = false
        }
    }

    /**
     * Escaneo BLE crudo, sin el SDK, para una sola pregunta: **¿anuncian las
     * gafas?**
     *
     * El escaneo del SDK no las encontro con ellas puestas y enlazadas por audio
     * (A2DP + HFP activos, pero `LE:N` en el dumpsys). Hay dos explicaciones y
     * hacen falta cosas distintas:
     *
     * - Si aqui aparecen, el problema es el escaneo del SDK: usa `ScanSettings`
     *   por defecto, o sea SCAN_MODE_LOW_POWER, que solo escucha medio segundo
     *   cada cinco. Este usa LOW_LATENCY, que escucha en continuo.
     * - Si no aparecen, es que dejan de anunciarse mientras estan conectadas por
     *   Bluetooth clasico, y entonces el camino es otro: soltar el audio antes,
     *   o construir el BleeqUpDevice desde el aparato ya emparejado.
     *
     * Registra **todo** lo que ve, no solo lo que parezca de BleeqUp: un nombre
     * distinto del esperado tambien es una respuesta.
     */
    fun escanearCrudo(context: Context) {
        val bt = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val escaner = bt?.adapter?.bluetoothLeScanner
        if (escaner == null) {
            Log.e(TAG, "Sin escaner BLE: ¿Bluetooth apagado?")
            return
        }
        val vistos = mutableSetOf<String>()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(tipo: Int, r: android.bluetooth.le.ScanResult) {
                val mac = r.device.address
                if (!vistos.add(mac)) return
                val nombre = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
                Log.i(TAG, "  anuncio: $mac  nombre='${nombre ?: "?"}'  rssi=${r.rssi}")
            }

            override fun onScanFailed(code: Int) {
                Log.e(TAG, "  escaneo crudo fallido: codigo=$code")
            }
        }
        // setLegacy(false) NO es un detalle: por defecto Android entrega solo
        // anuncios legacy y se calla los extendidos de Bluetooth 5. Unas gafas
        // que anuncien en extendido son invisibles para un escaneo por defecto,
        // que es justo lo que hace el SDK del fabricante.
        val ajustes = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setLegacy(false)
                    setPhy(android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        Log.i(TAG, "ESCANEO CRUDO ${ESPERA_MS}ms LOW_LATENCY, sin filtros, legacy=false")
        escaner.startScan(null, ajustes, callback)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { escaner.stopScan(callback) }
            Log.i(TAG, "ESCANEO CRUDO terminado: ${vistos.size} aparatos distintos")
        }, ESPERA_MS)
    }

    /**
     * Conexion GATT **directa** al aparato ya emparejado, sin escanear, y volcado
     * de todos sus servicios y caracteristicas.
     *
     * Responde a la pregunta que bloquea todo lo demas: *¿tiene este aparato el
     * servicio que el SDK espera?* El SDK busca `9B005FFE-1DEA-2D9C-B841-
     * 9FD52E4C8B3A`, pero el `dumpsys` de estas gafas anuncia
     * `66666666-6666-6666-6666-666666666666` por BR/EDR. Si el servicio del SDK
     * no esta, el AAR es de otro modelo o de otro firmware y no hay ajuste que
     * lo arregle.
     *
     * De paso resuelve lo otro: `connectGatt` con `autoConnect=false` es una
     * conexion dirigida y **no necesita que el aparato se anuncie**, que es
     * justo donde se atasca el escaneo del SDK.
     */
    fun volcarGatt(context: Context) {
        val mac = BuildConfig.GAFAS_MAC
        if (mac.isEmpty()) {
            Log.e(TAG, "GAFAS_MAC vacia en local.properties")
            return
        }
        val bt = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val remoto = runCatching { bt.adapter.getRemoteDevice(mac) }.getOrNull()
        if (remoto == null) {
            Log.e(TAG, "No hay aparato con MAC $mac")
            return
        }
        Log.i(TAG, "GATT DIRECTO a $mac (bond=${remoto.bondState}, tipo=${remoto.type})")
        val callback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, nuevo: Int) {
                Log.i(TAG, "  conexion: status=$status estado=$nuevo")
                if (nuevo == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "  conectado; descubriendo servicios")
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                Log.i(TAG, "  servicios descubiertos status=$status, ${gatt.services.size} servicios")
                gatt.services.forEach { s ->
                    Log.i(TAG, "  SERVICIO ${s.uuid}")
                    s.characteristics.forEach { c ->
                        Log.i(TAG, "      caract ${c.uuid} props=0x${Integer.toHexString(c.properties)}")
                    }
                }
                gatt.disconnect()
                gatt.close()
            }
        }
        remoto.connectGatt(
            context,
            false,
            callback,
            android.bluetooth.BluetoothDevice.TRANSPORT_LE,
        )
    }

    /**
     * Se une al AP **sin tocar el BLE**, con un SSID y una clave que ya se saben.
     *
     * Aisla la mitad WiFi del problema: el AP se queda encendido un rato despues
     * de la orden, asi que si ya esta emitiendo no hace falta volver a pedirlo, y
     * un fallo aqui es del WiFi y no del canal de control.
     */
    fun soloUnirse(scope: kotlinx.coroutines.CoroutineScope, ssid: String, clave: String) {
        alcance = scope
        scope.launch {
            Log.i(TAG, "UNION DIRECTA a '$ssid' (sin BLE)")
            val unido = AppContainer.gafasMediaRepository.conectar(ssid, clave)
            if (!unido) {
                Log.e(TAG, "NO SE PUDO UNIR a '$ssid'")
                return@launch
            }
            val servidor = AppContainer.gafasMediaRepository.pasarela
            Log.w(TAG, "UNIDO a '$ssid'  servidor=$servidor")
            if (servidor != null) pedirListado(servidor)
        }
    }

    /**
     * Une el telefono al AP recien encendido y dice **cual es la IP del servidor**.
     *
     * Sale un dialogo del sistema que hay que aceptar a mano: `WifiNetworkSpecifier`
     * exige app en primer plano y confirmacion del usuario, y eso no se puede
     * evitar. Por eso esto nunca podra ser una sincronizacion de fondo.
     *
     * Ademas pregunta a las gafas si ven al cliente conectado: que Android diga
     * que se unio y que las gafas digan que no hay nadie serian dos cosas
     * distintas, y conviene enterarse aqui y no al descargar.
     */
    private fun unirse(device: BleeqUpDevice, ssid: String, clave: String) {
        val scope = alcance
        if (scope == null) {
            Log.i(TAG, "Sin alcance: no se intenta la union (solo se pedia encender)")
            return
        }
        scope.launch {
            Log.i(TAG, "5/5 uniendose a '$ssid' (sale dialogo del sistema: hay que aceptarlo)")
            val unido = AppContainer.gafasMediaRepository.conectar(ssid, clave)
            if (!unido) {
                Log.e(TAG, "NO SE PUDO UNIR a '$ssid'")
                enMarcha = false
                return@launch
            }
            val servidor = AppContainer.gafasMediaRepository.pasarela
            Log.w(TAG, "UNIDO a '$ssid'  servidor=$servidor")
            if (servidor != null) pedirListado(servidor)
            soltar(device)
            enMarcha = false
        }
    }

    /**
     * Pide el listado al servidor de las gafas y **vuelca la respuesta en crudo**.
     *
     * El endpoint (`/list?fileType=`) salio de leer el AAR, pero los valores que
     * acepta `fileType` no estan en ninguna parte: el SDK se limita a pasar por
     * parametro lo que le den. Por eso aqui se prueban varios y se imprime lo que
     * conteste cada uno, sin parsear. Con la respuesta real delante se escribe
     * `GafasMediaRepository.listar()` **una vez y bien**, en lugar de adivinar la
     * forma del JSON.
     */
    private suspend fun pedirListado(servidor: String) = withContext(Dispatchers.IO) {
        for (tipo in listOf("video", "photo", "image", "all", "")) {
            val url = java.net.URL("https://$servidor/list?fileType=$tipo")
            val conexion = AppContainer.gafasMediaRepository.abrirSeguro(url)
            if (conexion == null) {
                Log.e(TAG, "  list[$tipo] no se pudo abrir")
                continue
            }
            try {
                val codigo = conexion.responseCode
                val cuerpo = runCatching {
                    (if (codigo in 200..299) conexion.inputStream else conexion.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                }.getOrElse { "<sin cuerpo: ${it.message}>" }
                Log.w(TAG, "  list[$tipo] HTTP $codigo  ${cuerpo.take(600)}")
            } catch (e: Exception) {
                Log.e(TAG, "  list[$tipo] fallo: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                conexion.disconnect()
            }
        }
    }

    /** Apaga el AP. Las gafas lo dejan encendido hasta que se les diga. */
    fun apagar(device: BleeqUpDevice) {
        BleeqUpWifiManager.turnOffWifi(device) { ok, mensaje ->
            Log.i(TAG, "turnOffWifi -> $ok: $mensaje")
        }
    }

    /** Escaneo y conexion. 15 s es lo que tarda un GATT lento en un sitio con ruido. */
    private const val ESPERA_MS = 15_000L

    /** Clave reservada: en vez de encender el AP, solo pregunta el estado. */
    const val SOLO_ESTADO = "estado"
}
