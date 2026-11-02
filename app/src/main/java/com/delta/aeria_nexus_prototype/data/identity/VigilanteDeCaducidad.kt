package com.delta.aeria_nexus_prototype.data.identity

import android.util.Log
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "AeriaCaducidad"

/**
 * Aplica [PoliticaDeCaducidad] mientras la app vive (workflow 53).
 *
 * Mira la fecha cada minuto, cada vez que empieza o termina una grabacion y cada
 * vez que cambia el estado de confianza: asi la gracia acaba en cuanto se para la
 * ultima grabacion, y un desbloqueo con la credencial vencida se corta en el acto.
 * Solo actua con el telefono ya en servicio (bloqueado, activo o con la sesion
 * caducada); durante el alta no hay credencial que juzgar.
 *
 * [grabando] es cualquier captura en marcha: camara y audio del telefono, SOS,
 * bodycam y gafas. Lo junta AppContainer, que es quien conoce esos repositorios.
 */
class VigilanteDeCaducidad(
    private val identity: IdentityRepository,
    private val auditoria: AuditoriaLocal,
    private val grabando: Flow<Boolean>,
) {

    // En el hilo principal: el bloqueo pasa por lock(), que deshace las ataduras y
    // sella la boveda, y a lock() se le llama siempre desde la interfaz.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _estado = MutableStateFlow(EstadoDeCaducidad())
    val estado: StateFlow<EstadoDeCaducidad> = _estado.asStateFlow()

    fun arrancar() {
        scope.launch {
            combine(
                grabando,
                identity.status.map { it.state }.distinctUntilChanged(),
                cadaMinuto(),
            ) { grabandoAhora, _, _ -> grabandoAhora }
                .collect { revisar(it) }
        }
    }

    private fun revisar(grabandoAhora: Boolean) {
        if (identity.status.value.state !in EN_SERVICIO) {
            _estado.value = EstadoDeCaducidad()
            return
        }
        val caducaEn = primeraCaducidad()
        val fase = PoliticaDeCaducidad.evaluar(caducaEn, System.currentTimeMillis(), grabandoAhora)
        val anterior = _estado.value.fase
        _estado.value = EstadoDeCaducidad(fase, caducaEn)
        if (fase == anterior) return

        val cuando = listOf("expired_at" to caducaEn?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
        when (fase) {
            FaseDeCaducidad.EN_GRACIA -> {
                Log.w(TAG, "credencial caducada con una grabacion en curso: se deja terminar")
                auditoria.registrar(TipoEvento.CREDENCIAL_EN_GRACIA, cuando)
            }
            FaseDeCaducidad.CADUCADA -> {
                Log.w(TAG, "credencial caducada: el telefono queda fuera de servicio")
                auditoria.registrar(TipoEvento.CREDENCIAL_CADUCADA, cuando)
                identity.bloquearPorCredencialCaducada()
            }
            FaseDeCaducidad.VIGENTE, FaseDeCaducidad.POR_CADUCAR -> Unit
        }
    }

    /**
     * El primero que caduque manda: con cualquiera de los dos vencido no hay sesion.
     * Sin certificados (piloto con el simulador, sin alta) no hay nada que juzgar. Un
     * fallo del Keystore tampoco bloquea: esta corrutina no tiene quien la recoja y
     * tumbaria la app, y no saber la fecha no es lo mismo que saber que ha vencido.
     */
    private fun primeraCaducidad(): Long? = listOfNotNull(
        caducidadDe(ClaveEnKeystore.terminal),
        caducidadDe(ClaveEnKeystore.agente),
    ).minOrNull()

    private fun caducidadDe(clave: ClaveEnKeystore): Long? =
        runCatching { clave.certificado()?.notAfter?.time }
            .onFailure { Log.w(TAG, "no se pudo leer el certificado: ${it.message}") }
            .getOrNull()

    private fun cadaMinuto() = flow {
        while (true) {
            emit(Unit)
            delay(REVISION_MILLIS)
        }
    }

    private companion object {
        const val REVISION_MILLIS = 60_000L
        val EN_SERVICIO = setOf(TrustState.LOCKED, TrustState.ACTIVE, TrustState.SESSION_EXPIRED)
    }
}
