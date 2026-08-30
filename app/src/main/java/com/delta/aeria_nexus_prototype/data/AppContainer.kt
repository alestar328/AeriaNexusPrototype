package com.delta.aeria_nexus_prototype.data

import android.content.Context
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.local.IncidentDatabase
import com.delta.aeria_nexus_prototype.data.upload.EvidenceUploader
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import com.delta.aeria_nexus_prototype.data.upload.UploadSessions

/**
 * Contenedor de dependencias manual del proyecto. Se inicializa una sola vez
 * en AeriaNexusApp; no se usa ningun framework de inyeccion.
 */
object AppContainer {
    lateinit var incidentRepository: IncidentRepository
        private set
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
    lateinit var vaultRepository: VaultRepository
        private set
    lateinit var evidenceUploader: EvidenceUploader
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext
        // La boveda va primero: sin ella cargada, una captura que se cierre antes de
        // abrir la pantalla de evidencia se cifraria solo para el servidor y el
        // agente no podria volver a verla en el telefono.
        EvidenceVault.init(appContext)
        // Una sola instancia de Room: build() no es singleton, y abrir dos sobre el
        // mismo fichero significa dos pools de conexiones y dos rastreadores de
        // invalidacion peleandose por el mismo WAL.
        val dao = IncidentDatabase.build(appContext).incidentDao()
        incidentRepository = IncidentRepository(dao)
        locationRepository = LocationRepository(appContext)
        batteryRepository = BatteryRepository(appContext)
        agoraRepository = AgoraRepository(appContext, locationRepository)
        bodycamRepository = BodycamRepository(appContext)
        localEvidenceRepository = LocalEvidenceRepository(appContext)
        vaultRepository = VaultRepository(appContext)
        evidenceUploader = EvidenceUploader(
            context = appContext,
            config = UploadConfig(appContext),
            sessions = UploadSessions(appContext),
            dao = dao,
        )
        incidentRepository.onIncidentSaved = { evidenceUploader.reconcile() }
    }
}
