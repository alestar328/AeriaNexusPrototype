package com.delta.aeria_nexus_prototype.data

import android.util.Log
import com.bleequp.bleequplibrary.BleeqUpWifiManager
import com.delta.aeria_nexus_prototype.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "DescargaDeGafas"

/** En que punto va la descarga, para poder contarlo en pantalla. */
sealed interface EstadoDescarga {
    data object Parada : EstadoDescarga
    data object EncendiendoWifi : EstadoDescarga
    data object Uniendose : EstadoDescarga
    data class Trayendo(val nombre: String, val hecho: Int, val total: Int) : EstadoDescarga
    data class Terminada(val traidos: Int, val fallados: Int) : EstadoDescarga
    data class Fallo(val motivo: String) : EstadoDescarga
}

/**
 * Trae a la boveda los videos que las gafas tienen pendientes.
 *
 * Es el unico momento del dia en que la app le pide algo al oficial. El video no
 * viaja solo: se queda en la tarjeta de las gafas y hay que encender su punto de
 * acceso, unir el telefono a esa red y bajarlo. Por eso el ciclo entero vive aqui
 * y no repartido entre una pantalla y un repositorio.
 *
 * ## Lo que hace, en orden
 *
 * 1. Enciende el AP de las gafas por BLE y se queda con SSID y clave.
 * 2. Une el telefono a esa red (el sistema pide confirmacion al agente).
 * 3. Lista lo que hay y baja **solo lo que esta pendiente**, cifrandolo en la
 *    boveda como evidencia en bruto, sin incidente.
 * 4. Suelta la red y apaga el AP, pase lo que pase.
 *
 * ## Cuando NO se puede
 *
 * **Mientras la bodycam este grabando o transmitiendo, no.** Las gafas apagan su
 * WiFi mientras graban, asi que no habria nada a lo que unirse; y unir el telefono
 * a una red sin internet en mitad de una emergencia es lo ultimo que conviene.
 */
class DescargaDeGafas(
    private val media: GafasMediaRepository,
    private val pendientes: GafasPendientesRepository,
    private val mando: GafasCommandRepository,
    private val bodycam: BodycamRepository,
) {

    private val _estado = MutableStateFlow<EstadoDescarga>(EstadoDescarga.Parada)
    val estado: StateFlow<EstadoDescarga> = _estado.asStateFlow()

    /**
     * Trae todo lo pendiente. [clave] es la contrasena que se le pone al AP de las
     * gafas: exactamente 8 letras o digitos, lo exige el SDK.
     */
    suspend fun traerPendientes(clave: String) {
        // Solo bloquea si hay una descarga VIVA. Un fallo tiene que poder
        // reintentarse: antes dejaba el boton muerto para el resto de la sesion.
        if (estaEnMarcha()) {
            Log.w(TAG, "ya hay una descarga en curso")
            return
        }
        val queFaltan = pendientes.pendientes.value.map { it.nombre }
        if (queFaltan.isEmpty()) {
            _estado.value = EstadoDescarga.Terminada(traidos = 0, fallados = 0)
            return
        }
        if (bodycam.isRecording.value || bodycam.isStreaming.value) {
            // Las gafas apagan su WiFi mientras graban: no habria a que unirse.
            _estado.value = EstadoDescarga.Fallo("Not possible while the bodycam is recording")
            return
        }

        _estado.value = EstadoDescarga.EncendiendoWifi
        val credenciales = mando.encenderPuntoDeAcceso(clave)
        if (credenciales == null) {
            // Casi siempre es el codigo de pais, no una averia: ver el KDoc de
            // [paisParaElPuntoDeAcceso]. Decirlo evita que alguien pierda una
            // tarde revisando el Bluetooth.
            _estado.value = EstadoDescarga.Fallo(
                "The FalconOne did not turn on its Wi-Fi. Its only channel (149, 5.8 GHz) is " +
                    "not allowed with the phone country set to ${java.util.Locale.getDefault().country}.",
            )
            return
        }

        try {
            _estado.value = EstadoDescarga.Uniendose
            if (!media.conectar(credenciales.ssid, credenciales.clave)) {
                _estado.value = EstadoDescarga.Fallo("The phone could not join the FalconOne Wi-Fi")
                return
            }
            // Ultimo cinturon: si el AP se va sin cerrar el socket, la lectura se
            // queda bloqueada y ni siquiera salta el timeout de la conexion. Sin
            // esto la pantalla se quedaba en "Retrieving" para siempre.
            val termino = withTimeoutOrNull(TOPE_DE_LA_TANDA_MILLIS) { traer(queFaltan) }
            if (termino == null) {
                Log.e(TAG, "la tanda supero el tope de $TOPE_DE_LA_TANDA_MILLIS ms")
                _estado.value = EstadoDescarga.Fallo(
                    "The download timed out. Check the FalconOne and try again.",
                )
            }
        } finally {
            // Soltar la red no es opcional: la peticion retiene el WiFi del
            // telefono en un AP sin internet mientras viva.
            media.desconectar()
            mando.apagarPuntoDeAcceso()
        }
    }

    private suspend fun traer(queFaltan: List<String>) {
        val enLasGafas = media.listar()
        if (enLasGafas.isEmpty()) {
            _estado.value = EstadoDescarga.Fallo("The FalconOne returned no files")
            return
        }
        // Solo lo pendiente: en la tarjeta hay decenas de ficheros viejos que no
        // son de este servicio y no hay por que traerselos.
        val aTraer = enLasGafas.filter { it.nombre in queFaltan }
        if (aTraer.isEmpty()) {
            // Lo pendiente ya no esta en la tarjeta: alguien lo borro. Se quita de
            // la lista para que no quede pidiendo algo que no existe.
            Log.w(TAG, "lo pendiente ya no esta en las gafas; se limpia la lista")
            queFaltan.forEach { pendientes.quitar(it) }
            _estado.value = EstadoDescarga.Terminada(traidos = 0, fallados = 0)
            return
        }

        var traidos = 0
        var fallados = 0
        aTraer.forEachIndexed { indice, archivo ->
            _estado.value = EstadoDescarga.Trayendo(archivo.nombre, indice, aTraer.size)
            // Si el AP se cae a media tanda no tiene sentido seguir pidiendo
            // ficheros: cada uno esperaria su propio timeout y la pantalla se
            // quedaria en "Retrieving" durante minutos.
            if (!media.conectado) {
                Log.e(TAG, "se perdio el WiFi de las gafas a media descarga")
                _estado.value = EstadoDescarga.Fallo(
                    "Lost the FalconOne Wi-Fi. $traidos retrieved, ${aTraer.size - traidos} pending.",
                )
                return
            }
            when (val resultado = media.descargar(archivo)) {
                is ResultadoDescarga.Cifrada -> {
                    // Se quita SOLO cuando ya esta cifrado en la boveda: si se
                    // quitara antes, un fallo a mitad perderia el video para
                    // siempre sin que nadie lo supiera.
                    pendientes.quitar(archivo.nombre)
                    traidos++
                }

                is ResultadoDescarga.Fallo -> {
                    Log.e(TAG, "no se pudo traer ${archivo.nombre}: ${resultado.motivo}")
                    fallados++
                }
            }
        }
        _estado.value = EstadoDescarga.Terminada(traidos, fallados)
        Log.i(TAG, "descarga terminada: $traidos traidos, $fallados fallados")
    }

    /** Vuelve al reposo para que la pantalla no se quede con el ultimo resultado. */
    fun olvidarResultado() {
        if (estaEnMarcha()) return
        _estado.value = EstadoDescarga.Parada
    }

    private fun estaEnMarcha(): Boolean = when (_estado.value) {
        is EstadoDescarga.EncendiendoWifi,
        is EstadoDescarga.Uniendose,
        is EstadoDescarga.Trayendo,
        -> true

        else -> false
    }
}

/** Credenciales del punto de acceso que levantan las gafas. */
data class PuntoDeAccesoGafas(val ssid: String, val clave: String)

/**
 * Enciende el AP de las gafas y espera a que conteste.
 *
 * Vive como extension y no dentro de [GafasCommandRepository] porque el WiFi de
 * las gafas lo maneja otro objeto del SDK ([BleeqUpWifiManager]) aunque viaje por
 * el mismo GATT.
 */
private suspend fun GafasCommandRepository.encenderPuntoDeAcceso(
    clave: String,
): PuntoDeAccesoGafas? {
    val device = aparatoParaWifi() ?: return null
    val paisAnterior = java.util.Locale.getDefault()
    declararPais()
    var resultado: PuntoDeAccesoGafas? = null
    var respondio = false
    BleeqUpWifiManager.turnOnWifi(device, clave) { ok, wifi, mensaje ->
        if (ok) {
            resultado = PuntoDeAccesoGafas(wifi.name, wifi.password)
        } else {
            Log.e(TAG, "el AP no encendio: $mensaje")
        }
        respondio = true
    }
    var esperado = 0L
    while (!respondio && esperado < ESPERA_DEL_AP_MILLIS) {
        delay(PASO_MILLIS)
        esperado += PASO_MILLIS
    }
    // Se restaura en cuanto el SDK ha contestado: cambiar el Locale afecta al
    // PROCESO entero —formatos de fecha y numero de toda la app— y no puede
    // quedarse puesto mas alla de la orden que lo necesitaba.
    java.util.Locale.setDefault(paisAnterior)
    return resultado
}

/**
 * Declara el pais de despliegue mientras dura la orden de encender el AP.
 *
 * Las gafas solo ofrecen su punto de acceso en 5745 MHz (canal 149) y el firmware
 * se niega si el pais del telefono no permite esa banda: con un movil en `ES`
 * responde `Open WiFi failed` y no hay video que traer. El SDK saca el pais de
 * `Locale.getDefault().country` y **no lo expone por parametro**, asi que este
 * apano es la unica via.
 *
 * El valor sale de `PAIS_PERIFERICOS` en `local.properties` (hoy `PH`, que es
 * donde se despliega) y no de una constante escondida: el pais correcto es el del
 * despliegue real, no el del terminal del que desarrolla. La sonda puede pisarlo
 * con `--es gafas_pais` para probar otros.
 */
private fun declararPais() {
    val pais = GafasSdkPuente.paisParaElPuntoDeAcceso
        ?: BuildConfig.PAIS_PERIFERICOS.takeIf { it.isNotEmpty() }
        ?: return
    Log.i(TAG, "declarando pais '$pais' para encender el AP (canal 149)")
    java.util.Locale.setDefault(
        java.util.Locale.Builder().setLanguage("en").setRegion(pais).build(),
    )
}

private fun GafasCommandRepository.apagarPuntoDeAcceso() {
    val device = aparatoParaWifi() ?: return
    BleeqUpWifiManager.turnOffWifi(device) { ok, mensaje ->
        Log.i(TAG, "AP apagado ok=$ok $mensaje")
    }
}

/** Lo que tarda el aparato en levantar su punto de acceso. */
private const val ESPERA_DEL_AP_MILLIS = 20_000L

/**
 * Tope de una tanda entera de descarga.
 *
 * Generoso a proposito: un video de 5 minutos son ~450 MB por WiFi de las gafas.
 * No esta para acelerar nada, sino para que la pantalla no se quede en "Retrieving"
 * indefinidamente el dia que el punto de acceso se caiga sin cerrar el socket.
 */
private const val TOPE_DE_LA_TANDA_MILLIS = 10 * 60 * 1000L
private const val PASO_MILLIS = 250L
