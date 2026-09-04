package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Donde vive la clave privada del terminal. Lo decide el hardware, no nosotros. */
enum class NivelClave {
    /** La clave esta en memoria del proceso: no vale para identificar un terminal. */
    SOFTWARE,

    /** Entorno de ejecucion aislado del sistema operativo. Es lo habitual. */
    TEE,

    /** Chip dedicado, separado del procesador principal. Lo mejor disponible. */
    STRONGBOX,

    /** Aun no se puede saber: hace falta generar la clave para preguntarlo. */
    DESCONOCIDO,
}

/**
 * Informacion minima del terminal para el alta (workflow 12, paso 3).
 *
 * "Minima" es literal y es una decision, no una omision: el §15 del documento
 * limita el alcance de la gestion a lo que hace falta para proteger el servicio,
 * y el paso 19 lo llama "privacy boundary". El telefono es del agente. Aqui no
 * entra nada que no sea el aparato y nuestra propia app.
 */
data class DeviceAttributes(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** Fecha del parche de seguridad; el backend la usa para la elegibilidad (paso 4). */
    val securityPatch: String,
    val buildFingerprint: String,
    val appPackage: String,
    val appVersion: String,
    /** SHA-256 del certificado con el que se firmo el APK (paso 7). */
    val appSignerSha256: String,
)

/**
 * Estado de seguridad observable desde dentro de la app (workflow 12, pasos 5 y 6).
 *
 * AVISO IMPORTANTE sobre lo que esto vale: son **indicios**, no pruebas. Cualquier
 * comprobacion que hace la app puede falsearla quien controle el sistema, que es
 * precisamente el caso del que queremos protegernos. La prueba de verdad del
 * arranque verificado y del respaldo por hardware es la atestacion de la clave
 * (`DeviceKeystore.cadenaDeAtestacion`), que va firmada por Google y la verifica
 * el backend.
 *
 * Por eso esta clase no tiene ninguna propiedad del tipo "apto": decidir la
 * elegibilidad es el paso 4, y ese paso es del backend. Aqui solo se informa.
 */
data class DevicePosture(
    val keystoreDisponible: Boolean,
    /** Lo que declara el sistema ANTES de generar la clave; se confirma despues. */
    val capacidadDeclarada: NivelClave,
    /** Rastros de un sistema abierto o modificado. Vacio no significa limpio. */
    val indiciosDeManipulacion: List<String>,
    val esEmulador: Boolean,
)

/** Recolector de los datos del terminal. */
object DeviceInspector {

    fun atributos(context: Context): DeviceAttributes {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return DeviceAttributes(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildFingerprint = Build.FINGERPRINT,
            appPackage = context.packageName,
            appVersion = "${packageInfo.versionName} ($versionCode)",
            appSignerSha256 = huellaDelFirmante(context),
        )
    }

    fun postura(context: Context): DevicePosture = DevicePosture(
        keystoreDisponible = keystoreDisponible(),
        capacidadDeclarada = capacidadDeclarada(context),
        indiciosDeManipulacion = indiciosDeManipulacion(),
        esEmulador = esEmulador(),
    )

    private fun keystoreDisponible(): Boolean = runCatching {
        java.security.KeyStore.getInstance(DeviceKeystore.PROVEEDOR).apply { load(null) }
        true
    }.getOrDefault(false)

    /**
     * Lo que el sistema dice tener. En Android 8 y 9 no existe ninguna de las dos
     * banderas, asi que la respuesta honesta es DESCONOCIDO hasta que se genere la
     * clave y se pregunte por ella.
     */
    private fun capacidadDeclarada(context: Context): NivelClave {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        ) {
            return NivelClave.STRONGBOX
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            pm.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE)
        ) {
            return NivelClave.TEE
        }
        return NivelClave.DESCONOCIDO
    }

    private fun indiciosDeManipulacion(): List<String> = buildList {
        // Una imagen firmada con las claves de desarrollo de AOSP no es una
        // version de fabrica.
        if (Build.TAGS?.contains("test-keys") == true) add("build firmado con test-keys")
        RUTAS_SU.filter { File(it).exists() }.forEach { add("binario su en $it") }
    }

    private fun esEmulador(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true)

    private fun huellaDelFirmante(context: Context): String {
        val firmas = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures
            }
        }.getOrNull()

        val primera = firmas?.firstOrNull() ?: return ""
        val resumen = MessageDigest.getInstance("SHA-256").digest(primera.toByteArray())
        return resumen.joinToString(":") { "%02X".format(it) }
    }

    private val RUTAS_SU = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/data/local/bin/su",
    )
}
