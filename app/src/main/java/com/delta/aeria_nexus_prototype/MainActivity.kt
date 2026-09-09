package com.delta.aeria_nexus_prototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.identity.Pkcs10
import com.delta.aeria_nexus_prototype.data.identity.PropositoDelReto
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.navigation.TrustGate
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import java.security.Signature
import java.security.cert.X509Certificate

private const val TAG_ALTA = "AeriaAlta"
private const val EXTRA_CERTIFICADO_TERMINAL = "device_cert"
private const val EXTRA_CERTIFICADO_AGENTE = "user_cert"
private const val EXTRA_ANCLA_PERIFERICOS = "peripheral_anchor"
private const val EXTRA_RETO = "challenge"
private const val EXTRA_RETO_PROPOSITO = "challenge_purpose"
private const val EXTRA_RETO_EMISOR = "challenge_issuer"
private const val EXTRA_RETO_VALIDEZ = "challenge_ttl"

/**
 * Lo que se firma cuando nadie ha emitido un reto. Sirve para cerrar el alta sin
 * backend, y no es un secreto ni pretende serlo: precisamente por ser fijo, la
 * firma que produce se puede reutilizar y no acredita frescura.
 */
private const val RETO_SIN_EMISOR = "prueba-de-posesion"

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
            // El reto se guarda ANTES de cualquier cosa que pueda consumirlo: el
            // mismo intent puede traer el reto y el certificado que lo usa.
            recibirRetoDebug(intent)
            instalarAnclaDePerifericosDebug(intent)
            importarCertificadoDelTerminalDebug(intent)
            importarCertificadoDelAgenteDebug(intent)
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
 * Cierra a mano el alta del terminal mientras no exista la CA de AeriaOne
 * (workflow 12, pasos 14 a 18, que son del backend).
 *
 * El circuito completo lo automatiza `tools/alta-terminal.sh`; a mano seria:
 *
 *     adb shell run-as com.delta.aeria_nexus_prototype cat files/enrollment/device.csr.pem > device.csr.pem
 *     openssl x509 -req -in device.csr.pem -CA ca.crt -CAkey ca.key -days 30 -sha256 -out device.crt
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es device_cert "$(base64 -w0 device.crt)"
 */
private fun importarCertificadoDelTerminalDebug(intent: Intent) {
    val pem = leerPemDelIntent(intent, EXTRA_CERTIFICADO_TERMINAL) ?: return
    val enrollment = AppContainer.enrollmentRepository

    runCatching {
        val certificado = enrollment.instalarCertificado(pem)
        registrarPosesion(
            quien = "terminal",
            paso = "16",
            proposito = PropositoDelReto.POSESION_TERMINAL,
            certificado = certificado,
            firmar = enrollment::pruebaDePosesion,
        )
        // El telefono queda acreditado, pero el alta NO ha terminado: falta la
        // credencial del agente, que es el workflow 3.
        AppContainer.identityRepository.altaDeTerminalCompletada(enrollment.deviceId())
    }.onFailure { fallo ->
        Log.e(TAG_ALTA, "No se pudo cerrar el alta del terminal", fallo)
    }
}

/**
 * Lo mismo para la credencial del agente (workflow 3, pasos 9, 10, 12 y 15, que
 * son del backend). Lo automatiza `tools/alta-agente.sh`, que firma con una CA
 * DISTINTA de la del terminal, como exige el documento de arquitectura: el
 * certificado del agente y el del telefono son dos dominios de confianza.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es user_cert "$(base64 -w0 user.crt)"
 */
private fun importarCertificadoDelAgenteDebug(intent: Intent) {
    val pem = leerPemDelIntent(intent, EXTRA_CERTIFICADO_AGENTE) ?: return
    val credential = AppContainer.credentialRepository
    val userId = AppContainer.identityRepository.status.value.identity?.userId
    if (userId == null) {
        Log.e(TAG_ALTA, "No hay agente provisionado al que instalarle un certificado")
        return
    }

    runCatching {
        val certificado = credential.instalarCertificado(pem, userId)
        registrarPosesion(
            quien = "agente",
            paso = "14",
            proposito = PropositoDelReto.POSESION_AGENTE,
            certificado = certificado,
            firmar = credential::pruebaDePosesionDelAlta,
        )
        AppContainer.identityRepository.credencialCompletada(userId)
    }.onFailure { fallo ->
        Log.e(TAG_ALTA, "No se pudo instalar la credencial del agente", fallo)
    }
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

/**
 * Recibe un reto emitido por el backend simulado y lo guarda hasta que haga falta.
 *
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es challenge_purpose ALTA_TERMINAL --es challenge "$(base64 -w0 nonce.bin)"
 *
 * Lo automatiza `tools/reto.sh`. El nonce lo genera ese script, que es lo unico
 * que importa para la frescura: el telefono no puede predecirlo.
 */
private fun recibirRetoDebug(intent: Intent) {
    val enBase64 = intent.getStringExtra(EXTRA_RETO) ?: return
    val proposito = intent.getStringExtra(EXTRA_RETO_PROPOSITO)
        ?.let { nombre -> PropositoDelReto.entries.firstOrNull { it.name == nombre } }
        ?: return

    runCatching {
        AppContainer.retoRepository.recibir(
            proposito = proposito,
            bytes = Base64.decode(enBase64, Base64.DEFAULT),
            emisor = intent.getStringExtra(EXTRA_RETO_EMISOR) ?: "tools/reto.sh",
            validoSegundos = intent.getStringExtra(EXTRA_RETO_VALIDEZ)?.toLongOrNull() ?: 300L,
        )
    }.onFailure { fallo ->
        Log.e(TAG_ALTA, "No se pudo guardar el reto recibido", fallo)
    }
}

/**
 * Prueba de posesion contra el certificado recien instalado.
 *
 * Se firma el reto que emitio el backend simulado. Si la firma verifica, la clave
 * que hay en el Keystore y la que certifico la CA son el mismo par **y** quien
 * responde tiene esa clave ahora: un reto que el telefono no eligio es lo que
 * convierte esto en prueba de frescura (IAM-04) y no solo de correspondencia.
 *
 * Si no hay reto emitido se cae a una cadena fija para no dejar el alta a medias,
 * pero se dice en el log con todas las letras: esa firma no prueba frescura y se
 * puede reutilizar. La respuesta queda en disco para que el backend la verifique
 * por su cuenta, que es lo que hace `tools/reto.sh`.
 */
private fun registrarPosesion(
    quien: String,
    paso: String,
    proposito: PropositoDelReto,
    certificado: X509Certificate,
    firmar: (ByteArray) -> ByteArray,
) {
    val repositorio = AppContainer.retoRepository
    val reto = repositorio.consumir(proposito)
    val bytes = reto?.bytes ?: RETO_SIN_EMISOR.toByteArray()
    val firma = firmar(bytes)

    val posesionDemostrada = Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
        initVerify(certificado.publicKey)
        update(bytes)
        verify(firma)
    }

    Log.i(TAG_ALTA, "Certificado del $quien instalado para ${certificado.subjectX500Principal}")
    Log.i(TAG_ALTA, "Emitido por ${certificado.issuerX500Principal}")
    if (reto != null) {
        repositorio.responder(reto, firma, certificado.subjectX500Principal.name)
        Log.i(TAG_ALTA, "Prueba de posesion del $quien (paso $paso) con reto de ${reto.emisor}: $posesionDemostrada")
    } else {
        Log.w(TAG_ALTA, "Prueba de posesion del $quien (paso $paso) SIN reto del backend: $posesionDemostrada")
        Log.w(TAG_ALTA, "Esa firma acredita correspondencia pero NO frescura: es reutilizable")
    }

    check(posesionDemostrada) { "El certificado del $quien no corresponde a su clave del Keystore" }
}
