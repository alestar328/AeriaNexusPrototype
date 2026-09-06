package com.delta.aeria_nexus_prototype.feature.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.PropositoDelReto
import com.delta.aeria_nexus_prototype.data.identity.ProvisionedIdentity
import com.delta.aeria_nexus_prototype.data.identity.RetoRepository
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.data.identity.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LockUiState(
    val identity: ProvisionedIdentity? = null,
    /** Distingue "arranco el turno" de "se me corto la sesion a medias". */
    val sessionExpired: Boolean = false,
    /** Digitos tecleados. Nunca se muestran: la pantalla solo pinta cuantos hay. */
    val pin: String = "",
    val attemptsLeft: Int = IdentityRepository.MAX_ATTEMPTS,
    /** Segundos que faltan para poder volver a intentarlo. 0 = teclado libre. */
    val lockoutSeconds: Int = 0,
    /** Quien emitio el reto que espera firma, si hay uno. */
    val emisorDelRetoPendiente: String? = null,
    val mensajeError: String? = null,
)

/**
 * Pantalla de bloqueo (workflow 27, pasos 4 a 7).
 *
 * El agente no teclea su usuario: la app ya sabe quien es porque la identidad
 * quedo atada a esta instalacion en el alta. Lo unico que se pide es el PIN, y su
 * unico papel es autorizar el uso de la clave protegida, no viajar a ningun sitio.
 */
class LockViewModel(
    private val identity: IdentityRepository,
    private val retos: RetoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        // El reto se mira al construir la pantalla y no en cada recomposicion: es
        // una lectura de disco y el estado no cambia mientras se teclea el PIN.
        LockUiState(emisorDelRetoPendiente = retos.pendiente(PropositoDelReto.LOGIN)?.emisor),
    )
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            identity.status.collect { status ->
                _uiState.update {
                    it.copy(
                        identity = status.identity,
                        sessionExpired = status.state == TrustState.SESSION_EXPIRED,
                        attemptsLeft = status.attemptsLeft,
                    )
                }
            }
        }
        // Cuenta atras del bloqueo temporal. El agente tiene que ver que el tiempo
        // corre: un teclado muerto sin explicacion se percibe como app rota, y lo
        // siguiente es reinstalar, que es justo lo que no queremos que haga.
        //
        // collectLatest reinicia el bucle en cada cambio de estado, asi que el
        // reloj arranca en el mismo instante en que salta el bloqueo y no gasta
        // nada mientras no hay bloqueo (que es casi siempre).
        viewModelScope.launch {
            identity.status.collectLatest { status ->
                while (status.lockedOutUntil > System.currentTimeMillis()) {
                    val restante = status.lockedOutUntil - System.currentTimeMillis()
                    _uiState.update { it.copy(lockoutSeconds = (restante / 1_000).toInt() + 1) }
                    delay(1_000)
                }
                _uiState.update { it.copy(lockoutSeconds = 0) }
            }
        }
    }

    /** Cierto durante la pausa del ultimo digito, para no encadenar dos intentos. */
    private var comprobando = false

    fun escribirDigito(digito: Char) {
        val estado = _uiState.value
        if (comprobando || estado.lockoutSeconds > 0) return
        if (estado.pin.length >= IdentityRepository.PIN_LENGTH) return

        val nuevo = estado.pin + digito
        _uiState.update { it.copy(pin = nuevo, mensajeError = null) }
        // Se valida solo al completar los digitos: con guantes, un boton de OK
        // extra es un toque de mas en cada desbloqueo del turno.
        if (nuevo.length == IdentityRepository.PIN_LENGTH) {
            comprobando = true
            viewModelScope.launch {
                comprobar(nuevo)
                comprobando = false
            }
        }
    }

    fun borrarDigito() {
        if (comprobando) return
        _uiState.update { it.copy(pin = it.pin.dropLast(1), mensajeError = null) }
    }

    /**
     * Comprueba el PIN fuera del hilo de la interfaz.
     *
     * Derivar la clave del PIN cuesta cientos de milisegundos A PROPOSITO: son las
     * 210.000 iteraciones de PBKDF2 que hacen cara la fuerza bruta contra seis
     * digitos. Lo que no puede pasar es que ese rato lo pague el hilo de la
     * interfaz, que era lo que ocurria: la pantalla se quedaba congelada y el
     * teclado ni siquiera pintaba el ultimo punto.
     *
     * Como el trabajo real ya tarda, no hace falta pausa artificial para que se vea
     * el sexto digito; solo se garantiza un minimo por si algun dia el terminal es
     * lo bastante rapido como para que la respuesta vuelva en el mismo fotograma.
     */
    private suspend fun comprobar(pin: String) {
        val inicio = System.currentTimeMillis()
        val resultado = withContext(Dispatchers.Default) { identity.unlock(pin) }
        val transcurrido = System.currentTimeMillis() - inicio
        if (transcurrido < MINIMO_VISIBLE_MILLIS) delay(MINIMO_VISIBLE_MILLIS - transcurrido)

        when (resultado) {
            // El cambio de estado del repositorio se lleva la pantalla por delante.
            // Aun asi hay que vaciar los digitos: el ViewModel sobrevive a la
            // sesion, y al volver a bloquear se verian los seis puntos llenos.
            UnlockResult.OK -> _uiState.update { it.copy(pin = "", mensajeError = null) }

            UnlockResult.WRONG_PIN -> _uiState.update {
                it.copy(pin = "", mensajeError = "Wrong PIN")
            }

            UnlockResult.LOCKED_OUT -> _uiState.update {
                it.copy(pin = "", mensajeError = null)
            }
        }
    }

    private companion object {
        /** Suelo para que el sexto punto llegue a verse aunque la respuesta sea inmediata. */
        const val MINIMO_VISIBLE_MILLIS = 150L
    }
}
