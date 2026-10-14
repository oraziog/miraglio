plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.miraglio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miraglio"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Notifiche "e' disponibile una versione piu' recente" delle dipendenze:
        // non sono difetti; l'aggiornamento massivo del toolchain e' una
        // decisione a parte, non un fix di qualita' del codice.
        disable += listOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable"
        )
        // Icona adattiva in mipmap-anydpi-v26 anche se minSdk=26: e' la
        // configurazione che AAPT2 accetta per <adaptive-icon>.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Core
    implementation(libs.core.ktx)

    // Lifecycle & Navigation
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ONNX Runtime
    implementation(libs.onnxruntime)

    // ML Kit OCR
    implementation(libs.mlkit.text.recognition)

    // QR (solo generazione, libreria pura Java)
    implementation(libs.zxing.core)

    // EXIF GPS delle foto dei luoghi
    implementation(libs.androidx.exifinterface)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
