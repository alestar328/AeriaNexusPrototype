package com.delta.aeria_nexus_prototype.feature.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.PinLocal
import com.delta.aeria_nexus_prototype.data.identity.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PinSetupUiState(
    /** Agente al que va a pertenecer el PIN, tomado de su certificado. */
    val userId: String? = null,
    val digitos: String = "",
    /** false = eligiendo el PIN; true = repitiendolo para confirmar. */
    val confirmando: Boolean = false,
    val error: String? = null,
)

/**
 * Alta del PIN del agente (workflow 4, paso 13).
 *
 * Es el ultimo tramo del alta y el unico que depende de una persona: hasta aqui
 * —claves, peticiones, certificados— no ha habido nada que decidir.
 *
 * El PIN no sale nunca de aqui. Lo que se teclea vive en este ViewModel el tiempo
 * de confirmarlo y se entrega a [PinLocal], que guarda un verificador y no el PIN.
 */
class PinSetupViewModel(
    private val pinLocal: PinLocal,
    private val credential: CredentialRepository,
    private val identity: IdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinSetupUiState(userId = credential.agenteProvisionado()))
    val uiState: StateFlow<PinSetupUiState> = _uiState.asStateFlow()

    /** El primer PIN tecleado, mientras se espera la confirmacion. */
    private var pinPropuesto: String? = null

    /** Cierto durante la pausa del ultimo digito, para no encadenar dos resoluciones. */
    private var resolviendo = false

    fun escribirDigito(digito: Char) {
        val estado = _uiState.value
        if (resolviendo || estado.digitos.length >= IdentityRepository.PIN_LENGTH) return

        val escrito = estado.digitos + digito
        _uiState.update { it.copy(digitos = escrito, error = null) }
        // Se resuelve al completar los digitos, sin boton de OK: el mismo gesto que
        // en la pantalla de bloqueo, para que el PIN se elija como se va a teclear.
        if (escrito.length == IdentityRepository.PIN_LENGTH) resolverTrasPintarElUltimo(escrito)
    }

    fun borrarDigito() {
        if (resolviendo) return
        _uiState.update { it.copy(digitos = it.digitos.dropLast(1), error = null) }
    }

    /**
     * Resuelve fuera del hilo de la interfaz y sin comerse el ultimo fotograma.
     *
     * Guardar el PIN deriva la clave con 210.000 iteraciones de PBKDF2 y eso cuesta
     * cientos de milisegundos a proposito, asi que no puede correr en el hilo de la
     * interfaz. Y como rechazar por politica si es instantaneo, se garantiza un
     * minimo visible: sin el, el sexto digito se escribia y se borraba en la misma
     * pasada y Compose no llegaba a pintar nunca ese punto. Con guantes, eso es no
     * saber si el ultimo toque entro.
     */
    private fun resolverTrasPintarElUltimo(escrito: String) {
        resolviendo = true
        viewModelScope.launch {
            val inicio = System.currentTimeMillis()
            // Tanto establecer el PIN como entrar derivan la clave con PBKDF2, asi
            // que las dos cosas van fuera del hilo de la interfaz.
            val siguiente = withContext(Dispatchers.Default) {
                val paso = resolver(escrito)
                if (paso.establecido) abrirSesion(escrito)
                paso
            }
            val transcurrido = System.currentTimeMillis() - inicio
            if (transcurrido < MINIMO_VISIBLE_MILLIS) delay(MINIMO_VISIBLE_MILLIS - transcurrido)
            aplicar(siguiente)
            resolviendo = false
        }
    }

    /**
     * Primera vuelta: se comprueba la politica y se pide repetirlo. Segunda: si
     * coincide se establece, y si no se vuelve a empezar del todo.
     *
     * Se pide dos veces porque el PIN no se puede recuperar: un digito mal al
     * crearlo deja al agente fuera de su propio terminal en el siguiente arranque,
     * y la recuperacion (workflow 6) no existe todavia.
     *
     * Devuelve lo que hay que pintar en vez de pintarlo: esta funcion corre fuera
     * del hilo de la interfaz.
     */
    private fun resolver(escrito: String): SiguientePaso {
        val propuesto = pinPropuesto
        if (propuesto == null) {
            val motivo = PoliticaPin.motivoDeRechazo(escrito)
            if (motivo != null) return SiguientePaso(error = motivo)
            pinPropuesto = escrito
            return SiguientePaso(confirmando = true)
        }

        pinPropuesto = null
        if (escrito != propuesto) {
            return SiguientePaso(error = "PINs did not match. Start again.")
        }
        if (!pinLocal.establecer(escrito)) {
            return SiguientePaso(error = "Could not store the PIN on this phone.")
        }
        return SiguientePaso(establecido = true)
    }

    /**
     * Con el PIN ya establecido, se entra directamente.
     *
     * El agente acaba de teclearlo dos veces: pedirselo una tercera para abrir la
     * app no anade seguridad, solo tres toques mas al final de un alta que ya es
     * larga. La siguiente vez que arranque si vera la pantalla de bloqueo.
     *
     * Se entra llamando a [IdentityRepository.unlock] y no a un atajo, aunque eso
     * cueste una derivacion mas: asi **hay un solo camino para abrir sesion**, y
     * ese camino siempre pasa por el verificador, autoriza la clave del agente y
     * firma el reto del backend si lo hay. Un segundo camino que abriese sesion
     * sin verificar nada seria justo la clase de atajo que despues se cuela en
     * produccion.
     */
    private fun abrirSesion(pin: String) {
        if (identity.unlock(pin) != UnlockResult.OK) {
            // No deberia ocurrir: el verificador se acaba de escribir con este PIN.
            // Si ocurre, se deja el terminal bloqueado, que es el estado seguro.
            identity.pinCompletado()
        }
    }

    private fun aplicar(paso: SiguientePaso) {
        _uiState.update {
            it.copy(digitos = "", confirmando = paso.confirmando, error = paso.error)
        }
    }

    /** Lo que toca hacer con la pantalla despues de resolver. */
    private data class SiguientePaso(
        val error: String? = null,
        val confirmando: Boolean = false,
        val establecido: Boolean = false,
    )

    private companion object {
        /**
         * Lo justo para que se vea el ultimo punto. Mas seria una app lenta y
         * menos no se percibe: se eligio mirandolo en el terminal, no en teoria.
         */
        const val MINIMO_VISIBLE_MILLIS = 150L
    }
}

/**
 * Politica minima del PIN.
 *
 * **Es propuesta nuestra.** El documento de ciberseguridad no fija ninguna, y la
 * politica de credenciales es uno de los artefactos de diseno que reconoce
 * pendientes. Se rechaza lo que un atacante probaria primero y nada mas: reglas
 * mas duras, con guantes y de noche, acaban en un PIN apuntado en la funda del
 * telefono, que es peor que un PIN flojo.
 */
internal object PoliticaPin {

    fun motivoDeRechazo(pin: String): String? {
        if (pin.length != IdentityRepository.PIN_LENGTH) return "The PIN must have six digits."
        if (pin.all { it == pin.first() }) return "Do not use the same digit six times."
        if (esConsecutivo(pin)) return "Do not use consecutive digits."
        return null
    }

    /** Tanto 123456 como 654321: las dos se prueban igual de pronto. */
    private fun esConsecutivo(pin: String): Boolean {
        val ascendente = pin.zipWithNext().all { (a, b) -> b - a == 1 }
        val descendente = pin.zipWithNext().all { (a, b) -> a - b == 1 }
        return ascendente || descendente
    }
}
