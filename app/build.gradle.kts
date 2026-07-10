import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
val bodycamMac: String = localProperties.getProperty("BODYCAM_MAC") ?: ""

android {
    namespace = "com.delta.aeria_nexus_prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.delta.aeria_nexus_prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")
        buildConfigField("String", "AGORA_APP_ID", "\"$agoraAppId\"")
        buildConfigField("String", "BODYCAM_MAC", "\"$bodycamMac\"")
    }

    buildTypes {
        release {
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}