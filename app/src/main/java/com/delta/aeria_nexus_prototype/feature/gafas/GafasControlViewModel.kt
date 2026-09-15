package com.delta.aeria_nexus_prototype.feature.gafas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.DescargaDeGafas
import com.delta.aeria_nexus_prototype.data.EstadoDescarga
import com.delta.aeria_nexus_prototype.data.GafasCandidatas
import com.delta.aeria_nexus_prototype.data.GafasCommandRepository
import com.delta.aeria_nexus_prototype.data.GafasControlState
import com.delta.aeria_nexus_prototype.data.GafasRepository
import com.delta.aeria_nexus_prototype.data.GafasPendientesRepository
import com.delta.aeria_nexus_prototype.data.GafasState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GafasControlUiState(
    /** Enlace Bluetooth clasico, el mismo que pinta el indicador de la barra. */
    val enlace: GafasState = GafasState.DISCONNECTED,
    /** Canal de mando BLE, que solo existe mientras se esta en esta pantalla. */
    val control: GafasControlState = GafasControlState.DESCONECTADO,
    /** Ultima orden de grabacion dada, no lo que hacen las gafas de verdad. */
    val grabando: Boolean = false,
    /** Resolucion de video configurada, o null mientras no se sepa. */
    val resolucion: String? = null,
    /** Espacio libre en la tarjeta, ya formateado, o null mientras no se sepa. */
    val espacioLibre: String? = null,
    /** Respuesta corta de las gafas; se borra sola. */
    val aviso: String? = null,
    /** Videos que siguen en la tarjeta de las gafas y hay que traer. */
    val pendientes: Int = 0,
    /** En que punto va la descarga, si es que hay una. */
    val descarga: EstadoDescarga = EstadoDescarga.Parada,
    /** Las gafas de este agente, o null si todavia no las ha elegido. */
    val gafasElegidas: GafasCandidatas? = null,
    val selectorAbierto: Boolean = false,
    /** Lo emparejado en el telefono, para el selector. */
    val candidatas: List<GafasCandidatas> = emptyList(),
)

/**
 * Controlador remoto de las gafas BleeqUp Ranger: grabacion y foto por BLE.
 *
 * Usa dos repositorios porque son dos enlaces distintos con el mismo aparato:
 * [GafasRepository] observa el enlace de audio que trae el sistema (y de paso
 * sabe si hay permiso de Bluetooth), y [GafasCommandRepository] abre y cierra el
 * GATT propietario por el que viajan las ordenes.
 *
 * **El canal ya no es de esta pantalla.** Desde que la bodycam hace grabar a las
 * gafas (ver `ReleGafas`), el canal lo mantiene abierto
 * `BodycamService` durante todo el turno, asi que salir de aqui no lo cierra:
 * cerrarlo dejaria al oficial sin gafas en la siguiente emergencia. Esta pantalla
 * pasa a ser sobre todo un indicador.
 */
class GafasControlViewModel(
    private val mando: GafasCommandRepository,
    private val presencia: GafasRepository,
    private val pendientes: GafasPendientesRepository,
    private val descarga: DescargaDeGafas,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GafasControlUiState())
    val uiState: StateFlow<GafasControlUiState> = _uiState.asStateFlow()

    private var avisoJob: Job? = null

    init {
        viewModelScope.launch {
            presencia.state.collect { estado -> _uiState.update { it.copy(enlace = estado) } }
        }
        viewModelScope.launch {
            mando.estado.collect { estado -> _uiState.update { it.copy(control = estado) } }
        }
        viewModelScope.launch {
            mando.grabando.collect { grabando -> _uiState.update { it.copy(grabando = grabando) } }
        }
        viewModelScope.launch {
            mando.camara.collect { camara ->
                _uiState.update {
                    it.copy(
                        resolucion = if (camara.altoVideo > 0) {
                            "${camara.anchoVideo}x${camara.altoVideo}"
                        } else {
                            null
                        },
                        espacioLibre = formatearEspacio(camara.kilobytesLibres),
                    )
                }
            }
        }
        viewModelScope.launch {
            mando.mensajes.collect { mensaje -> mostrarAviso(mensaje) }
        }
        viewModelScope.launch {
            pendientes.pendientes.collect { lista ->
                _uiState.update { it.copy(pendientes = lista.size) }
            }
        }
        viewModelScope.launch {
            descarga.estado.collect { estado -> _uiState.update { it.copy(descarga = estado) } }
        }
        viewModelScope.launch {
            presencia.gafasElegidas.collect { gafas -> _uiState.update { it.copy(gafasElegidas = gafas) } }
        }
    }

    /**
     * Lo que se hace al entrar con permiso de Bluetooth: conectar con las gafas del
     * agente. Si todavia no hay, se eligen solas cuando no hay duda, y si la hay se
     * abre el selector.
     */
    fun alEntrar() {
        presencia.elegirSolasSiNoHayDuda()
        if (presencia.macElegida() == null) abrirSelector() else mando.conectar()
    }

    fun abrirSelector() {
        _uiState.update { it.copy(selectorAbierto = true, candidatas = presencia.emparejadas()) }
    }

    /** Tras emparejar en los ajustes de Android la lista no se entera sola. */
    fun releerCandidatas() {
        _uiState.update { it.copy(candidatas = presencia.emparejadas()) }
    }

    fun cerrarSelector() {
        _uiState.update { it.copy(selectorAbierto = false) }
    }

    /**
     * Cambia de gafas. Se suelta antes el canal con las anteriores: si no, el SDK
     * seguiria hablando con ellas y el bucle del turno no reconectaria nunca.
     */
    fun elegir(gafas: GafasCandidatas) {
        mando.desconectar()
        presencia.elegirGafas(gafas)
        _uiState.update { it.copy(selectorAbierto = false) }
        mando.conectar()
    }

    /**
     * Trae a la boveda lo que las gafas tienen pendiente.
     *
     * La clave del punto de acceso se genera aqui y no se le enseña a nadie: es de
     * usar y tirar, dura lo que dura la descarga y el oficial no tiene por que
     * saberla. El SDK exige exactamente 8 letras o digitos.
     */
    fun traerVideos() {
        viewModelScope.launch { descarga.traerPendientes(claveDeUsarYTirar()) }
    }

    fun olvidarResultadoDeDescarga() = descarga.olvidarResultado()

    private fun claveDeUsarYTirar(): String =
        (1..8).map { LETRAS_DE_CLAVE.random() }.joinToString("")

    fun conectar() = mando.conectar()

    fun desconectar() = mando.desconectar()

    fun alternarGrabacion() {
        if (_uiState.value.grabando) mando.pararGrabacion() else mando.iniciarGrabacion()
    }

    fun hacerFoto() = mando.hacerFoto()

    fun tienePermisoBluetooth(): Boolean = presencia.tienePermisoBluetooth()

    /** Tras conceder el permiso hay que releer: los avisos perdidos no vuelven. */
    fun alConcederBluetooth() {
        presencia.refrescar()
        alEntrar()
    }

    private fun mostrarAviso(mensaje: String) {
        avisoJob?.cancel()
        _uiState.update { it.copy(aviso = mensaje) }
        avisoJob = viewModelScope.launch {
            delay(AVISO_MILLIS)
            _uiState.update { it.copy(aviso = null) }
        }
    }

    /**
     * El SDK devuelve el espacio en **kilobytes**, no en bytes.
     *
     * Medido el 2026-09-13 contra las gafas: `total=26540012 libres=19679128`, que
     * en KB son 25,3 GB de tarjeta y 18,8 GB libres. Tratandolo como bytes salia
     * "0.0 GB libres" con la tarjeta a tres cuartos de vacia.
     */
    private fun formatearEspacio(kilobytes: Long): String? {
        if (kilobytes < 0) return null
        val gigas = kilobytes.toDouble() / (1024 * 1024)
        return String.format(java.util.Locale.US, "%.1f GB", gigas)
    }

    private companion object {
        /** Lo que dura en pantalla la respuesta de las gafas. */
        const val AVISO_MILLIS = 3_000L

        /** Sin enes ni acentos: el SSID y la clave viajan por un protocolo ASCII. */
        const val LETRAS_DE_CLAVE = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}
