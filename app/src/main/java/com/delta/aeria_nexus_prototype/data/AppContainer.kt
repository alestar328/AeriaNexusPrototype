package com.delta.aeria_nexus_prototype.data

import android.content.Context

/**
 * Contenedor de dependencias manual del proyecto. Se inicializa una sola vez
 * en AeriaNexusApp; no se usa ningun framework de inyeccion.
 */
object AppContainer {
    val incidentRepository = IncidentRepository()

    lateinit var locationRepository: LocationRepository
        private set
    lateinit var batteryRepository: BatteryRepository
        private set
    lateinit var agoraRepository: AgoraRepository
        private set
    lateinit var bodycamRepository: BodycamRepository
        private set
    lateinit var localEvidenceRepository: LocalEvidenceRepository
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext
        locationRepository = LocationRepository(appContext)
        batteryRepository = BatteryRepository(appContext)
        agoraRepository = AgoraRepository(appContext, locationRepository)
        bodycamRepository = BodycamRepository(appContext)
        localEvidenceRepository = LocalEvidenceRepository(appContext)
    }
}
