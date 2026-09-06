package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.delta.aeria_nexus_prototype.BodycamService
import com.delta.aeria_nexus_prototype.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.delta.aeria_nexus_prototype.data.identity.BindingActivo
import com.delta.aeria_nexus_prototype.data.identity.BindingPeriferico
import com.delta.aeria_nexus_prototype.data.identity.ClaveEnKeystore
import com.delta.aeria_nexus_prototype.data.identity.EmparejamientoDelTelefono
import com.delta.aeria_nexus_prototype.data.identity.ResultadoEmparejamiento
import java.util.Base64
import org.json.JSONObject

/** Estado de la conexion Bluetooth con la bodycam. */
enum class BodycamState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Si sabemos con QUIEN estamos hablando (workflow 31).
 *
 * Estar conectado y estar autenticado son dos cosas distintas, y hasta hoy la app
 * solo distinguia la primera. El canal es un RFCOMM sin cifrado con un UUID que
 * esta publicado en nuestra propia documentacion: conectar no prueba nada.
 */
enum class EnlaceAutenticado {
    /** Sin enlace, o enlace recien abierto y todavia sin intentar el intercambio. */
    DESCONOCIDO,

    /** Intercambio en curso. */
    COMPROBANDO,

    /** La bodycam demostro su identidad y nosotros la nuestra ante ella. */
    SI,

    /**
     * El intercambio no se pudo completar: firmware sin el workflow 31, o este
     * telefono sin ancla de confianza para perifericos. No es que mienta: es que
     * todavia no hay con que comprobarlo.
     */
    NO_SOPORTADO,

    /** El intercambio se completo y algo no cuadro. Este enlace NO es de fiar. */
    RECHAZADO,
}

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

    private val _enlaceAutenticado = MutableStateFlow(EnlaceAutenticado.DESCONOCIDO)
    val enlaceAutenticado: StateFlow<EnlaceAutenticado> = _enlaceAutenticado.asStateFlow()

    private val _motivoDelEnlace = MutableStateFlow<String?>(null)
    val motivoDelEnlace: StateFlow<String?> = _motivoDelEnlace.asStateFlow()

    /** Atadura agente-camara en vigor (workflow 33). Null si la camara no sirve a nadie. */
    private val _binding = MutableStateFlow<BindingActivo?>(null)
    val binding: StateFlow<BindingActivo?> = _binding.asStateFlow()

    /** Intercambio en curso, si lo hay. Vive lo que dure una conexion. */
    private var emparejamiento: EmparejamientoDelTelefono? = null
    private var inicioDelEmparejamientoMillis = 0L

    /** Declaracion enviada a la camara mientras se espera su BIND_OK. */
    private var pendienteDeAtar: BindingPeriferico.Declaracion? = null

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

    // Grabacion local y livestream de la bodycam. Las respuestas OK:* y los
    // botones fisicos BTN_* adelantan el valor al instante; el STATUS de cada
    // 5 segundos es la fuente de verdad que corrige cualquier desvio.
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Visor remoto de foto: la bodycam mantiene su camara abierta y sirve
    // frames JPEG por HTTP mientras este flag sea true.
    private val _isPreviewing = MutableStateFlow(false)
    val isPreviewing: StateFlow<Boolean> = _isPreviewing.asStateFlow()

    // Direccion del servidor HTTP de la bodycam (visor y descarga de
    // grabaciones); llega en cada STATUS cuando la W1 tiene WiFi.
    @Volatile private var fileServerIp: String? = null
    @Volatile private var fileServerPort = DEFAULT_FILE_SERVER_PORT

    // Lineas BTN_* de los botones fisicos, para el flujo SOS de fases siguientes.
    private val _buttonEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val buttonEvents: SharedFlow<String> = _buttonEvents.asSharedFlow()

    // Respuestas OK:/ERROR: a los comandos, para dar feedback puntual en la UI.
    private val _commandResponses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commandResponses: SharedFlow<String> = _commandResponses.asSharedFlow()

    /** True si los permisos Bluetooth de runtime ya estan concedidos. */
    fun hasBluetoothPermission(): Boolean {
        // Antes de Android 12 el permiso BLUETOOTH es de instalacion, no de runtime.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        // CONNECT para el socket y SCAN para cancelDiscovery() antes de conectar.
        return BLUETOOTH_RUNTIME_PERMISSIONS.all { permiso ->
            ContextCompat.checkSelfPermission(context, permiso) ==
                PackageManager.PERMISSION_GRANTED
        }
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
            iniciarEmparejamiento()

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
            vigilarEmparejamiento()
            if (System.currentTimeMillis() - lastRxMillis > STALE_LINK_MILLIS) {
                Log.w(TAG, "Sin datos de la bodycam hace ${STALE_LINK_MILLIS / 1000} s: enlace muerto")
                closeQuietly()
                return
            }
        }
    }

    /**
     * Arranca el intercambio del workflow 31 en cuanto hay enlace.
     *
     * Se hace lo primero, antes del primer STATUS: cuanto mas tarde se autentique
     * el canal, mas comandos habran viajado por un enlace del que no sabemos nada.
     */
    private fun iniciarEmparejamiento() {
        _motivoDelEnlace.value = null
        val certificado = ClaveEnKeystore.terminal.certificado()
        if (certificado == null) {
            marcarEnlace(
                EnlaceAutenticado.NO_SOPORTADO,
                "Este telefono no esta dado de alta: no tiene certificado que presentar",
            )
            return
        }

        emparejamiento = EmparejamientoDelTelefono(
            deviceId = ClaveEnKeystore.terminal.commonNameDelCertificado() ?: "DESCONOCIDO",
            certificadoDelTelefono = certificado,
            firmar = { datos -> ClaveEnKeystore.terminal.firmarReto(datos) },
            anclaDePerifericos = AppContainer.enrollmentRepository.anclaDePerifericos(),
        )
        inicioDelEmparejamientoMillis = System.currentTimeMillis()
        _enlaceAutenticado.value = EnlaceAutenticado.COMPROBANDO
        sendCommand(emparejamiento!!.saludo())
    }

    /** Cada linea AUTH_* del intercambio, en el orden en que llegan. */
    private fun continuarEmparejamiento(linea: String) {
        val enCurso = emparejamiento ?: return
        if (linea.startsWith("AUTH_ID")) {
            val prueba = enCurso.responderA(linea)
            if (prueba == null) {
                marcarEnlace(EnlaceAutenticado.RECHAZADO, "La bodycam se identifico de forma ilegible")
                return
            }
            sendCommand(prueba)
            return
        }

        when (val resultado = enCurso.comprobar(linea)) {
            is ResultadoEmparejamiento.Autenticado -> {
                marcarEnlace(EnlaceAutenticado.SI, "${resultado.bwcId} · ${resultado.sujeto}")
                // El emparejamiento dice QUE camara es; la atadura dice A QUIEN
                // sirve. Son dos cosas distintas y esta es la segunda.
                atarAlAgente(resultado.bwcId, resultado.nonceDeLaSesion)
            }

            is ResultadoEmparejamiento.Rechazado ->
                marcarEnlace(EnlaceAutenticado.RECHAZADO, resultado.motivo)

            is ResultadoEmparejamiento.NoSoportado ->
                marcarEnlace(EnlaceAutenticado.NO_SOPORTADO, resultado.motivo)
        }
        emparejamiento = null
    }

    /**
     * Una bodycam sin el workflow 31 no contesta al saludo: no responde nada, o
     * responde un error de comando desconocido. Se le da un plazo y se sigue.
     */
    private fun vigilarEmparejamiento() {
        if (_enlaceAutenticado.value != EnlaceAutenticado.COMPROBANDO) return
        if (System.currentTimeMillis() - inicioDelEmparejamientoMillis < PLAZO_EMPAREJAMIENTO_MILLIS) return

        emparejamiento = null
        marcarEnlace(
            EnlaceAutenticado.NO_SOPORTADO,
            "La bodycam no respondio al emparejamiento: firmware sin el workflow 31",
        )
    }

    /**
     * QUE PASA CUANDO NO ESTA AUTENTICADO, dicho aqui para que se vea al leerlo:
     * hoy **no se corta el enlace**. Ninguna bodycam implementa todavia su mitad
     * —es el workflow 13 y vive en otro repositorio—, asi que cortar dejaria la
     * camara inservible sin haber ganado nada. Se marca, se registra y se ensena.
     *
     * En cuanto exista una W1 con identidad propia, esto tiene que invertirse:
     * un enlace RECHAZADO se cierra, y un NO_SOPORTADO deja de permitir grabar.
     */
    private fun marcarEnlace(estado: EnlaceAutenticado, motivo: String) {
        _enlaceAutenticado.value = estado
        _motivoDelEnlace.value = motivo
        when (estado) {
            EnlaceAutenticado.SI -> Log.i(TAG, "Enlace autenticado con $motivo")
            EnlaceAutenticado.RECHAZADO -> Log.e(TAG, "ENLACE NO FIABLE: $motivo")
            else -> Log.w(TAG, "Enlace sin autenticar: $motivo")
        }
    }

    /**
     * Ata la camara al agente que tiene la sesion abierta (workflow 33).
     *
     * La atadura es una declaracion firmada CON LA CLAVE DEL AGENTE, no con la del
     * telefono, y ahi esta el sentido: quien autoriza a la camara a operar en su
     * nombre es la persona, no el aparato. Por eso solo se puede crear con la
     * sesion abierta —la clave del agente esta autorizada solo entonces— y por eso
     * cerrar sesion la deshace.
     */
    private fun atarAlAgente(bwcId: String, nonceDeLaSesion: ByteArray) {
        val identidad = AppContainer.identityRepository.status.value.identity
        val credential = AppContainer.credentialRepository
        if (identidad == null || !credential.puedeFirmarComoElAgente) {
            Log.w(TAG, "camara autenticada pero sin sesion de agente: no se ata a nadie")
            return
        }
        val certificado = ClaveEnKeystore.agente.certificado()
        if (certificado == null) {
            Log.w(TAG, "no hay certificado del agente que presentar a la camara")
            return
        }

        val ahora = System.currentTimeMillis()
        val declaracion = BindingPeriferico.declaracion(
            bindingId = BindingPeriferico.nuevoId(),
            userId = identidad.userId,
            deviceId = identidad.deviceId,
            bwcId = bwcId,
            tenant = identidad.tenant,
            nonceDeLaSesion = nonceDeLaSesion,
            emitidoEn = ahora,
            caducaEn = ahora + BindingPeriferico.VALIDEZ_POR_DEFECTO_MILLIS,
        )
        val firma = credential.firmarRetoDeSesion(declaracion.toByteArray(Charsets.UTF_8))
        val codificador = Base64.getEncoder()
        pendienteDeAtar = BindingPeriferico.leer(declaracion)
        sendCommand(
            listOf(
                "BIND",
                codificador.encodeToString(declaracion.toByteArray(Charsets.UTF_8)),
                codificador.encodeToString(firma),
                codificador.encodeToString(certificado.encoded),
            ).joinToString(":"),
        )
    }

    /**
     * Deshace la atadura (workflow 34): fin de turno, cierre de sesion, caducidad,
     * reasignacion o revocacion.
     *
     * Va firmada igual que la creacion. Si cualquiera pudiera deshacerla, bastaria
     * con acercarse a la camara para dejar al agente sin atribucion en mitad de un
     * incidente, y la evidencia de ese rato no podria decir quien la grabo.
     */
    fun desatar(motivo: BindingPeriferico.MotivoDeFin) {
        val activo = _binding.value ?: return
        val credential = AppContainer.credentialRepository
        _binding.value = null

        if (!credential.puedeFirmarComoElAgente) {
            // Sin sesion no se puede firmar el fin. La atadura muere igual por
            // caducidad y al reconectar no se recrea, asi que el efecto se produce;
            // lo que se pierde es el aviso inmediato a la camara.
            Log.w(TAG, "sesion ya cerrada: la atadura de ${activo.bwcId} caducara sola")
            return
        }
        val declaracion = BindingPeriferico.declaracionDeFin(
            bindingId = activo.bindingId,
            motivo = motivo,
            momento = System.currentTimeMillis(),
        )
        val firma = credential.firmarRetoDeSesion(declaracion.toByteArray(Charsets.UTF_8))
        val codificador = Base64.getEncoder()
        sendCommand(
            listOf(
                "UNBIND",
                codificador.encodeToString(declaracion.toByteArray(Charsets.UTF_8)),
                codificador.encodeToString(firma),
            ).joinToString(":"),
        )
        Log.i(TAG, "atadura de ${activo.bwcId} deshecha: ${motivo.name}")
    }

    private fun atenderRespuestaDeAtadura(linea: String) {
        when {
            linea.startsWith("BIND_OK") -> {
                val declaracion = pendienteDeAtar
                if (declaracion == null) {
                    Log.w(TAG, "BIND_OK sin atadura pendiente")
                    return
                }
                _binding.value = BindingActivo(
                    bindingId = declaracion.bindingId,
                    userId = declaracion.userId,
                    bwcId = declaracion.bwcId,
                    caducaEn = declaracion.caducaEn,
                )
                Log.i(TAG, "camara ${declaracion.bwcId} atada a ${declaracion.userId}")
            }

            linea.startsWith("BIND_FAIL") -> {
                _binding.value = null
                Log.e(TAG, "la camara rechazo la atadura: $linea")
            }

            linea.startsWith("UNBIND_OK") -> Log.i(TAG, "la camara confirmo el desatado")
        }
        pendienteDeAtar = null
    }

    private fun handleLine(line: String) {
        // El intercambio del workflow 31 viaja por el mismo canal de texto, asi
        // que se atiende aqui antes que nada: si se hiciera con lecturas
        // bloqueantes aparte, competiria con este bucle por las mismas lineas.
        if (line.startsWith("AUTH_")) {
            continuarEmparejamiento(line)
            return
        }
        if (line.startsWith("BIND_") || line.startsWith("UNBIND_")) {
            atenderRespuestaDeAtadura(line)
            return
        }
        when {
            // STATUS:{json} — estado periodico de la bodycam.
            line.startsWith("STATUS:") -> {
                try {
                    val estado = JSONObject(line.removePrefix("STATUS:"))
                    _batteryPercent.value = estado.optInt("battery", _batteryPercent.value)
                    _isRecording.value = estado.optBoolean("recording", _isRecording.value)
                    _isStreaming.value = estado.optBoolean("streaming", _isStreaming.value)
                    _isPreviewing.value = estado.optBoolean("preview", _isPreviewing.value)
                    fileServerIp = estado.optString("file_server_ip").takeIf { it.isNotEmpty() }
                    fileServerPort = estado.optInt("file_server_port", fileServerPort)
                } catch (e: Exception) {
                    Log.w(TAG, "STATUS ilegible de la bodycam")
                }
            }
            // Botones fisicos (BTN_STREAM_*, BTN_REC_*, BTN_PTT).
            line.startsWith("BTN_") -> {
                applyStateChange(line)
                _buttonEvents.tryEmit(line)
            }
            // OK:x / ERROR:x — respuestas a los comandos enviados.
            line.startsWith("OK:") || line.startsWith("ERROR:") -> {
                applyStateChange(line)
                _commandResponses.tryEmit(line)
            }
        }
    }

    /**
     * Adelanta el estado de grabacion/streaming sin esperar al siguiente
     * STATUS. La camara de la bodycam es exclusiva: iniciar el livestream
     * detiene la grabacion local en el dispositivo, por eso se apaga aqui.
     */
    private fun applyStateChange(line: String) {
        when (line) {
            "OK:REC_START", "BTN_REC_START" -> {
                _isRecording.value = true
                _isPreviewing.value = false
            }
            "OK:REC_STOP", "BTN_REC_STOP" -> _isRecording.value = false
            "OK:STREAM_STOP", "BTN_STREAM_STOP" -> _isStreaming.value = false
            "OK:STREAM_START", "BTN_STREAM_START" -> {
                _isStreaming.value = true
                _isRecording.value = false
                _isPreviewing.value = false
            }
            "OK:PREVIEW_START" -> _isPreviewing.value = true
            "OK:PREVIEW_STOP" -> _isPreviewing.value = false
        }
    }

    private fun closeQuietly() {
        emparejamiento = null
        // La atadura NO sobrevive al enlace: un corte de Bluetooth es rutinario y
        // se recrea al reconectar, pero mientras no hay enlace la camara no esta
        // sirviendo a nadie y la interfaz no debe decir lo contrario.
        pendienteDeAtar = null
        _binding.value = null
        _enlaceAutenticado.value = EnlaceAutenticado.DESCONOCIDO
        _motivoDelEnlace.value = null
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        output = null
        socket = null
        _batteryPercent.value = 0
        // Sin enlace no se conoce el estado real de la bodycam: se muestra
        // apagado hasta que el primer STATUS de la reconexion lo confirme.
        _isRecording.value = false
        _isStreaming.value = false
        // La bodycam tambien apaga su visor al perder el telefono.
        _isPreviewing.value = false
    }

    /**
     * Frames del visor en vivo: abre GET /preview/stream (MJPEG) y emite cada
     * frame ya enderezado. El flujo termina cuando la bodycam apaga su visor,
     * la red se cae o aun no llego la IP en el STATUS; quien colecta decide
     * si reintenta.
     */
    fun streamPreviewFrames(rotationDegrees: Float): Flow<Bitmap> = flow {
        val ip = fileServerIp ?: return@flow
        var conexion: HttpURLConnection? = null
        try {
            conexion = URL("http://$ip:$fileServerPort/preview/stream").openConnection() as HttpURLConnection
            conexion.connectTimeout = FRAME_TIMEOUT_MILLIS
            conexion.readTimeout = STREAM_READ_TIMEOUT_MILLIS
            if (conexion.responseCode != HttpURLConnection.HTTP_OK) return@flow
            val entrada = BufferedInputStream(conexion.inputStream)
            while (true) {
                val bytes = leerFrameMultipart(entrada) ?: break
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.let { emit(rotarFrame(it, rotationDegrees)) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Stream del visor cortado: ${e.message}")
        } finally {
            conexion?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Lee un frame del stream MJPEG: cabeceras de la parte (de donde sale
     * Content-Length) y despues exactamente esos bytes de JPEG. Devuelve null
     * al terminar el stream.
     */
    private fun leerFrameMultipart(entrada: BufferedInputStream): ByteArray? {
        var tamano = -1
        while (true) {
            val linea = leerLinea(entrada) ?: return null
            if (linea.startsWith("Content-Length:", ignoreCase = true)) {
                tamano = linea.substringAfter(":").trim().toIntOrNull() ?: return null
            }
            // La linea vacia separa cabeceras de datos dentro de cada parte.
            if (linea.isEmpty() && tamano > 0) break
        }
        val datos = ByteArray(tamano)
        var leidos = 0
        while (leidos < tamano) {
            val n = entrada.read(datos, leidos, tamano - leidos)
            if (n < 0) return null
            leidos += n
        }
        return datos
    }

    /** Linea de texto terminada en \n (sin el \r\n), o null si el stream acabo. */
    private fun leerLinea(entrada: BufferedInputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val b = entrada.read()
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString().trimEnd('\r')
            bytes.append(b.toChar())
        }
    }

    // Cada fuente de la bodycam entrega el frame con su propia orientacion;
    // el que colecta pasa los grados ajustados en campo para su fuente.
    private fun rotarFrame(frame: Bitmap, grados: Float): Bitmap {
        if (grados == 0f) return frame
        val matriz = Matrix().apply { postRotate(grados) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matriz, true)
    }

    companion object {
        private const val TAG = "BodycamRepository"

        // Permisos de runtime que exige Android 12+ para conectar por Bluetooth;
        // la UI los pide juntos en un solo dialogo ("Dispositivos cercanos").
        val BLUETOOTH_RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )

        // Mismo UUID que BodyCamServer en la bodycam (RFCOMM custom de Falcon).
        private val FALCON_UUID: UUID = UUID.fromString("FA1C0000-1337-4242-CAFE-DEADBEEF0001")

        /** Lo que se espera a que la bodycam conteste al saludo antes de seguir sin el. */
        private const val PLAZO_EMPAREJAMIENTO_MILLIS = 6_000L

        private const val STATUS_POLL_MILLIS = 5_000L
        private const val WATCHDOG_CHECK_MILLIS = 3_000L
        private const val STALE_LINK_MILLIS = 12_000L
        private const val RETRY_AFTER_DROP_MILLIS = 1_000L

        private const val DEFAULT_FILE_SERVER_PORT = 8080
        private const val FRAME_TIMEOUT_MILLIS = 3_000
        // La W1 empuja un frame cada ~150 ms; si en 5 s no llega nada, el
        // enlace esta muerto y conviene cortar para que el visor reintente.
        private const val STREAM_READ_TIMEOUT_MILLIS = 5_000

        // Rotacion de los frames segun la fuente, ajustadas en campo:
        // visor de foto (2026-07-12: con 90 quedaba girada a la derecha).
        const val PREVIEW_ROTATION_DEGREES = 0f
        // Monitor de grabacion (2026-07-12: el TextureView llega girado 90
        // a la derecha; se endereza con 90 a la izquierda).
        const val MONITOR_ROTATION_DEGREES = -90f
    }
}
