package com.delta.aeria_nexus_prototype

import android.app.Application
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.mapbox.common.MapboxOptions

/** Punto de arranque de la app: configura Mapbox y el contenedor de dependencias. */
class AeriaNexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        AppContainer.init(this)
        // La red tactica se conecta desde el arranque: asi el telefono recibe
        // alertas SOS de otros agentes aunque nunca se abra la pestana Map.
        AppContainer.agoraRepository.ensureStarted()
    }
}
