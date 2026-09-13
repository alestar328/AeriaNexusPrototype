package com.delta.aeria_nexus_prototype.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "ReleGafas"

/** Que paso con las gafas la ultima vez que la bodycam empezo a grabar. */
enum class GafasEnLaGrabacion {
    /** La bodycam no ha grabado todavia en esta sesion. */
    SIN_GRABAR,

    /** Se les mando grabar y aceptaron. */
    GRABANDO,

    /** Se les mando parar. */
    PARADAS,

    /** Se les mando grabar y no obedecieron: canal caido, apagadas o sin responder. */
    NO_DISPONIBLES,
}

/**
 * Hace que las gafas graben **cuando graba la bodycam**, y avisa al oficial cuando
 * queda video suyo por traer.
 *
 * El manager pidio que el oficial no maneje los perifericos desde la app: en campo
 * no hay tiempo. Solo pulsa los botones de la camara que lleva en el pecho. Como
 * las gafas y la bodycam no pueden hablarse entre ellas —las gafas solo entienden
 * el GATT propietario del fabricante, y lo unico que lo habla es su SDK de Android,
 * que corre aqui— el telefono hace de **rele invisible**.
 *
 * ## Los dos botones, y por que se miran juntos
 *
 * Valen los dos: el **134** (grabacion normal) y el **133** (SOS). Se combinan
 * `isRecording` y `isStreaming` en una sola condicion en vez de atender a cada uno
 * por su lado, y no es un capricho: **la camara de la W1 es exclusiva**, asi que
 * arrancar el livestream del SOS apaga su grabacion local. Mirando solo
 * `isRecording`, un SOS encadenado a una grabacion le habria mandado PARAR a las
 * gafas justo al empezar la emergencia.
 *
 * Se escuchan los estados y no `buttonEvents` porque el boton se pierde si el
 * enlace tiene un microcorte, mientras que a `isRecording`/`isStreaming` los
 * alimentan tanto el boton como el STATUS que la bodycam manda cada 5 segundos.
 *
 * ## Lo que este rele NO hace
 *
 * No toca el SOS ni la grabacion de la bodycam. Si las gafas fallan, la bodycam
 * sigue su curso igual: son una camara de apoyo. Pero **si el fallo ocurre en un
 * SOS se avisa al oficial con sonido y vibracion**: el telefono va en el bolsillo
 * y esa es la unica forma de que no siga creyendo que lleva dos camaras grabando.
 */
class ReleGafas(
    private val bodycam: BodycamRepository,
    private val gafas: GafasCommandRepository,
    private val pendientes: GafasPendientesRepository,
    private val aviso: AvisoDeGafas,
) {

    private val _estado = MutableStateFlow(GafasEnLaGrabacion.SIN_GRABAR)
    val estado: StateFlow<GafasEnLaGrabacion> = _estado.asStateFlow()

    /**
     * Empieza a seguir a la bodycam.
     *
     * El [alcance] tiene que vivir tanto como el enlace, asi que lo pone
     * [com.delta.aeria_nexus_prototype.BodycamService], que es quien mantiene el
     * proceso vivo en segundo plano.
     */
    fun vigilar(alcance: CoroutineScope) {
        alcance.launch {
            combine(bodycam.isRecording, bodycam.isStreaming) { grabando, transmitiendo ->
                grabando || transmitiendo
            }
                .distinctUntilChanged()
                .collect { laBodycamEstaCapturando ->
                    // El SOS se distingue aqui y no dentro: si las gafas fallan, un
                    // SOS avisa al oficial y una grabacion normal no. No es lo mismo
                    // perderse el apoyo de una camara en una emergencia que en una
                    // grabacion rutinaria.
                    if (laBodycamEstaCapturando) {
                        alEmpezar(esSos = bodycam.isStreaming.value)
                    } else {
                        alTerminar()
                    }
                }
        }
        alcance.launch {
            gafas.videosCerrados.collect { nombre -> alCerrarseUnVideo(nombre) }
        }
    }

    private fun alEmpezar(esSos: Boolean) {
        Log.i(TAG, "la bodycam esta capturando: se manda grabar a las gafas")
        gafas.iniciarGrabacion { obedecieron ->
            _estado.value = if (obedecieron) {
                GafasEnLaGrabacion.GRABANDO
            } else {
                GafasEnLaGrabacion.NO_DISPONIBLES
            }
            if (obedecieron) {
                Log.i(TAG, "las gafas estan grabando")
                return@iniciarGrabacion
            }
            Log.e(TAG, "GRABACION SIN GAFAS: no aceptaron la orden")
            // En un SOS el oficial tiene que enterarse EN EL MOMENTO: creera que
            // lleva dos camaras y llevara una. Fuera del SOS basta el log, porque
            // el video se puede repetir y un aviso sonoro por cada grabacion
            // rutinaria acabaria ignorandose.
            if (esSos) aviso.avisarSosSinGafas()
        }
    }

    private fun alTerminar() {
        // Sin grabacion previa no hay nada que parar.
        if (_estado.value == GafasEnLaGrabacion.SIN_GRABAR) return
        Log.i(TAG, "la bodycam dejo de capturar: se manda parar a las gafas")
        gafas.pararGrabacion()
        // Una grabacion que se quedo sin gafas lo sigue estando cuando termina:
        // pisarlo con PARADAS borraria el unico rastro del fallo.
        if (_estado.value != GafasEnLaGrabacion.NO_DISPONIBLES) {
            _estado.value = GafasEnLaGrabacion.PARADAS
        }
    }

    /**
     * El video ya esta cerrado en la tarjeta de las gafas, y ahi se queda.
     *
     * Se anota y se avisa al oficial, que es la unica forma de que se entere: el
     * fichero no viaja solo, hay que ir a por el por el WiFi de las gafas. Se
     * atiende cualquier video, tambien los que arranco el agente con el boton de
     * las gafas: si esta en la tarjeta, hay que traerlo.
     */
    private fun alCerrarseUnVideo(nombre: String) {
        pendientes.anadir(nombre)
        aviso.avisarDeVideosPendientes(pendientes.pendientes.value.size)
    }
}
