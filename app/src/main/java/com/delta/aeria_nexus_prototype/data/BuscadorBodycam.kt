package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Un aparato Bluetooth visible desde el telefono, candidato a ser la bodycam. */
data class DispositivoCercano(
    val nombre: String,
    val mac: String,
    val pareceBodycam: Boolean,
)

/**
 * Busca bodycams cercanas para que el agente elija la suya desde la app.
 *
 * Antes la MAC de la W1 se metia en el APK al compilar, y un APK solo servia para
 * una camara concreta. No basta con listar los aparatos emparejados: el enlace es
 * un RFCOMM insecure, asi que la W1 funciona sin emparejarla en los ajustes de
 * Android y lo normal es que no aparezca en esa lista. Por eso se hace una busqueda
 * de verdad.
 */
class BuscadorBodycam(private val context: Context) {

    /** Permisos que exige la busqueda en esta version de Android. */
    fun permisosNecesarios(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BodycamRepository.BLUETOOTH_RUNTIME_PERMISSIONS
        } else {
            // Hasta Android 11 la busqueda no entrega resultados sin la ubicacion.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun tienePermisos(): Boolean = permisosNecesarios().all { permiso ->
        ContextCompat.checkSelfPermission(context, permiso) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Emite la lista acumulada cada vez que aparece un aparato nuevo y termina
     * cuando Android da la busqueda por acabada (unos 12 segundos). Cancelar la
     * recoleccion detiene la busqueda.
     */
    @SuppressLint("MissingPermission")
    fun buscar(): Flow<List<DispositivoCercano>> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled || !tienePermisos()) {
            close()
            return@callbackFlow
        }

        val encontrados = LinkedHashMap<String, DispositivoCercano>()
        fun anadir(dispositivo: BluetoothDevice) {
            // Sin nombre no hay forma de que el agente la reconozca en la lista.
            val nombre = dispositivo.name ?: return
            encontrados[dispositivo.address] = DispositivoCercano(
                nombre = nombre,
                mac = dispositivo.address,
                pareceBodycam = nombre.startsWith(PREFIJO_NOMBRE_W1),
            )
            trySend(encontrados.values.sortedByDescending { it.pareceBodycam })
        }

        adapter.bondedDevices.orEmpty().forEach { anadir(it) }

        val receptor = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        IntentCompat.getParcelableExtra(
                            intent,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )?.let { anadir(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filtro = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Son broadcasts protegidos del sistema: ninguna otra app puede falsificarlos.
        ContextCompat.registerReceiver(context, receptor, filtro, ContextCompat.RECEIVER_EXPORTED)

        adapter.cancelDiscovery()
        if (!adapter.startDiscovery()) close()

        awaitClose {
            adapter.cancelDiscovery()
            context.unregisterReceiver(receptor)
        }
    }

    private companion object {
        // Nombre Bluetooth de fabrica de la W1 (modelo DSJ-ZXAN9A1).
        const val PREFIJO_NOMBRE_W1 = "DSJ-"
    }
}
