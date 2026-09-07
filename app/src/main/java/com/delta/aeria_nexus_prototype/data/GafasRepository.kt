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
import com.delta.aeria_nexus_prototype.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado del enlace Bluetooth con las gafas. */
enum class GafasState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Presencia de las gafas de realidad aumentada BleeqUp Ranger.
 *
 * A diferencia de la bodycam, la app no manda nada a las gafas: se emparejan y
 * se conectan desde los ajustes del sistema, y aqui solo se OBSERVA el enlace
 * para pintarlo en la barra de estado. Por eso no hay socket, ni servicio en
 * primer plano, ni reintentos: no hay enlace propio que mantener vivo, y montar
 * uno para saber algo que el sistema ya sabe seria gastar radio y bateria a
 * cambio de nada.
 *
 * Las gafas se presentan al telefono como dispositivo de audio (HFP y A2DP)
 * ademas de BLE, asi que el sistema avisa de sus conexiones como de las de
 * cualquier auricular.
 */
class GafasRepository(private val context: Context) {

    private val _state = MutableStateFlow(GafasState.DISCONNECTED)
    val state: StateFlow<GafasState> = _state.asStateFlow()

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
        if (BuildConfig.GAFAS_MAC.isEmpty()) {
            Log.w(TAG, "GAFAS_MAC vacio en local.properties: gafas deshabilitadas")
            return
        }
        if (!estaVigilando) {
            val filtro = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context,
                receptor,
                filtro,
                ContextCompat.RECEIVER_NOT_EXPORTED,
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
            val gafasConectadas = proxy.connectedDevices.any { it.address == BuildConfig.GAFAS_MAC }
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
        return dispositivo?.address == BuildConfig.GAFAS_MAC
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
    }
}
