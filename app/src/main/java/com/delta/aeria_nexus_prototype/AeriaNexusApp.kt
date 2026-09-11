package com.delta.aeria_nexus_prototype

import android.app.Application
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Punto de arranque de la app: configura Mapbox y el contenedor de dependencias. */
class AeriaNexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        AppContainer.init(this)
        // La red tactica se conecta desde el arranque: asi el telefono recibe
        // alertas SOS de otros agentes aunque nunca se abra la pestana Map.
        AppContainer.agoraRepository.ensureStarted()
        // Las gafas se conectan solas al telefono cuando el agente se las pone;
        // la app solo mira ese enlace, y tiene que mirarlo desde el arranque
        // para que el estado sea correcto la primera vez que se ve la barra.
        AppContainer.gafasRepository.vigilar()
        // Una subida que se corto porque el sistema mato el proceso se retoma aqui,
        // por el offset guardado. Es el equivalente movil de lo que en la bodycam
        // hace BootReceiver.
        AppContainer.evidenceUploader.resumePending()
        AppContainer.evidenceUploader.reconcile()
        // Lo mismo para la copia ligera de los videos: si el proceso murio mientras se
        // hacia, el original sigue en claro esperandola y hay que terminarla.
        AppContainer.proxyRepository.reanudar()
        vigilarSesionParaLaRadio()
    }

    /**
     * Levanta y retira la radio tactica con la sesion del agente.
     *
     * Entrar al canal no bastaba para oir el PTT con la app cerrada: sin un
     * foreground service el proceso queda en cache y el sistema lo mata cuando le
     * conviene. RadioService es lo que lo impide, y este colector decide cuando
     * tiene derecho a estar en marcha.
     *
     * Solo con sesion abierta (ACTIVE, o OFFLINE_GRANTED cuando se opera sin
     * cobertura con el permiso firmado). Un telefono en el canal tactico en nombre
     * de un agente que no ha metido su PIN es lo mismo que prohibe el workflow 34
     * cuando desata la bodycam al cerrar sesion: el aparato no puede seguir
     * operando por alguien que ya no esta de servicio.
     */
    private fun vigilarSesionParaLaRadio() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            AppContainer.identityRepository.status
                .map { it.state == TrustState.ACTIVE || it.state == TrustState.OFFLINE_GRANTED }
                .distinctUntilChanged()
                .collect { enServicio ->
                    if (enServicio) RadioService.start(this@AeriaNexusApp)
                    else RadioService.stop(this@AeriaNexusApp)
                }
        }
    }
}
