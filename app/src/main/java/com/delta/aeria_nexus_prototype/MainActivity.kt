package com.delta.aeria_nexus_prototype

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delta.aeria_nexus_prototype.data.AppContainer
import com.delta.aeria_nexus_prototype.data.identity.Pkcs10
import com.delta.aeria_nexus_prototype.data.identity.TrustBlockReason
import com.delta.aeria_nexus_prototype.data.identity.TrustState
import com.delta.aeria_nexus_prototype.navigation.TrustGate
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme
import java.security.Signature

private const val TAG_ALTA = "AeriaAlta"

/** Actividad unica: toda la app vive en Compose con navegacion propia. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            importarCertificadoDeAltaDebug(intent)
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
    if (estado == TrustState.NOT_PROVISIONED) AppContainer.enrollmentRepository.deshacerAlta()
    AppContainer.identityRepository.forzarEstado(estado, motivo)
}

/**
 * Cierra a mano el alta del terminal mientras no exista la CA de AeriaOne
 * (workflow 12, pasos 14 a 18, que son del backend).
 *
 * El circuito completo, con el CSR que genero la app:
 *
 *     adb shell run-as com.delta.aeria_nexus_prototype cat files/enrollment/device.csr.pem > device.csr.pem
 *     openssl x509 -req -in device.csr.pem -CA ca.crt -CAkey ca.key -days 30 -sha256 -out device.crt
 *     adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity \
 *         --es device_cert "$(base64 -w0 device.crt)"
 *
 * Al instalarlo se hace ademas la prueba de posesion del paso 16 contra el
 * certificado recien puesto: si la firma verifica, la clave que hay en el
 * Keystore y la que certifico la CA son el mismo par, que es lo unico que este
 * paso tiene que demostrar.
 */
private fun importarCertificadoDeAltaDebug(intent: Intent) {
    val certificadoEnBase64 = intent.getStringExtra("device_cert") ?: return
    val enrollment = AppContainer.enrollmentRepository

    runCatching {
        val pem = String(Base64.decode(certificadoEnBase64, Base64.DEFAULT), Charsets.UTF_8)
        val certificado = enrollment.instalarCertificado(pem)

        val reto = "prueba-de-posesion".toByteArray()
        val firma = enrollment.pruebaDePosesion(reto)
        val posesionDemostrada = Signature.getInstance(Pkcs10.ALGORITMO_FIRMA).run {
            initVerify(certificado.publicKey)
            update(reto)
            verify(firma)
        }

        Log.i(TAG_ALTA, "Certificado instalado para ${certificado.subjectX500Principal}")
        Log.i(TAG_ALTA, "Emitido por ${certificado.issuerX500Principal}")
        Log.i(TAG_ALTA, "Prueba de posesion (paso 16): $posesionDemostrada")

        check(posesionDemostrada) { "El certificado no se corresponde con la clave del Keystore" }
        AppContainer.identityRepository.altaCompletada(enrollment.deviceId())
    }.onFailure { fallo ->
        Log.e(TAG_ALTA, "No se pudo cerrar el alta con el certificado recibido", fallo)
    }
}
