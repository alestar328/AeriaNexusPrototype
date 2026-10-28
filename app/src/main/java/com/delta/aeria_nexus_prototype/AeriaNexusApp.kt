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
        rescatarCapturasAMedias()
        // La red tactica se conecta desde el arranque: asi el telefono recibe
        // alertas SOS de otros agentes aunque nunca se abra la pestana Map.
        AppContainer.agoraRepository.ensureStarted()
        // Las gafas se conectan solas al telefono cuando el agente se las pone;
        // la app solo mira ese enlace, y tiene que mirarlo desde el arranque
        // para que el estado sea correcto la primera vez que se ve la barra.
        AppContainer.gafasRepository.vigilar()
        // Una subida que se corto porque el sistema mato el proceso se retoma por el
        // offset guardado. Al arrancar todavia no hay sesion con AeriaOne, asi que lo
        // normal es que esto no haga nada y salga al acreditarse la sesion; se deja
        // por si algun dia el token sobrevive al proceso.
        AppContainer.reanudarSubidas()
        AppContainer.evidenceUploader.reconcile()
        // El agente lee en la boveda tambien lo que transcribe la bodycam: se trae en
        // cuanto la camara avisa o, al reconectar, lo que diga su lista de subidas.
        AppContainer.transcripcionesDeBodycam.arrancar()
        vigilarSesionParaLaRadio()
    }

    /**
     * Lo que dejo a medias la app al morir, en este orden:
     *  1. Capturas en claro cuyo cifrado fallo. Va lo primero, antes de que las gafas
     *     o la camara escriban nada en captures/, y lo que cifra lo recogen los dos
     *     pasos siguientes.
     *  2. Transcripciones: antes que los proxies porque limpia lo que dejo la app al
     *     morir, y el rescate de los proxies escribe en esa misma carpeta.
     *  3. La copia ligera de los videos: el original sigue en claro esperandola.
     */
    private fun rescatarCapturasAMedias() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppContainer.localEvidenceRepository.sellarLasQueFallaron()
            AppContainer.transcripcionRepository.reanudar()
            AppContainer.proxyRepository.reanudar()
        }
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
