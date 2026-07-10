package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.delta.aeria_nexus_prototype.BodycamService
import com.delta.aeria_nexus_prototype.BuildConfig
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
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
import org.json.JSONObject

/** Estado de la conexion Bluetooth con la bodycam. */
enum class BodycamState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Conexion y control de la bodycam W1 por Bluetooth RFCOMM (fase 4 del port
 * de Falcon One). El telefono es el cliente; la bodycam corre BodyCamServer,
 * que escucha con el UUID custom de Falcon.
 *
 * El protocolo es texto delimitado por saltos de linea (ver
 * docs/bodycam-contexto.md): comandos como STATUS o PING reciben respuesta,
 * y los botones fisicos de la bodycam llegan como notificaciones BTN_*.
 *
 * El enlace BT con la W1 sufre micro-cortes (atenuacion corporal, WiFi 2.4 GHz
 * del livestream compartiendo radio). Por eso la conexion es un DESEO
 * persistente: mientras el usuario la quiera, un unico bucle reintenta con
 * backoff, un watchdog descarta enlaces muertos que no llegan a fallar la
 * escritura, y BodycamService mantiene el proceso vivo en segundo plano.
 */
class BodycamRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    // Coordina connect/disconnect con el ciclo de vida del bucle de conexion.
    private val lock = Any()

    // Lock propio de escritura: una escritura atascada en un enlace muerto
    // no debe retener a connect/disconnect (que corren en el hilo de UI).
    private val writeLock = Any()

    // True mientras el usuario quiera el enlace; el bucle reintenta hasta
    // que vuelva a false (desconexion manual) o el Bluetooth no este.
    @Volatile private var linkWanted = false
    private var connectionJob: Job? = null

    // Ultimo dato recibido; el watchdog cierra el socket si se queda viejo.
    @Volatile private var lastRxMillis = 0L

    private val _state = MutableStateFlow(BodycamState.DISCONNECTED)
    val state: StateFlow<BodycamState> = _state.asStateFlow()

    val isConnected: Boolean get() = _state.value == BodycamState.CONNECTED

    // Bateria de la bodycam (no la del telefono), del campo battery del STATUS.
    private val _batteryPercent = MutableStateFlow(0)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    // Lineas BTN_* de los botones fisicos, para el flujo SOS de fases siguientes.
    private val _buttonEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val buttonEvents: SharedFlow<String> = _buttonEvents.asSharedFlow()

    /** True si el permiso Bluetooth de runtime ya esta concedido. */
    fun hasBluetoothPermission(): Boolean {
        // Antes de Android 12 el permiso BLUETOOTH es de instalacion, no de runtime.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Activa el enlace con la bodycam: conecta ahora y reintenta solo ante
     * cualquier caida, hasta que se llame a [disconnect].
     */
    fun connect() {
        if (BuildConfig.BODYCAM_MAC.isEmpty()) {
            Log.w(TAG, "BODYCAM_MAC vacio en local.properties: bodycam deshabilitada")
            return
        }
        if (!hasBluetoothPermission()) {
            _state.value = BodycamState.ERROR
            return
        }
        // Con el Bluetooth apagado se avisa de inmediato, sin bucle ni servicio.
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = BodycamState.ERROR
            return
        }
        BodycamService.start(context)
        synchronized(lock) {
            linkWanted = true
            if (connectionJob == null) {
                connectionJob = scope.launch { connectionLoop() }
            }
        }
    }

    /** Desconexion manual: cierra el socket y detiene los reintentos. */
    fun disconnect() {
        synchronized(lock) { linkWanted = false }
        closeQuietly()
        BodycamService.stop(context)
        _state.value = BodycamState.DISCONNECTED
    }

    /**
     * Unico bucle de conexion: intenta, mantiene y reintenta el enlace
     * mientras [linkWanted] siga true. Solo existe una instancia a la vez.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectionLoop() {
        var intentosFallidos = 0
        while (true) {
            // Salida y limpieza del job bajo el mismo lock que usa connect(),
            // para que un connect() simultaneo no se quede sin bucle.
            val seguir = synchronized(lock) {
                if (!linkWanted) connectionJob = null
                linkWanted
            }
            if (!seguir) break

            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                // Sin adaptador o con el Bluetooth apagado no se reintenta:
                // el usuario debe encenderlo y volver a tocar el icono.
                synchronized(lock) {
                    linkWanted = false
                    connectionJob = null
                }
                BodycamService.stop(context)
                _state.value = BodycamState.ERROR
                return
            }

            _state.value = BodycamState.CONNECTING
            val nuevoSocket = try {
                adapter.cancelDiscovery()
                createSocket(adapter.getRemoteDevice(BuildConfig.BODYCAM_MAC))
                    .also { it.connect() }
            } catch (e: Exception) {
                Log.w(TAG, "Intento de conexion fallido: ${e.message}")
                null
            }
            if (nuevoSocket == null) {
                intentosFallidos++
                delay(reconnectDelayMillis(intentosFallidos))
                continue
            }

            intentosFallidos = 0
            socket = nuevoSocket
            output = nuevoSocket.outputStream
            lastRxMillis = System.currentTimeMillis()
            _state.value = BodycamState.CONNECTED
            Log.i(TAG, "Bodycam conectada")

            // El poll de STATUS y el watchdog viven solo mientras dura esta
            // conexion; readUntilClosed bloquea hasta que el enlace se cae.
            val vigilantes = scope.launch {
                launch { statusPollLoop() }
                launch { watchdogLoop() }
            }
            readUntilClosed(nuevoSocket)
            vigilantes.cancel()
            closeQuietly()

            if (linkWanted) {
                Log.i(TAG, "Enlace con la bodycam perdido, reintentando…")
                delay(RETRY_AFTER_DROP_MILLIS)
            }
        }
        _state.value = BodycamState.DISCONNECTED
    }

    /**
     * Socket RFCOMM insecure con el UUID custom de Falcon (BodyCamServer
     * escucha sin cifrado de enlace). Si el SDP de la W1 no responde, se cae
     * al canal 1 por reflexion, igual que hacia la app Flutter original.
     */
    private fun createSocket(device: BluetoothDevice): BluetoothSocket =
        try {
            device.createInsecureRfcommSocketToServiceRecord(FALCON_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "SDP fallido, probando canal 1: ${e.message}")
            device.javaClass
                .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        }

    /** Backoff de reconexion: rapido al principio, con techo de 10 segundos. */
    private fun reconnectDelayMillis(intento: Int): Long = when (intento) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        else -> 10_000L
    }

    /** Envia un comando del protocolo; el salto de linea se agrega aqui. */
    fun sendCommand(command: String) {
        scope.launch {
            try {
                // Un solo escritor a la vez: sin esto, comandos concurrentes
                // podrian entrelazar sus bytes dentro de la misma linea.
                synchronized(writeLock) {
                    output?.write("$command\n".toByteArray(Charsets.UTF_8))
                    output?.flush()
                }
            } catch (e: IOException) {
                // El cierre despierta a readUntilClosed y dispara el reintento.
                closeQuietly()
            }
        }
    }

    /** Lee lineas del socket hasta que la conexion se pierda o se cierre. */
    private fun readUntilClosed(activeSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(activeSocket.inputStream))
            var linea: String?
            while (reader.readLine().also { linea = it } != null) {
                lastRxMillis = System.currentTimeMillis()
                linea?.trim()?.takeIf { it.isNotEmpty() }?.let(::handleLine)
            }
        } catch (e: IOException) {
            // Fin normal de la conexion: el bucle decide si reintenta.
        }
    }

    /** Pide STATUS cada 5 segundos; ademas de la bateria, sirve de keepalive. */
    private suspend fun statusPollLoop() {
        while (true) {
            sendCommand("STATUS")
            delay(STATUS_POLL_MILLIS)
        }
    }

    /**
     * Un enlace BT puede morir sin que la escritura falle (los bytes quedan en
     * el buffer). Si en [STALE_LINK_MILLIS] no llego ni una linea —el poll
     * garantiza al menos dos STATUS en ese lapso—, se fuerza el cierre para
     * que el bucle reconecte ya, sin esperar el timeout de supervision BT.
     */
    private suspend fun watchdogLoop() {
        while (true) {
            delay(WATCHDOG_CHECK_MILLIS)
            if (System.currentTimeMillis() - lastRxMillis > STALE_LINK_MILLIS) {
                Log.w(TAG, "Sin datos de la bodycam hace ${STALE_LINK_MILLIS / 1000} s: enlace muerto")
                closeQuietly()
                return
            }
        }
    }

    private fun handleLine(line: String) {
        when {
            // STATUS:{json} — estado periodico de la bodycam.
            line.startsWith("STATUS:") -> {
                try {
                    val estado = JSONObject(line.removePrefix("STATUS:"))
                    _batteryPercent.value = estado.optInt("battery", _batteryPercent.value)
                } catch (e: Exception) {
                    Log.w(TAG, "STATUS ilegible de la bodycam")
                }
            }
            // Botones fisicos (BTN_STREAM_*, BTN_REC_*, BTN_PTT).
            line.startsWith("BTN_") -> _buttonEvents.tryEmit(line)
            // OK:x / ERROR:x / PONG: respuestas a comandos, sin uso por ahora.
        }
    }

    private fun closeQuietly() {
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        output = null
        socket = null
        _batteryPercent.value = 0
    }

    companion object {
        private const val TAG = "BodycamRepository"

        // Mismo UUID que BodyCamServer en la bodycam (RFCOMM custom de Falcon).
        private val FALCON_UUID: UUID = UUID.fromString("FA1C0000-1337-4242-CAFE-DEADBEEF0001")

        private const val STATUS_POLL_MILLIS = 5_000L
        private const val WATCHDOG_CHECK_MILLIS = 3_000L
        private const val STALE_LINK_MILLIS = 12_000L
        private const val RETRY_AFTER_DROP_MILLIS = 1_000L
    }
}
