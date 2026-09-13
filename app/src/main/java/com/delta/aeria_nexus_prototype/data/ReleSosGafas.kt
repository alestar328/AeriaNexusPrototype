package com.delta.aeria_nexus_prototype.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ReleSosGafas"

/** Que paso con las gafas en el ultimo SOS. */
enum class GafasEnElSos {
    /** No ha habido ningun SOS todavia en esta sesion. */
    SIN_SOS,

    /** Se les mando grabar y aceptaron. */
    GRABANDO,

    /** Se les mando parar tras el SOS. */
    PARADAS,

    /** Hubo SOS pero las gafas no obedecieron: canal caido, apagadas o sin responder. */
    NO_DISPONIBLES,
}

/**
 * Hace que las gafas graben cuando el oficial pulsa el **SOS de la bodycam**.
 *
 * El manager pidio que el oficial no tenga que manejar los perifericos desde la
 * app: en campo no hay tiempo. Como las gafas y la bodycam no pueden hablarse
 * entre ellas —las gafas solo entienden el GATT propietario del fabricante, y lo
 * unico que lo habla es su SDK de Android, que corre aqui— el telefono hace de
 * **rele invisible**: oye el boton de la bodycam por RFCOMM y da la orden a las
 * gafas por BLE. El oficial no toca nada y el movil se queda en el bolsillo.
 *
 * ## Por que se escucha [BodycamRepository.isStreaming] y no los botones crudos
 *
 * `buttonEvents` da el aviso mas rapido, pero se pierde si el enlace Bluetooth
 * tiene un microcorte justo en ese instante. `isStreaming` lo alimentan las dos
 * vias: el boton `BTN_STREAM_START` lo adelanta al momento, y el STATUS que la
 * bodycam manda cada 5 segundos lo corrige si el boton se perdio. Para algo que
 * solo ocurre en una emergencia, importa mas no perderselo que ganar un segundo.
 *
 * ## Lo que este rele NO hace
 *
 * No toca el SOS. Si las gafas fallan, el SOS sigue su curso igual: la emergencia
 * es lo primero y las gafas son una camara de apoyo. Lo unico que queda del fallo
 * es [estado], porque el oficial no va a estar mirando la pantalla para verlo.
 *
 * Tampoco reacciona al boton 134 (grabacion comun de la bodycam). Ese boton
 * conmuta un buffer que a menudo ya esta corriendo —medido el 2026-09-13: el
 * primer disparo devolvio `BTN_REC_STOP`— y atarle las gafas significaria
 * arrancarlas y pararlas todo el turno.
 */
class ReleSosGafas(
    private val bodycam: BodycamRepository,
    private val gafas: GafasCommandRepository,
) {

    private val _estado = MutableStateFlow(GafasEnElSos.SIN_SOS)
    val estado: StateFlow<GafasEnElSos> = _estado.asStateFlow()

    /**
     * Empieza a vigilar el SOS de la bodycam.
     *
     * El [alcance] tiene que vivir tanto como el enlace con la bodycam, asi que lo
     * pone [com.delta.aeria_nexus_prototype.BodycamService], que es quien mantiene
     * el proceso vivo en segundo plano.
     */
    fun vigilar(alcance: CoroutineScope) {
        alcance.launch {
            // El primer valor que emite el StateFlow es el actual, no un cambio. Si
            // ya estuviera transmitiendo al arrancar el servicio, esto manda grabar,
            // que es justo lo que se quiere: el SOS ya esta en marcha.
            bodycam.isStreaming.collect { transmitiendo ->
                if (transmitiendo) alEmpezarElSos() else alTerminarElSos()
            }
        }
    }

    private fun alEmpezarElSos() {
        Log.i(TAG, "SOS de la bodycam: se manda grabar a las gafas")
        gafas.iniciarGrabacion { obedecieron ->
            _estado.value = if (obedecieron) GafasEnElSos.GRABANDO else GafasEnElSos.NO_DISPONIBLES
            if (obedecieron) {
                Log.i(TAG, "las gafas estan grabando el SOS")
            } else {
                // Esto es lo unico que quedara del fallo: nadie va a estar mirando.
                Log.e(TAG, "SOS SIN GAFAS: no aceptaron la orden de grabar")
            }
        }
    }

    private fun alTerminarElSos() {
        // Sin SOS previo no hay nada que parar.
        if (_estado.value == GafasEnElSos.SIN_SOS) return
        Log.i(TAG, "fin del SOS: se manda parar a las gafas")
        gafas.pararGrabacion()
        // Un SOS que se quedo sin gafas sigue siendo un SOS sin gafas cuando
        // termina: pisarlo con PARADAS borraria el unico rastro del fallo.
        if (_estado.value != GafasEnElSos.NO_DISPONIBLES) {
            _estado.value = GafasEnElSos.PARADAS
        }
    }
}
