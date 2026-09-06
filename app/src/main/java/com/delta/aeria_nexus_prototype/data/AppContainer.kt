package com.delta.aeria_nexus_prototype.data

import android.content.Context
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.PinLocal
import com.delta.aeria_nexus_prototype.data.identity.RetoRepository
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
    lateinit var identityRepository: IdentityRepository
        private set
    lateinit var enrollmentRepository: EnrollmentRepository
        private set
    lateinit var credentialRepository: CredentialRepository
        private set
    lateinit var pinLocal: PinLocal
        private set
    lateinit var retoRepository: RetoRepository
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
        // Decide si la app llega siquiera a la pantalla de operaciones, asi que
        // tiene que estar lista antes de que se componga nada (ver TrustGate).
        pinLocal = PinLocal(appContext)
        enrollmentRepository = EnrollmentRepository(appContext)
        credentialRepository = CredentialRepository(appContext)
        retoRepository = RetoRepository(appContext)
        // Va detras de los tres anteriores: el desbloqueo autoriza la clave del
        // agente y firma el reto del backend, asi que los necesita ya construidos.
        identityRepository = IdentityRepository(
            context = appContext,
            pinLocal = pinLocal,
            credential = credentialRepository,
            retos = retoRepository,
        )
        evidenceUploader = EvidenceUploader(
            context = appContext,
            config = UploadConfig(appContext),
            sessions = UploadSessions(appContext),
            dao = dao,
        )
        incidentRepository.onIncidentSaved = { evidenceUploader.reconcile() }
        // Workflow 34: cerrar sesion deshace las ataduras con los perifericos. Sin
        // esto, una camara emparejada seguiria operando en nombre de un agente que
        // ya no esta de servicio.
        identityRepository.alCerrarSesion = { motivo -> bodycamRepository.desatar(motivo) }
    }

    /**
     * Destruye TODA la identidad local: la del terminal y la del agente.
     *
     * Van juntas siempre. El §13 lo exige al resetear ("reset must destroy /
     * invalidate old local identity") y dejarse una a medias es peor que no
     * borrar ninguna: el alta siguiente reutilizaria una clave vieja y no
     * probaria nada.
     */
    fun destruirIdentidadLocal() {
        enrollmentRepository.deshacerAlta()
        credentialRepository.borrarCredencial()
        pinLocal.borrar()
        retoRepository.borrar()
        // Tambien los identificadores: si se quedasen, un volcado de las
        // preferencias seguiria mostrando a que agente y a que terminal pertenecio
        // este telefono despues de haber destruido su identidad.
        identityRepository.deshacerAlta()
    }
}
