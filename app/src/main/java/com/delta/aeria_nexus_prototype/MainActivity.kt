package com.delta.aeria_nexus_prototype

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import kotlinx.coroutines.launch
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.GafasApPrueba
import com.delta.aeria_nexus_prototype.data.GafasSondaEstado
import com.delta.aeria_nexus_prototype.data.GafasSdkPuente
import com.delta.aeria_nexus_prototype.data.GafasSondaListado
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.navigation.TrustGate
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme

private const val TAG_ALTA = "AeriaAlta"
private const val EXTRA_ANCLA_PERIFERICOS = "peripheral_anchor"

/** Marca de que ya se ofrecio la exencion de bateria; no se insiste. */
private const val YA_PREGUNTADO = "exencion_bateria_preguntada"

/** Idem para el autoarranque del fabricante, que va por otra pantalla. */
private const val YA_AUTOARRANQUE = "autoarranque_fabricante_ofrecido"

/** Actividad unica: toda la app vive en Compose con navegacion propia. */
class MainActivity : ComponentActivity() {

    /**
     * La notificacion de RadioService es la unica cara de la radio cuando la app
     * no esta delante. Sin POST_NOTIFICATIONS (Android 13+) el servicio corre
     * igual, pero su notificacion no se ve y el agente no tiene forma de saber si
     * sigue a la escucha ni quien esta hablando.
     */
    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pedirNotificacionesSiHaceFalta()
        pedirExencionDeBateriaUnaVez()
        ofrecerAutoarranqueDelFabricanteUnaVez()
        // La app muestra evidencia y datos de agentes: FLAG_SECURE bloquea las
        // capturas y la grabacion de pantalla en toda la aplicacion, y ademas
        // oculta la vista previa en el selector de apps recientes.
        // TODO-REACTIVAR-FLAG_SECURE: desactivado temporalmente para poder tomar
        // screenshots y grabar pantalla durante el desarrollo. Descomentar antes
        // de release.
        // window.setFlags(
        //     WindowManager.LayoutParams.FLAG_SECURE,
        //     WindowManager.LayoutParams.FLAG_SECURE,
        // )
        if (BuildConfig.DEBUG) {
            aplicarEstadoDeArranqueDebug(intent)
            instalarAnclaDePerifericosDebug(intent)
            importarEnBrutoDebug(intent)
            encenderApDeLasGafasDebug(intent)
            sondarEstadoDeLasGafasDebug(intent)
            listarLasGafasDebug(intent)
            registrarFalloDelCanalDebug()
        }
        enableEdgeToEdge()
        setContent {
            AeriaNexusPrototypeTheme {
                // La app ya no arranca operativa: TrustGate decide si se ve la
                // navegacion, la pantalla de PIN o un terminal fuera de servicio.
                TrustGate()
            }
        }
    }

    /**
     * Pide una sola vez que el sistema deje de optimizar la bateria de la app.
     *
     * Sin esto, quitar la app del multitarea o dejar el telefono quieto acaba
     * matando el proceso, y con el la radio: el agente deja de recibir el PTT sin
     * enterarse. Se pregunta una vez y no se vuelve a insistir — si el agente dice
     * que no, es su decision y repetirsela cada arranque solo consigue que la
     * conceda sin leerla.
     *
     * **No basta en Xiaomi, Huawei ni Oppo**: ahi ademas hay que conceder el
     * autoarranque y poner la bateria en "sin restricciones" a mano, en los ajustes
     * del sistema. Eso no se puede pedir por intent.
     */
    private fun pedirExencionDeBateriaUnaVez() {
        val prefs = getSharedPreferences("aeria_radio", Context.MODE_PRIVATE)
        if (prefs.getBoolean(YA_PREGUNTADO, false)) return
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean(YA_PREGUNTADO, true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { Log.w(TAG_ALTA, "El sistema no ofrece la exencion de bateria", it) }
    }

    /**
     * Lleva al agente a la pantalla de autoarranque del fabricante, una sola vez.
     *
     * En MIUI ese interruptor es lo que decide si la radio sigue viva al quitar la
     * app de recientes, y **las apps instaladas por adb o desde Android Studio lo
     * llevan apagado de fabrica**. No se puede conceder por codigo ni consultar: lo
     * unico que esta en nuestra mano es abrir la pantalla en vez de esperar que el
     * agente la encuentre entre los menus de seguridad de Xiaomi.
     *
     * Solo en Xiaomi/Redmi/POCO, que es el fabricante que tenemos delante. Huawei,
     * Oppo y Vivo tienen pantallas equivalentes con otros nombres de componente, y
     * no se cablean a ciegas: si el componente no existe, el salto falla en
     * silencio y el agente se queda sin el ajuste creyendo que lo hizo. Cuando haya
     * uno de esos terminales en la flota se anade con el componente comprobado.
     */
    private fun ofrecerAutoarranqueDelFabricanteUnaVez() {
        val prefs = getSharedPreferences("aeria_radio", Context.MODE_PRIVATE)
        if (prefs.getBoolean(YA_AUTOARRANQUE, false)) return
        if (Build.MANUFACTURER.lowercase() !in setOf("xiaomi", "redmi", "poco")) return
        prefs.edit().putBoolean(YA_AUTOARRANQUE, true).apply()

        val autoarranqueMiui = Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        // Si MIUI ha movido ese componente, la ficha de la app siempre existe y
        // desde ahi se llega igual.
        val fichaDeLaApp = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(autoarranqueMiui) }
            .recoverCatching { startActivity(fichaDeLaApp) }
            .onFailure { Log.w(TAG_ALTA, "No se pudo abrir el autoarranque del fabricante", it) }
    }

    private fun pedirNotificacionesSiHaceFalta() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Marca un `.fev` que YA esta en la boveda como si acabara de importarse de las
 * gafas, para poder probar la categorizacion sin tener el protocolo resuelto.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity  *         --es import_raw last
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity  *         --es import_raw video_agent_007_2026-09-09_10-11-12.mp4.fev  *         --el recorded_at 1757404272000
 *
 * `last` coge el .fev mas reciente. Sin `recorded_at` la fila queda **sin fecha de
 * grabacion**, que es el caso que hay que ver funcionando: es lo que pasara con un
 * aparato que no la reporte.
 *
 * El hash de custodia va como `DEBUG-IMPORT-NO-HASH` y no se disimula: esta fila
 * no viene de un cifrado nuestro y su evidencia no esta verificada. Solo existe
 * bajo BuildConfig.DEBUG.
 */
private fun ComponentActivity.importarEnBrutoDebug(intent: Intent) {
    val cual = intent.getStringExtra("import_raw") ?: return
    val enBoveda = AppContainer.vaultRepository.list()
    val elegido = if (cual == "last") enBoveda.firstOrNull() else enBoveda.find { it.name == cual }
    if (elegido == null) {
        Log.e(TAG_ALTA, "No hay ningun .fev en la boveda que se llame '$cual'")
        return
    }
    lifecycleScope.launch {
        AppContainer.rawEvidenceRepository.registrar(
            fileName = elegido.name,
            bytes = elegido.bytes,
            plainSha256 = "DEBUG-IMPORT-NO-HASH",
            source = EvidenceSource.FALCON_LENS,
            originalName = elegido.name.removeSuffix(".fev"),
            recordedAtMillis = intent.getLongExtra("recorded_at", 0L),
        )
        Log.w(TAG_ALTA, "IMPORTACION DE PRUEBA: ${elegido.name} espera categorizacion")
    }
}

/**
 * Enciende el punto de acceso de las gafas y deja el SSID y la clave en logcat.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es gafas_ap Aeria123
 *
 * Con `--es gafas_scan 1` en vez de `gafas_ap` hace solo el escaneo BLE crudo,
 * para ver si las gafas se anuncian.
 *
 * La clave tiene que ser de **exactamente 8 letras o digitos**; el SDK la rechaza
 * antes de mandar nada. El resultado sale con la etiqueta `AeriaGafasAP`:
 *
 *     adb logcat -s AeriaGafasAP
 *
 * Es una sonda, no una funcion: sirve para confirmar que el AP se enciende y con
 * que credenciales, que es lo que le faltaba a GafasMediaRepository. Solo existe
 * bajo BuildConfig.DEBUG. Ver GafasApPrueba.
 */
/**
 * Engancha todos los avisos que las gafas empujan solas y los vuelca al log.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity  *         --es gafas_sonda escuchar
 *
 * Con `ciclo` en vez de `escuchar`, ademas graba y para sola a los pocos segundos,
 * para comparar los avisos de una grabacion pedida por la app con los de una
 * pedida a mano con el boton de las gafas.
 *
 *     adb logcat -s AeriaSondaEstado
 *
 * Con `parar` suelta el canal, que hay que hacer antes de volver a usar la
 * pantalla FALCON LENS.
 *
 * Responde a si el telefono puede enterarse de que las gafas graban sin haberlo
 * pedido el. Hay que salir de FALCON LENS antes: el SDK guarda un solo oyente y la
 * sonda y GafasCommandRepository se pisan. Ver GafasSondaEstado.
 */
/**
 * Enciende el AP de las gafas, se une y vuelca el listado completo de su tarjeta.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity  *         --es gafas_listar Aeria123 --es gafas_pais US
 *
 *     adb logcat -s AeriaSondaListado
 *
 * La clave son exactamente 8 letras o digitos, y el pais importa: con codigo
 * europeo el AP sale en el canal 149 y el telefono no puede unirse. Sirve para
 * saber si los dos nombres de fichero que aparecen al parar una grabacion son dos
 * ficheros o uno. Ver GafasSondaListado.
 */
private fun ComponentActivity.listarLasGafasDebug(intent: Intent) {
    // El pais se recoge SIEMPRE, aunque no haya listado: es lo que decide si el AP
    // de las gafas puede encenderse en la descarga de verdad. Ver GafasSdkPuente.
    intent.getStringExtra("gafas_pais")?.let { GafasSdkPuente.paisParaElPuntoDeAcceso = it }
    val clave = intent.getStringExtra("gafas_listar") ?: return
    // Si el sistema recrea la actividad, onCreate vuelve con el MISMO intent y la
    // sonda se disparaba dos veces, pisandose el GATT. Se consume el extra.
    intent.removeExtra("gafas_listar")
    GafasSondaListado.arrancar(this, lifecycleScope, clave, intent.getStringExtra("gafas_pais"))
}

private fun ComponentActivity.sondarEstadoDeLasGafasDebug(intent: Intent) {
    val modo = intent.getStringExtra("gafas_sonda") ?: return
    // Si el sistema recrea la actividad, onCreate vuelve con el MISMO intent y la
    // sonda se disparaba dos veces, pisandose el GATT. Se consume el extra.
    intent.removeExtra("gafas_sonda")
    if (modo == "parar") {
        GafasSondaEstado.parar()
        return
    }
    GafasSondaEstado.arrancar(this, lifecycleScope, conCiclo = modo == "ciclo")
}

private fun ComponentActivity.encenderApDeLasGafasDebug(intent: Intent) {
    if (intent.hasExtra("gafas_scan")) {
        GafasApPrueba.escanearCrudo(this)
        return
    }
    if (intent.hasExtra("gafas_gatt")) {
        GafasApPrueba.volcarGatt(this)
        return
    }
    intent.getStringExtra("gafas_solo_unir")?.let { clave ->
        intent.removeExtra("gafas_solo_unir")
        GafasApPrueba.soloUnirse(lifecycleScope, "BleeqUp-Ranger-901FC", clave)
        return
    }
    val password = intent.getStringExtra("gafas_ap") ?: return
    // Si el sistema recrea la actividad, onCreate vuelve con el MISMO intent y la
    // sonda se disparaba dos veces, pisandose el GATT. Se consume el extra.
    intent.removeExtra("gafas_ap")
    GafasApPrueba.paisForzado = intent.getStringExtra("gafas_pais")
    // Con gafas_unir, tras encender el AP el telefono se une a el.
    GafasApPrueba.alcance = if (intent.hasExtra("gafas_unir")) lifecycleScope else null
    GafasApPrueba.encender(this, password)
}

/**
 * Simula que Agora da el canal por perdido, sin pasar 20 min sin red:
 *
 *     adb shell am broadcast -a com.delta.aeria_nexus_prototype.FALLO_CANAL
 *
 * Y que otro agente abre o cierra su PTT, para probar el aviso de PTT pisado sin un
 * segundo telefono:
 *
 *     adb shell am broadcast -a com.delta.aeria_nexus_prototype.PTT_REMOTO \
 *         --ez abierto true --es officer 7777
 *
 * Por broadcast y no por extra del intent para no tener que reiniciar la app, que
 * vuelve a pedir el PIN. Se registra en el contexto de la aplicacion y una sola
 * vez por proceso. Solo existe bajo BuildConfig.DEBUG.
 */
private var falloDelCanalRegistrado = false

private fun ComponentActivity.registrarFalloDelCanalDebug() {
    if (falloDelCanalRegistrado) return
    falloDelCanalRegistrado = true
    ContextCompat.registerReceiver(
        applicationContext,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppContainer.agoraRepository.simularFalloDelCanalDebug()
            }
        },
        IntentFilter("com.delta.aeria_nexus_prototype.FALLO_CANAL"),
        ContextCompat.RECEIVER_EXPORTED,
    )
    ContextCompat.registerReceiver(
        applicationContext,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppContainer.agoraRepository.simularPttRemotoDebug(
                    abierto = intent.getBooleanExtra("abierto", true),
                    officer = intent.getStringExtra("officer") ?: "7777",
                )
            }
        },
        IntentFilter("com.delta.aeria_nexus_prototype.PTT_REMOTO"),
        ContextCompat.RECEIVER_EXPORTED,
    )
}

/**
 * Permite arrancar la app directamente en un estado de confianza concreto:
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es trust_state LOCKED
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es trust_state BLOCKED --es block_reason DEVICE_REVOKED
 *
 * Es el mismo atajo que el selector de TrustStateSimulator, pero desde consola.
 * Hace falta porque el Redmi de pruebas (MIUI) rechaza `adb shell input`, asi que
 * sin esto cada captura de las siete pantallas de arranque exige toques a mano.
 *
 * Solo se llama bajo BuildConfig.DEBUG: en release el extra se ignora porque la
 * llamada ni siquiera existe.
 */
private fun aplicarEstadoDeArranqueDebug(intent: Intent) {
    val nombreEstado = intent.getStringExtra("trust_state") ?: return
    val estado = TrustState.entries.firstOrNull { it.name == nombreEstado } ?: return
    val motivo = intent.getStringExtra("block_reason")
        ?.let { nombre -> TrustBlockReason.entries.firstOrNull { it.name == nombre } }
    // Volver a "sin dar de alta" destruye la clave y la peticion, igual que en el
    // selector de la pantalla: si no, el alta siguiente reutilizaria la anterior y
    // no probaria nada.
    if (estado == TrustState.NOT_PROVISIONED) AppContainer.destruirIdentidadLocal()
    AppContainer.identityRepository.forzarEstado(estado, motivo)
}

/**
 * Instala el ancla con la que este telefono valida a los perifericos que se
 * emparejan (workflow 31). Lo automatiza `tools/alta-bodycam.sh`.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity  *         --es peripheral_anchor "$(base64 -w0 ca.crt)"
 */
private fun instalarAnclaDePerifericosDebug(intent: Intent) {
    val pem = leerPemDelIntent(intent, EXTRA_ANCLA_PERIFERICOS) ?: return
    runCatching {
        val ancla = AppContainer.enrollmentRepository.instalarAnclaDePerifericos(pem)
        Log.i(TAG_ALTA, "Ancla de perifericos instalada: ${ancla.subjectX500Principal}")
    }.onFailure { fallo ->
        Log.e(TAG_ALTA, "No se pudo instalar el ancla de perifericos", fallo)
    }
}

private fun leerPemDelIntent(intent: Intent, extra: String): String? {
    val certificadoEnBase64 = intent.getStringExtra(extra) ?: return null
    return String(Base64.decode(certificadoEnBase64, Base64.DEFAULT), Charsets.UTF_8)
}
