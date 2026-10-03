import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // KSP genera el codigo de Room en tiempo de compilacion; no pesa en el APK.
    alias(libs.plugins.ksp)
}

// Secretos locales (tokens) leidos de local.properties, que no va a git.
val localProperties = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) {
        archivo.inputStream().use { load(it) }
    }
}
val mapboxAccessToken: String = localProperties.getProperty("MAPBOX_ACCESS_TOKEN") ?: ""
val agoraAppId: String = localProperties.getProperty("AGORA_APP_ID") ?: ""
// Pais que se le declara al SDK de las gafas al encender su punto de acceso. Su
// unico canal es el 149 (5745 MHz) y el firmware se niega si el pais del telefono
// no lo permite: con un movil en ES responde "Open WiFi failed". El despliegue es
// en Filipinas, asi que aqui va PH. Vacio = se usa el pais real del terminal.
val paisPerifericos: String = localProperties.getProperty("PAIS_PERIFERICOS") ?: ""
// Deja el selector de estado de confianza tambien en release, para poder saltarse
// la verificacion durante las pruebas del manager. NO es un ajuste de conveniencia:
// una release con esto en true no exige alta ni PIN y NO puede llegar a campo.
val simuladorEnRelease: Boolean =
    (localProperties.getProperty("SIMULADOR_CONFIANZA_EN_RELEASE") ?: "false").toBoolean()

// La version vive en version.properties (raiz del repo, versionado en git) en
// vez de escribirse aqui, para que la tarea git addincrementarVersion de mas abajo
// pueda subirla automaticamente cada vez que se genera un APK de release.
val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    versionPropertiesFile.inputStream().use { load(it) }
}
val appVersionCode = versionProperties.getProperty("VERSION_CODE").toInt()
val appVersionName: String = versionProperties.getProperty("VERSION_NAME")

// Modelo de Whisper que va dentro del APK (decision del usuario, 2026-10-02:
// base cuantizado a 5 bits, unos 57 MB). No esta en git: lo baja la tarea
// descargarModeloWhisper, comprueba el hash y lo deja en los assets.
val whisperModelo = "ggml-base-q5_1"
val whisperModeloSha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
val whisperModeloUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$whisperModelo.bin"

android {
    namespace = "com.delta.aeria_nexus_prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.delta.aeria_nexus_prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")
        buildConfigField("String", "AGORA_APP_ID", "\"$agoraAppId\"")
        buildConfigField("String", "PAIS_PERIFERICOS", "\"$paisPerifericos\"")
        buildConfigField("boolean", "SIMULADOR_CONFIANZA", "true")
        buildConfigField("String", "WHISPER_MODELO", "\"$whisperModelo\"")
        buildConfigField("String", "WHISPER_MODELO_SHA256", "\"$whisperModeloSha256\"")

        // whisper.cpp solo para arm64: es lo que llevan los telefonos de campo, y
        // en 32 bits transcribir tardaria mas que la grabacion. En el resto de ABIs
        // la transcripcion sale como "failed" y la evidencia sigue igual.
        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
            }
        }
    }

    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        // El modelo se copia una vez del APK a disco; sin comprimir no hay que
        // descomprimir 57 MB que, ademas, apenas comprimen.
        noCompress += "bin"
    }

    // Firma de release. La ruta y las contrasenas viven en local.properties, que
    // no va a git; el keystore vive fuera del repo. Si falta cualquiera de las
    // cuatro propiedades no se declara la config y el release sale sin firmar,
    // que es preferible a fallar el build en una maquina que no tenga la clave.
    val releaseKeystore = localProperties.getProperty("RELEASE_KEYSTORE_FILE")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                // Con minSdk 26 el esquema v2 es suficiente y es el que acaba
                // verificando (AGP omite el v1 aunque se pida). Se deja pedido
                // por si algun dia baja el minSdk.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Sufijo con fecha/hora para distinguir builds debug entre si cuando
            // se comparten como APK suelto (ej. a ciberseguridad para pruebas):
            // version.properties no sube en debug, asi que sin esto todos los
            // debug se verian igual (1.0).
            versionNameSuffix = "-debug-" + SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
        }
        release {
            // En release el selector solo existe si se pide EXPLICITAMENTE en
            // local.properties. Asi una release normal sigue exigiendo alta y PIN,
            // y la de pruebas se distingue por configuracion, no por un comentario
            // que alguien olvide descomentar (ver FLAG_SECURE, comentado desde julio).
            buildConfigField("boolean", "SIMULADOR_CONFIANZA", simuladorEnRelease.toString())
            signingConfig = signingConfigs.findByName("release")
            // Minify y shrinkResources reducen el peso del APK y eliminan
            // los iconos de material-icons-extended que no se usan.
            isMinifyEnabled = true
            isShrinkResources = true
            // Solo ABIs de telefonos reales: las x86 son de emulador y duplican
            // el peso de las libs nativas de Mapbox y Agora. Debug las conserva.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Extensiones opcionales que el core de Agora trae embebidas y solo
            // carga si se activan (no las usamos): quitarlas ahorra ~25 MB.
            excludes += setOf(
                "lib/*/libagora_ai_echo_cancellation_extension.so",
                "lib/*/libagora_ai_echo_cancellation_ll_extension.so",
                "lib/*/libagora_ai_noise_suppression_extension.so",
                "lib/*/libagora_ai_noise_suppression_ll_extension.so",
                "lib/*/libagora_audio_beauty_extension.so",
                "lib/*/libagora_clear_vision_extension.so",
                "lib/*/libagora_screen_capture_extension.so",
                "lib/*/libagora_spatial_audio_extension.so",
            )
        }
    }
}

// Al terminar assembleRelease con exito se sube la version para el siguiente
// build: el APK recien generado sale con la version actual y version.properties
// queda ya incrementado (1.0 -> 1.1, code 1 -> 2). Se usa doLast y no una tarea
// con finalizedBy porque doLast solo se ejecuta si el build termino bien; asi
// un build roto no gasta numeros de version. Solo aplica a release; los builds
// de debug desde Android Studio no tocan la version. La app es interna (sin
// Play Store), asi que el unico requisito real es que versionCode crezca para
// poder instalar cada actualizacion encima de la anterior.
afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val siguienteCode = appVersionCode + 1
            val partes = appVersionName.split(".")
            val siguienteName = partes.dropLast(1).joinToString(".") +
                    "." + (partes.last().toInt() + 1)
            versionPropertiesFile.writeText(
                "# Version de la app. No editar a mano salvo salto de version mayor (ej. 2.0):\n" +
                        "# assembleRelease la sube solo al terminar cada APK de release.\n" +
                        "VERSION_CODE=$siguienteCode\n" +
                        "VERSION_NAME=$siguienteName\n"
            )
            println("APK generado con version $appVersionName (code $appVersionCode). Proxima version: $siguienteName (code $siguienteCode)")
        }
    }
}

/**
 * Baja el modelo de Whisper y lo deja en los assets del APK. Se guarda una copia
 * en .gradle/ de la raiz (fuera de git) para que un clean no vuelva a bajar 57 MB.
 * Si el hash no coincide, el build falla: un modelo cambiado cambia lo que se
 * transcribe, y eso no puede pasar sin que nadie lo decida.
 */
abstract class DescargarModeloWhisper : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val nombre: Property<String>
    @get:Internal abstract val cache: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun descargar() {
        val guardado = cache.file("${nombre.get()}.bin").get().asFile
        if (!guardado.isFile || hash(guardado) != sha256.get()) {
            guardado.parentFile.mkdirs()
            val temporal = File(guardado.parentFile, guardado.name + ".part")
            logger.lifecycle("Bajando el modelo de Whisper ${nombre.get()}...")
            URI(url.get()).toURL().openStream().use { entrada ->
                temporal.outputStream().use { salida -> entrada.copyTo(salida) }
            }
            val obtenido = hash(temporal)
            if (obtenido != sha256.get()) {
                temporal.delete()
                throw GradleException("El modelo de Whisper no tiene el hash esperado: $obtenido")
            }
            guardado.delete()
            temporal.renameTo(guardado)
        }
        val destino = outputDir.file("whisper/${nombre.get()}.bin").get().asFile
        destino.parentFile.mkdirs()
        guardado.copyTo(destino, overwrite = true)
    }

    private fun hash(fichero: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fichero.inputStream().use { entrada ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val leidos = entrada.read(buffer)
                if (leidos < 0) break
                digest.update(buffer, 0, leidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val descargarModeloWhisper = tasks.register<DescargarModeloWhisper>("descargarModeloWhisper") {
    url.set(whisperModeloUrl)
    sha256.set(whisperModeloSha256)
    nombre.set(whisperModelo)
    cache.set(rootProject.layout.projectDirectory.dir(".gradle/whisper"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            descargarModeloWhisper,
            DescargarModeloWhisper::outputDir,
        )
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // Mapa tactico (fase 1 del port de Falcon One) y ubicacion fusionada.
    implementation(libs.mapbox.maps)
    implementation(libs.mapbox.maps.compose)
    implementation(libs.play.services.location)
    // Red tactica entre agentes (port de Falcon One): posiciones, SOS y livestream.
    // Se excluyen las extensiones opcionales del SDK (denoise, eco IA, audio
    // espacial, filtros de imagen...): no se usan y suman decenas de MB.
    implementation(libs.agora.full.sdk) {
        exclude(group = "io.agora.rtc", module = "full-content-inspect")
        exclude(group = "io.agora.rtc", module = "full-virtual-background")
        exclude(group = "io.agora.rtc", module = "full-screen-sharing")
        exclude(group = "io.agora.rtc", module = "full-vqa")
        exclude(group = "io.agora.rtc", module = "full-face-detect")
        exclude(group = "io.agora.rtc", module = "full-face-capture")
        exclude(group = "io.agora.rtc", module = "full-voice-drive")
        exclude(group = "io.agora.rtc", module = "full-video-av1-codec-enc")
        exclude(group = "io.agora.rtc", module = "full-video-av1-codec-dec")
    }
    // SDK oficial de las gafas BleeqUp (app/libs). Sin el, encender su punto de
    // acceso es imposible: la orden viaja por un GATT propietario. Un .aar no
    // trae metadatos de dependencias, asi que OkHttp y Gson —que usa por
    // dentro— hay que declararlos aqui a mano o falla en ejecucion.
    implementation(files("libs/bleequplibrary-release.aar"))
    implementation(libs.okhttp)
    implementation(libs.gson)
    // Video del telefono: original a 1080p con CameraX y copia de 720p con
    // Media3 Transformer, ambas de Jetpack. Ver docs/BACKEND-PROXY-AND-SOS.md.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    // Base de datos local: los incidents creados en campo sobreviven al cierre de la app.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}