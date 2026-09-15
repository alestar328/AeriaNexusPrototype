package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado del enlace Bluetooth con las gafas. */
enum class GafasState { DISCONNECTED, CONNECTING, CONNECTED }

/** Un aparato emparejado en el telefono, candidato a ser las gafas del agente. */
data class GafasCandidatas(
    val nombre: String,
    val mac: String,
    /** Por el nombre de fabrica (`BleeqUp-Ranger-XXXXX`): se destacan y van primero. */
    val parecenGafas: Boolean,
)

/**
 * Presencia de las gafas de realidad aumentada BleeqUp Ranger.
 *
 * Aqui solo se OBSERVA el enlace para pintarlo en la barra de estado: las gafas
 * se emparejan y se conectan desde los ajustes del sistema. Por eso no hay
 * socket, ni servicio en primer plano, ni reintentos: no hay enlace propio que
 * mantener vivo, y montar uno para saber algo que el sistema ya sabe seria gastar
 * radio y bateria a cambio de nada.
 *
 * Las ordenes (grabar, foto) NO van por aqui: viajan por un GATT propietario que
 * abre [GafasCommandRepository] mientras se esta en la pantalla de mando.
 *
 * Las gafas se presentan al telefono como dispositivo de audio (HFP y A2DP)
 * ademas de BLE, asi que el sistema avisa de sus conexiones como de las de
 * cualquier auricular.
 */
class GafasRepository(private val context: Context) {

    private val _state = MutableStateFlow(GafasState.DISCONNECTED)
    val state: StateFlow<GafasState> = _state.asStateFlow()

    // La MAC no es secreta (va en cada paquete de radio de las gafas), por eso basta
    // con preferencias en claro. Hasta el 2026-09-15 venia compilada en el APK y cada
    // APK solo reconocia unas gafas concretas: las del manager no aparecian nunca.
    private val prefs = context.getSharedPreferences(FICHERO_PREFS, Context.MODE_PRIVATE)

    private val _gafasElegidas = MutableStateFlow(leerGafasElegidas())
    val gafasElegidas: StateFlow<GafasCandidatas?> = _gafasElegidas.asStateFlow()

    /** MAC de las gafas de este agente, o null si todavia no las ha elegido. */
    fun macElegida(): String? = _gafasElegidas.value?.mac

    /**
     * Aparatos emparejados en el telefono, con las gafas primero.
     *
     * No se busca por radio como con la bodycam: **las gafas no se anuncian nunca**
     * (medido el 2026-09-09), asi que una busqueda no las encontraria. Se emparejan
     * desde los ajustes de Android, como unos auriculares, y aqui solo se elige
     * entre lo ya emparejado.
     */
    @SuppressLint("MissingPermission")
    fun emparejadas(): List<GafasCandidatas> {
        if (!tienePermisoBluetooth()) return emptyList()
        val adaptador = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adaptador.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { aparato ->
                val nombre = aparato.name ?: aparato.address
                GafasCandidatas(nombre = nombre, mac = aparato.address, parecenGafas = parecenGafas(nombre))
            }
            .sortedWith(compareByDescending<GafasCandidatas> { it.parecenGafas }.thenBy { it.nombre })
    }

    /** Guarda las gafas del agente y relee el enlace con ellas. */
    fun elegirGafas(gafas: GafasCandidatas) {
        prefs.edit()
            .putString(CLAVE_MAC, gafas.mac)
            .putString(CLAVE_NOMBRE, gafas.nombre)
            .apply()
        _gafasElegidas.value = gafas
        Log.i(TAG, "Gafas elegidas: ${gafas.nombre}")
        // El estado que habia era el de las anteriores.
        cambiarEstado(GafasState.DISCONNECTED)
        refrescar()
    }

    /**
     * Si no hay nada elegido y en el telefono solo hay unas gafas emparejadas, son
     * esas: preguntarlo seria un paso de mas. Con dos o mas se deja elegir al agente.
     */
    fun elegirSolasSiNoHayDuda(): Boolean {
        if (_gafasElegidas.value != null) return false
        val unicas = emparejadas().filter { it.parecenGafas }.singleOrNull() ?: return false
        elegirGafas(unicas)
        return true
    }

    private fun leerGafasElegidas(): GafasCandidatas? {
        val mac = prefs.getString(CLAVE_MAC, null) ?: return null
        val nombre = prefs.getString(CLAVE_NOMBRE, null) ?: mac
        return GafasCandidatas(nombre = nombre, mac = mac, parecenGafas = parecenGafas(nombre))
    }

    private fun parecenGafas(nombre: String): Boolean =
        nombre.contains("bleequp", ignoreCase = true) || nombre.contains("ranger", ignoreCase = true)

    private var estaVigilando = false

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // El enlace fisico es quien manda: mientras haya ACL con las
                // gafas estan puestas y funcionando, caiga el perfil que caiga.
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    if (esDeLasGafas(intent)) cambiarEstado(GafasState.CONNECTED)

                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (esDeLasGafas(intent)) cambiarEstado(GafasState.DISCONNECTED)

                // Del perfil solo se aprovecha el aviso de "conectando", que el
                // ACL no da. No se usa su desconexion: un perfil de audio puede
                // caerse con las gafas todavia enlazadas, y eso se veria como
                // gafas ausentes cuando no lo estan.
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val estadoDelPerfil = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    )
                    if (esDeLasGafas(intent) && estadoDelPerfil == BluetoothProfile.STATE_CONNECTING) {
                        cambiarEstado(GafasState.CONNECTING)
                    }
                }

                // Al apagar el Bluetooth no llega ninguna desconexion por
                // dispositivo, asi que sin esto el icono se quedaria en verde.
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val estadoDelAdaptador = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF,
                    )
                    if (estadoDelAdaptador != BluetoothAdapter.STATE_ON) {
                        cambiarEstado(GafasState.DISCONNECTED)
                    }
                }
            }
        }
    }

    /**
     * Empieza a seguir el enlace con las gafas. Se llama una vez al arrancar la
     * app y el receptor vive lo que viva el proceso: el estado tiene que ser
     * correcto en cualquier pantalla, no solo mientras se mire la barra.
     */
    fun vigilar() {
        // Se vigila aunque todavia no haya gafas elegidas: en cuanto el agente las
        // elija, el receptor ya esta puesto y no hay que reiniciar la app.
        if (!estaVigilando) {
            val filtro = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            // EXPORTED a proposito, como en BuscadorBodycam. En Android 12 o anterior
            // estos avisos los emite el proceso de Bluetooth (otra app, uid 1002), y
            // NOT_EXPORTED se los bloqueaba: visto en el Redmi el 2026-09-15 como
            // "Permission Denial ... DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION". Son
            // broadcasts protegidos del sistema: ninguna otra app puede falsificarlos.
            ContextCompat.registerReceiver(
                context,
                receptor,
                filtro,
                ContextCompat.RECEIVER_EXPORTED,
            )
            estaVigilando = true
        }
        refrescar()
    }

    /**
     * Relee el estado en vez de esperar a un aviso.
     *
     * Hace falta en dos momentos: al arrancar, porque las gafas pueden llevar
     * conectadas desde antes de abrir la app, y despues de conceder el permiso
     * de Bluetooth, porque hasta entonces el sistema no entrega los avisos.
     */
    @SuppressLint("MissingPermission")
    fun refrescar() {
        if (!tienePermisoBluetooth()) return
        // Tambien aqui y no solo en la pantalla FalconOne: el rele de la bodycam
        // manda grabar a las gafas sin que el agente entre nunca en esa pantalla.
        if (elegirSolasSiNoHayDuda()) return
        val adaptador = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adaptador == null || !adaptador.isEnabled) {
            cambiarEstado(GafasState.DISCONNECTED)
            return
        }
        // No hay forma directa de preguntar por el ACL de un dispositivo, asi
        // que se consulta el perfil de audio, que es por donde se conectan.
        adaptador.getProfileProxy(context, oyenteDelPerfil, BluetoothProfile.HEADSET)
    }

    private val oyenteDelPerfil = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val mac = macElegida()
            val gafasConectadas = mac != null && proxy.connectedDevices.any { it.address == mac }
            if (gafasConectadas) {
                cambiarEstado(GafasState.CONNECTED)
            } else if (_state.value == GafasState.CONNECTED) {
                cambiarEstado(GafasState.DISCONNECTED)
            }
            // El proxy es una conexion viva con el servicio de Bluetooth; se
            // cierra en cuanto se ha leido, que es lo unico que se queria.
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.closeProfileProxy(profile, proxy)
        }

        override fun onServiceDisconnected(profile: Int) = Unit
    }

    // Un solo sitio donde cambia el estado, y ahi mismo la traza: el enlace de
    // las gafas no se puede depurar mirando la app, porque lo unico que se ve
    // de el es el color de un icono.
    private fun cambiarEstado(nuevo: GafasState) {
        if (_state.value == nuevo) return
        _state.value = nuevo
        Log.i(TAG, "Gafas: $nuevo")
    }

    private fun esDeLasGafas(intent: Intent): Boolean {
        val dispositivo = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        )
        val mac = macElegida() ?: return false
        return dispositivo?.address == mac
    }

    /**
     * True si ya se puede seguir el enlace. Solo se comprueba BLUETOOTH_CONNECT:
     * las gafas nunca se buscan desde la app, asi que el permiso de escaneo que
     * si necesita la bodycam aqui sobra.
     */
    fun tienePermisoBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "GafasRepository"
        private const val FICHERO_PREFS = "aeria_gafas"
        private const val CLAVE_MAC = "mac"
        private const val CLAVE_NOMBRE = "nombre"
    }
}
