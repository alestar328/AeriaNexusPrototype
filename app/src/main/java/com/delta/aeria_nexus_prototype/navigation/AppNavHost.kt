package com.delta.aeria_nexus_prototype.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.feature.activeincident.ActiveIncidentScreen
import com.delta.aeria_nexus_prototype.feature.activeincident.ActiveIncidentViewModel
import com.delta.aeria_nexus_prototype.feature.bodycam.BodycamControllerScreen
import com.delta.aeria_nexus_prototype.feature.bodycam.BodycamControllerViewModel
import com.delta.aeria_nexus_prototype.feature.bodycam.BodycamViewfinderScreen
import com.delta.aeria_nexus_prototype.feature.bodycam.BodycamViewfinderViewModel
import com.delta.aeria_nexus_prototype.feature.bodycam.ViewfinderMode
import com.delta.aeria_nexus_prototype.feature.draftreport.DraftReportScreen
import com.delta.aeria_nexus_prototype.feature.draftreport.DraftReportViewModel
import com.delta.aeria_nexus_prototype.feature.gafas.GafasControlScreen
import com.delta.aeria_nexus_prototype.feature.gafas.GafasControlViewModel
import com.delta.aeria_nexus_prototype.feature.incidentdetail.IncidentDetailScreen
import com.delta.aeria_nexus_prototype.feature.incidentlist.IncidentListScreen
import com.delta.aeria_nexus_prototype.feature.incidentlist.IncidentListViewModel
import com.delta.aeria_nexus_prototype.feature.livestream.LivestreamScreen
import com.delta.aeria_nexus_prototype.feature.livestream.LivestreamViewModel
import com.delta.aeria_nexus_prototype.feature.map.MapScreen
import com.delta.aeria_nexus_prototype.feature.map.MapViewModel
import com.delta.aeria_nexus_prototype.feature.operations.OperationsScreen
import com.delta.aeria_nexus_prototype.feature.operations.OperationsViewModel
import com.delta.aeria_nexus_prototype.feature.profile.ProfileScreen
import com.delta.aeria_nexus_prototype.feature.rmsform.RmsFormScreen
import com.delta.aeria_nexus_prototype.feature.rmsform.RmsFormViewModel
import com.delta.aeria_nexus_prototype.feature.ptt.PttAvisoOverlay
import com.delta.aeria_nexus_prototype.feature.ptt.PttAvisoViewModel
import com.delta.aeria_nexus_prototype.feature.sos.SosAlertOverlay
import com.delta.aeria_nexus_prototype.feature.sos.SosAlertViewModel
import com.delta.aeria_nexus_prototype.feature.submitted.SubmissionSuccessScreen
import com.delta.aeria_nexus_prototype.feature.vault.VaultScreen
import com.delta.aeria_nexus_prototype.feature.vault.VaultViewModel
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import com.delta.aeria_nexus_prototype.ui.components.AppScaffold
import com.delta.aeria_nexus_prototype.ui.components.MainTab
import com.delta.aeria_nexus_prototype.ui.theme.FondoBase

/** Rutas de navegacion, equivalentes a las del prototipo web. */
object Routes {
    const val OPERATIONS = "operations"
    const val MAP = "map"
    // Variante con foco opcional: abre el mapa centrado en esa posicion
    // (p. ej. la ubicacion de un SOS). Navegar a MAP a secas sigue valiendo.
    const val MAP_WITH_FOCUS = "map?focusLat={focusLat}&focusLng={focusLng}"
    const val INCIDENTS = "incidents"
    const val PROFILE = "profile"
    // Boveda de evidencia cifrada, protegida por la contrasena del agente.
    const val VAULT = "profile/vault"
    const val INCIDENT_DETAIL = "incidents/{id}"
    const val ACTIVE_INCIDENT = "incidents/{id}/active"
    const val DRAFT_REPORT = "incidents/{id}/report"
    const val RMS_FORM = "incidents/{id}/rms"
    const val SUBMITTED = "incidents/{id}/submitted"
    // Livestream del SOS: uid 0 = camara propia (emisor), otro uid = ver a ese agente.
    const val LIVESTREAM = "livestream/{uid}"
    const val BODYCAM = "bodycam"
    // Visor remoto de la bodycam para la foto a distancia (frames por WiFi).
    const val BODYCAM_VIEWFINDER = "bodycam/viewfinder"
    // Monitor de la grabacion en curso de la bodycam (misma pantalla, modo REC).
    const val BODYCAM_REC_MONITOR = "bodycam/recording"
    // Mando a distancia de las gafas BleeqUp (grabacion y foto por BLE).
    const val GAFAS = "gafas"

    fun livestream(uid: Int) = "livestream/$uid"
    fun mapFocusedAt(latitude: Double, longitude: Double) = "map?focusLat=$latitude&focusLng=$longitude"
    fun incidentDetail(id: String) = "incidents/$id"
    fun activeIncident(id: String) = "incidents/$id/active"
    fun draftReport(id: String) = "incidents/$id/report"
    fun rmsForm(id: String) = "incidents/$id/rms"
    fun submitted(id: String) = "incidents/$id/submitted"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val repositorio = AppContainer.incidentRepository

    // Cambio de pestana inferior: vuelve a la ruta raiz de cada seccion.
    val onTabSelected: (MainTab) -> Unit = { tab ->
        val ruta = when (tab) {
            MainTab.OPERATIONS -> Routes.OPERATIONS
            MainTab.MAP -> Routes.MAP
            MainTab.INCIDENTS -> Routes.INCIDENTS
            MainTab.PROFILE -> Routes.PROFILE
        }
        navController.navigate(ruta) {
            popUpTo(Routes.OPERATIONS)
            launchSingleTop = true
        }
    }

    // La ruta actual decide el cromo: que pestana va marcada y si hay barra
    // inferior. Se deduce aqui en vez de que cada pantalla lo declare, que es lo
    // que hacia que la barra superior fuera distinta segun donde estuvieras.
    val entradaActual by navController.currentBackStackEntryAsState()
    val pestanaActual = pestanaDeLaRuta(entradaActual?.destination?.route)

    AppScaffold(
        currentTab = pestanaActual,
        onTabSelected = onTabSelected,
        // La barra inferior solo en las cuatro secciones principales; el resto son
        // pantallas de detalle a las que se entra y de las que se sale con atras.
        showNav = pestanaActual != null,
        // Los perifericos se abren desde los iconos de la barra, que ahora existe
        // en todas las pantallas. El NavController vive aqui, asi que ninguna
        // pantalla necesita saber nada de esto.
        onOpenBodycam = { navController.navigate(Routes.BODYCAM) },
        onOpenLens = { navController.navigate(Routes.GAFAS) },
    ) { innerPadding ->
    // Fondo fijo oscuro detras de las transiciones y fundidos cortos:
    // sin esto se percibe un destello entre pantalla y pantalla.
    NavHost(
        navController = navController,
        startDestination = Routes.OPERATIONS,
        modifier = Modifier
            .fillMaxSize()
            // El hueco de las barras se descuenta AQUI y en ningun otro sitio: es
            // lo que hace que las pantallas sean solo contenido. Sin esto, todas
            // dibujan por debajo de la barra inferior y se comen su propia cabecera.
            .padding(innerPadding)
            .background(FondoBase),
        enterTransition = { fadeIn(tween(FADE_IN_MILLIS)) },
        exitTransition = { fadeOut(tween(FADE_OUT_MILLIS)) },
        popEnterTransition = { fadeIn(tween(FADE_IN_MILLIS)) },
        popExitTransition = { fadeOut(tween(FADE_OUT_MILLIS)) },
    ) {

        composable(Routes.OPERATIONS) {
            OperationsScreen(
                viewModel = viewModel {
                    OperationsViewModel(
                        repositorio,
                        AppContainer.agoraRepository,
                        AppContainer.gafasRepository,
                    )
                },
                onOpenActiveIncident = { id -> navController.navigate(Routes.activeIncident(id)) },
                onOpenSosLivestream = {
                    navController.navigate(Routes.livestream(LivestreamViewModel.OWN_CAMERA_UID))
                },
                onOpenBodycamControl = { navController.navigate(Routes.BODYCAM) },
                onOpenLensControl = { navController.navigate(Routes.GAFAS) },
            )
        }

        composable(Routes.BODYCAM) {
            BodycamControllerScreen(
                viewModel = viewModel {
                    BodycamControllerViewModel(AppContainer.bodycamRepository, AppContainer.buscadorBodycam)
                },
                onOpenLivestream = {
                    // Sin STATUS todavia no se sabe con que uid emite la camara, y un
                    // visor apuntando a otro uid se quedaria en negro.
                    AppContainer.bodycamRepository.uidAgoraBodycam?.let { uid ->
                        navController.navigate(Routes.livestream(uid))
                    }
                },
                onOpenViewfinder = { navController.navigate(Routes.BODYCAM_VIEWFINDER) },
                onOpenRecordingMonitor = { navController.navigate(Routes.BODYCAM_REC_MONITOR) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.GAFAS) {
            GafasControlScreen(
                viewModel = viewModel {
                    GafasControlViewModel(
                        mando = AppContainer.gafasCommandRepository,
                        presencia = AppContainer.gafasRepository,
                        pendientes = AppContainer.gafasPendientesRepository,
                        descarga = AppContainer.descargaDeGafas,
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BODYCAM_VIEWFINDER) {
            BodycamViewfinderScreen(
                viewModel = viewModel {
                    BodycamViewfinderViewModel(AppContainer.bodycamRepository, ViewfinderMode.PHOTO)
                },
                onClose = { navController.popBackStack() },
            )
        }

        composable(Routes.BODYCAM_REC_MONITOR) {
            BodycamViewfinderScreen(
                viewModel = viewModel {
                    BodycamViewfinderViewModel(AppContainer.bodycamRepository, ViewfinderMode.RECORDING)
                },
                onClose = { navController.popBackStack() },
            )
        }

        composable(Routes.LIVESTREAM) { entrada ->
            val uid = requireNotNull(entrada.arguments?.getString("uid")).toInt()
            LivestreamScreen(
                // La clave por uid evita reutilizar el ViewModel de otro livestream.
                viewModel = viewModel(key = "livestream-$uid") {
                    LivestreamViewModel(AppContainer.agoraRepository, uid)
                },
                onClose = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.MAP_WITH_FOCUS,
            arguments = listOf(
                navArgument("focusLat") { defaultValue = "" },
                navArgument("focusLng") { defaultValue = "" },
            ),
        ) { entrada ->
            MapScreen(
                viewModel = viewModel {
                    MapViewModel(
                        AppContainer.locationRepository,
                        AppContainer.batteryRepository,
                        AppContainer.agoraRepository,
                    )
                },
                onOpenLivestream = { uid -> navController.navigate(Routes.livestream(uid)) },
                focusLatitude = entrada.arguments?.getString("focusLat")?.toDoubleOrNull(),
                focusLongitude = entrada.arguments?.getString("focusLng")?.toDoubleOrNull(),
            )
        }

        composable(Routes.INCIDENTS) {
            IncidentListScreen(
                viewModel = viewModel { IncidentListViewModel(repositorio) },
                onOpenIncident = { id -> navController.navigate(Routes.incidentDetail(id)) },
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(
                profile = repositorio.officerProfile,
                onOpenVault = { navController.navigate(Routes.VAULT) },
            )
        }

        composable(Routes.VAULT) {
            VaultScreen(
                viewModel = viewModel {
                    VaultViewModel(
                        vault = AppContainer.vaultRepository,
                        enBruto = AppContainer.rawEvidenceRepository,
                        incidentes = AppContainer.incidentRepository,
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.INCIDENT_DETAIL) { entrada ->
            val id = entrada.requireId()
            IncidentDetailScreen(
                incident = repositorio.findOfficerIncident(id),
                onBack = { navController.popBackStack() },
                // El reporte IA solo existe para el caso de demostracion INC-001,
                // igual que en el prototipo web original.
                onGenerateReport = { navController.navigate(Routes.draftReport("INC-001")) },
            )
        }

        composable(Routes.ACTIVE_INCIDENT) {
            ActiveIncidentScreen(
                viewModel = viewModel {
                    ActiveIncidentViewModel(
                        repositorio,
                        AppContainer.bodycamRepository,
                        AppContainer.localEvidenceRepository,
                        AppContainer.evidenceUploader,
                        AppContainer.proxyRepository,
                        AppContainer.agoraRepository,
                    )
                },
                onBackToOperations = { navController.popBackStack() },
                onIncidentEnded = {
                    navController.navigate(Routes.INCIDENTS) {
                        popUpTo(Routes.OPERATIONS)
                    }
                },
                onOpenSosLivestream = {
                    navController.navigate(Routes.livestream(LivestreamViewModel.OWN_CAMERA_UID))
                },
            )
        }

        composable(Routes.DRAFT_REPORT) { entrada ->
            val id = entrada.requireId()
            DraftReportScreen(
                // La clave por id evita reutilizar el ViewModel de otro incidente.
                viewModel = viewModel(key = "draft-$id") { DraftReportViewModel(repositorio, id) },
                onBack = { navController.popBackStack() },
                onApproved = { navController.navigate(Routes.rmsForm(it)) },
            )
        }

        composable(Routes.RMS_FORM) { entrada ->
            val id = entrada.requireId()
            RmsFormScreen(
                viewModel = viewModel(key = "rms-$id") { RmsFormViewModel(repositorio, id) },
                onBack = { navController.popBackStack() },
                onSubmitted = { navController.navigate(Routes.submitted(it)) },
            )
        }

        composable(Routes.SUBMITTED) { entrada ->
            SubmissionSuccessScreen(
                incident = repositorio.findReportIncident(entrada.requireId()),
                onBackToList = {
                    navController.navigate(Routes.INCIDENTS) {
                        popUpTo(Routes.OPERATIONS)
                    }
                },
            )
        }
    }
    }

    // Alertas SOS de otros agentes. Los dialogos abren su propia ventana por
    // encima de cualquier pantalla, por eso basta con montarlos aqui una vez.
    SosAlertOverlay(
        viewModel = viewModel {
            SosAlertViewModel(AppContainer.agoraRepository, AppContainer.bodycamRepository)
        },
        onAcceptLivestream = { uid -> navController.navigate(Routes.livestream(uid)) },
        onOpenMap = { latitude, longitude ->
            navController.navigate(Routes.mapFocusedAt(latitude, longitude)) {
                popUpTo(Routes.OPERATIONS)
            }
        },
    )

    // Aviso de PTT: un companero esta comunicando por la bodycam. Se monta
    // DESPUES del SOS a proposito — si coinciden, la emergencia manda. Es una
    // banda superior, no un dialogo: no roba el foco ni tapa la pantalla.
    PttAvisoOverlay(
        viewModel = viewModel {
            PttAvisoViewModel(AppContainer.agoraRepository, AppContainer.bodycamRepository)
        },
    )
}

/** Lee el argumento id de la ruta; todas las rutas de detalle lo requieren. */
private fun androidx.navigation.NavBackStackEntry.requireId(): String =
    requireNotNull(arguments?.getString("id")) { "La ruta requiere el argumento id" }

// Fundidos cortos: mas de 300 ms se siente lento en uso de campo.
private const val FADE_IN_MILLIS = 220
private const val FADE_OUT_MILLIS = 180

/**
 * Que pestana inferior corresponde a una ruta, o null si no es una seccion.
 *
 * Vive aqui y no en cada pantalla porque el cromo es de la app, no de la pantalla:
 * antes cada una declaraba su pestana y su barra, y por eso la barra superior
 * acababa siendo distinta segun donde estuvieras.
 */
private fun pestanaDeLaRuta(ruta: String?): MainTab? = when (ruta) {
    Routes.OPERATIONS -> MainTab.OPERATIONS
    Routes.MAP, Routes.MAP_WITH_FOCUS -> MainTab.MAP
    Routes.INCIDENTS -> MainTab.INCIDENTS
    Routes.PROFILE -> MainTab.PROFILE
    else -> null
}
