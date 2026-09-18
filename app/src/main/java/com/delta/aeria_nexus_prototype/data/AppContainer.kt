package com.delta.aeria_nexus_prototype.data

import android.content.Context
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.ContextoDeAuditoria
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceVault
import com.delta.aeria_nexus_prototype.data.identity.CredentialRepository
import com.delta.aeria_nexus_prototype.data.identity.EnrollmentRepository
import com.delta.aeria_nexus_prototype.data.identity.IamClient
import com.delta.aeria_nexus_prototype.data.identity.IdentityRepository
import com.delta.aeria_nexus_prototype.data.identity.PinLocal
import com.delta.aeria_nexus_prototype.data.identity.SesionBackend
import com.delta.aeria_nexus_prototype.data.local.IncidentDatabase
import com.delta.aeria_nexus_prototype.data.upload.EvidenceUploader
import com.delta.aeria_nexus_prototype.data.upload.ManifestUploader
import com.delta.aeria_nexus_prototype.data.upload.UploadConfig
import com.delta.aeria_nexus_prototype.data.upload.UploadSessions
import com.delta.aeria_nexus_prototype.data.video.ProxyEncoder

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
    lateinit var buscadorBodycam: BuscadorBodycam
        private set
    lateinit var gafasRepository: GafasRepository
        private set
    lateinit var gafasMediaRepository: GafasMediaRepository
        private set
    lateinit var gafasCommandRepository: GafasCommandRepository
        private set
    lateinit var gafasPendientesRepository: GafasPendientesRepository
        private set
    lateinit var releGafas: ReleGafas
        private set
    lateinit var descargaDeGafas: DescargaDeGafas
        private set
    lateinit var rawEvidenceRepository: RawEvidenceRepository
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
    lateinit var iamClient: IamClient
        private set
    lateinit var evidenceUploader: EvidenceUploader
        private set
    lateinit var manifestUploader: ManifestUploader
        private set
    lateinit var proxyRepository: ProxyRepository
        private set

    /** Diario de auditoria local (workflow 61). Lo primero en crearse: todos registran en el. */
    lateinit var auditoria: AuditoriaLocal

    fun init(context: Context) {
        val appContext = context.applicationContext
        auditoria = AuditoriaLocal(appContext)
        // La boveda va primero: sin ella cargada, una captura que se cierre antes de
        // abrir la pantalla de evidencia se cifraria solo para el servidor y el
        // agente no podria volver a verla en el telefono.
        EvidenceVault.init(appContext)
        // Una sola instancia de Room: build() no es singleton, y abrir dos sobre el
        // mismo fichero significa dos pools de conexiones y dos rastreadores de
        // invalidacion peleandose por el mismo WAL.
        val db = IncidentDatabase.build(appContext)
        val dao = db.incidentDao()
        // Un solo upload.conf para la subida, los avisos del SOS y el IAM. El token con
        // el que se sube lo pone la sesion que se abre con el PIN.
        val sesionBackend = SesionBackend()
        val uploadConfig = UploadConfig(appContext, sesionBackend)
        iamClient = IamClient(uploadConfig)
        locationRepository = LocationRepository(appContext)
        incidentRepository = IncidentRepository(dao, locationRepository)
        // Material que entra de un periferico y todavia no es de ningun incidente.
        rawEvidenceRepository = RawEvidenceRepository(db.rawEvidenceDao(), incidentRepository)
        batteryRepository = BatteryRepository(appContext)
        agoraRepository = AgoraRepository(
            context = appContext,
            locationRepository = locationRepository,
            sosNotifier = SosNotifier(appContext, uploadConfig),
        )
        bodycamRepository = BodycamRepository(appContext)
        buscadorBodycam = BuscadorBodycam(appContext)
        gafasRepository = GafasRepository(appContext)
        // Canal de mando de las gafas. No abre nada al construirse: el GATT se abre
        // desde la pantalla de control y se cierra al salir de ella.
        gafasCommandRepository = GafasCommandRepository(appContext)
        // Los videos que se quedan en la tarjeta de las gafas esperando descarga.
        // Va en disco: entre que se graban y alguien los trae pueden pasar horas.
        gafasPendientesRepository = GafasPendientesRepository(appContext)
        // Grabar y parar con la bodycam hace grabar y parar a las gafas, sin que el
        // oficial toque el telefono. Construirlo no vigila nada todavia: lo arranca
        // BodycamService, que es quien mantiene vivo el proceso en segundo plano.
        releGafas = ReleGafas(
            bodycam = bodycamRepository,
            gafas = gafasCommandRepository,
            pendientes = gafasPendientesRepository,
            aviso = AvisoDeGafas(appContext),
        )
        localEvidenceRepository = LocalEvidenceRepository(appContext)
        // Trae de las gafas a la boveda. Lo usa DescargaDeGafas, que es quien
        // orquesta encender el AP, unirse, listar y bajar. Construirlo aqui no
        // abre red ni consume nada.
        gafasMediaRepository = GafasMediaRepository(
            context = appContext,
            evidencia = localEvidenceRepository,
            enBruto = rawEvidenceRepository,
        )
        // Trae a la boveda lo que las gafas dejaron en su tarjeta. Necesita el
        // repositorio de medios, que se construye mas arriba con la boveda ya viva.
        descargaDeGafas = DescargaDeGafas(
            media = gafasMediaRepository,
            pendientes = gafasPendientesRepository,
            mando = gafasCommandRepository,
            bodycam = bodycamRepository,
        )
        vaultRepository = VaultRepository(appContext, auditoria)
        // Decide si la app llega siquiera a la pantalla de operaciones, asi que
        // tiene que estar lista antes de que se componga nada (ver TrustGate).
        pinLocal = PinLocal(appContext)
        enrollmentRepository = EnrollmentRepository(appContext)
        credentialRepository = CredentialRepository(appContext)
        // Va detras de los anteriores: el desbloqueo autoriza la clave del agente y
        // firma el reto del backend, asi que los necesita ya construidos.
        identityRepository = IdentityRepository(
            context = appContext,
            pinLocal = pinLocal,
            credential = credentialRepository,
            iam = iamClient,
            sesionBackend = sesionBackend,
            auditoria = auditoria,
        )
        // Quien actua y desde donde, para cada evento. Se lee en el momento de
        // registrar, asi que siempre es la identidad y la sesion de ese instante.
        auditoria.contexto = {
            val identidad = identityRepository.status.value.identity
            ContextoDeAuditoria(
                actor = identidad?.userId,
                tenant = identidad?.tenant,
                dispositivo = identidad?.deviceId,
                instancia = identidad?.appInstanceId,
                release = identidad?.release,
                sesion = identityRepository.sesionActual,
            )
        }
        // Una sola instancia de UploadSessions para los dos: sincroniza por
        // instancia y guarda en un unico fichero.
        val uploadSessions = UploadSessions(appContext)
        evidenceUploader = EvidenceUploader(
            context = appContext,
            config = uploadConfig,
            sessions = uploadSessions,
            dao = dao,
        )
        manifestUploader = ManifestUploader(appContext, uploadConfig, uploadSessions)
        proxyRepository = ProxyRepository(
            evidencia = localEvidenceRepository,
            encoder = ProxyEncoder(appContext),
            uploader = evidenceUploader,
        )
        incidentRepository.onIncidentSaved = { incidente ->
            evidenceUploader.reconcile()
            // El expediente sale cada vez que el incidente se guarda: al cerrarlo y
            // al anadirle evidencia despues (manifest-schema.md §5).
            manifestUploader.enqueue(incidente)
        }
        // Lo capturado sin sesion con AeriaOne —sin cobertura al desbloquear, o antes
        // de que llegase el token— sale en cuanto hay token.
        identityRepository.alAcreditarSesion = ::reanudarSubidas
        // Workflow 34: cerrar sesion deshace las ataduras con los perifericos. Sin
        // esto, una camara emparejada seguiria operando en nombre de un agente que
        // ya no esta de servicio.
        identityRepository.alCerrarSesion = { motivo ->
            bodycamRepository.desatar(motivo)
            // Workflow 30: la boveda se sella con la sesion. Si no, quien coja el
            // telefono despues de desbloquearlo veria la evidencia abierta del agente
            // anterior, y sus copias descifradas seguirian en la cache.
            if (EvidenceVault.desbloqueada.value) {
                auditoria.registrar(TipoEvento.BOVEDA_CERRADA, listOf("reason" to motivo.name))
            }
            EvidenceVault.bloquear()
            vaultRepository.clearDecrypted()
        }
    }

    /** Reintenta la evidencia y los manifiestos que quedaron sin entregar. */
    fun reanudarSubidas() {
        evidenceUploader.resumePending()
        manifestUploader.resumePending()
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
        // Tambien los identificadores: si se quedasen, un volcado de las
        // preferencias seguiria mostrando a que agente y a que terminal pertenecio
        // este telefono despues de haber destruido su identidad.
        identityRepository.deshacerAlta()
    }
}
