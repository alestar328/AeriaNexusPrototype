package com.delta.aeria_nexus_prototype.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delta.aeria_nexus_prototype.BuildConfig
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.feature.enrollment.EnrollmentScreen
import com.delta.aeria_nexus_prototype.feature.enrollment.EnrollmentViewModel
import com.delta.aeria_nexus_prototype.feature.enrollment.PinSetupScreen
import com.delta.aeria_nexus_prototype.feature.enrollment.PinSetupViewModel
import com.delta.aeria_nexus_prototype.feature.lock.LockScreen
import com.delta.aeria_nexus_prototype.feature.lock.LockViewModel
import com.delta.aeria_nexus_prototype.feature.lock.TrustBlockedScreen

/**
 * Puerta de entrada de la app.
 *
 * Es lo primero que monta MainActivity, por delante de [AppNavHost]. La app de
 * siempre (operaciones, mapa, incidentes, perfil) solo existe dentro de la rama
 * ACTIVE; el resto de estados son pantallas completas sin navegacion ni barras,
 * porque en ellos no hay nada que navegar.
 *
 * Este es el cambio estructural que trae el modelo IAM: hasta hoy arrancar la app
 * era estar dentro. A partir de aqui el arranque es una decision, y mientras no
 * exista el backend que la toma, la toma IdentityRepository con las mismas
 * reglas y la misma forma.
 */
@Composable
fun TrustGate() {
    val identityRepository = AppContainer.identityRepository
    val status by identityRepository.status.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (status.state) {
            // OFFLINE_GRANTED entra por la misma puerta que ACTIVE: sin cobertura
            // se sigue trabajando. Lo que cambia es que hay funciones prohibidas
            // (§12) y una caducidad; eso se senalizara en la barra superior
            // cuando se implemente el indicador de sesion.
            TrustState.ACTIVE, TrustState.OFFLINE_GRANTED -> AppNavHost()

            TrustState.LOCKED, TrustState.SESSION_EXPIRED -> LockScreen(
                viewModel = viewModel { LockViewModel(identityRepository, AppContainer.retoRepository) },
            )

            // El asistente de alta lleva su propio progreso, asi que las dos ramas
            // muestran la misma pantalla: ENROLLING es un estado del terminal, no
            // una pantalla distinta.
            TrustState.NOT_PROVISIONED, TrustState.ENROLLING -> EnrollmentScreen(
                viewModel = viewModel {
                    EnrollmentViewModel(
                        enrollment = AppContainer.enrollmentRepository,
                        credential = AppContainer.credentialRepository,
                        identity = identityRepository,
                        retos = AppContainer.retoRepository,
                    )
                },
            )

            // Ultimo tramo del alta: el agente elige su PIN. Pantalla aparte
            // porque es un teclado y no una lista de pasos que ocurren solos.
            TrustState.PIN_SETUP -> PinSetupScreen(
                viewModel = viewModel {
                    PinSetupViewModel(
                        pinLocal = AppContainer.pinLocal,
                        credential = AppContainer.credentialRepository,
                        identity = identityRepository,
                    )
                },
            )

            TrustState.BLOCKED -> TrustBlockedScreen(
                // Un BLOCKED sin motivo es un error de programacion; se pinta el
                // corte mas amplio en vez de dejar la pantalla en blanco.
                reason = status.blockReason ?: TrustBlockReason.DEVICE_REVOKED,
                identity = status.identity,
                // Reintentar solo tiene sentido en el corte por falta de cobertura:
                // devuelve a la pantalla de PIN, que es donde se revalidara todo
                // cuando exista el backend.
                onRetry = { identityRepository.lock() },
            )
        }

        // No mira BuildConfig.DEBUG sino su propia bandera: en release vale false
        // salvo que se pida a proposito con SIMULADOR_CONFIANZA_EN_RELEASE=true, que
        // es como se genera la APK de pruebas para el manager. Una release con esto
        // puesto NO exige alta ni PIN y no puede llegar a campo.
        if (BuildConfig.SIMULADOR_CONFIANZA) {
            TrustStateSimulator(
                status = status,
                onSeleccionar = { estado, motivo ->
                    // Volver a "sin dar de alta" tiene que destruir la clave, o el
                    // alta siguiente reutilizaria la anterior y no probaria nada.
                    if (estado == TrustState.NOT_PROVISIONED) {
                        AppContainer.destruirIdentidadLocal()
                    }
                    identityRepository.forzarEstado(estado, motivo)
                },
                // Pegado al borde izquierdo y centrado: es el unico sitio de la
                // app donde no hay nada que tapar en ninguna pantalla.
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}
