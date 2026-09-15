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