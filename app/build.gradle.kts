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
        // La versione deve alzarsi a ogni release pubblicata: l'app confronta
        // la propria con l'etichetta della release piu' recente su GitHub e
        // propone l'aggiornamento solo se quella e' piu' alta. Etichetta e
        // versione vanno mantenute allineate (tag v1.0.2 = versionName 1.0.2).
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Repository da cui l'app legge le release (owner/nome). Finisce nel
        // BuildConfig, quindi cambiando repository si cambia questa riga.
        buildConfigField("String", "UPDATE_REPO", "\"oraziog/miraglio\"")
    }

    // Firma: si riusa la chiave di debug della macchina, la stessa con cui
    // l'app e' gia' installata sul telefono. Non e' una scelta di comodo: una
    // chiave diversa renderebbe impossibile aggiornare, perche' Android
    // rifiuta un APK firmato da altri e obbliga a disinstallare (perdendo
    // luoghi e calibrazioni). Il prezzo e' che quella chiave non e' segreta,
    // quindi chiunque potrebbe firmare un APK che il telefono accetterebbe
    // come aggiornamento di questo: accettabile per un app personale, da
    // cambiare con una chiave dedicata se un giorno la distribuisci ad altri.
    //
    // In CI la chiave arriva dai segreti del repository (variabili d'ambiente
    // impostate dal workflow), e i valori predefiniti sono quelli noti della
    // chiave di debug di Android.
    signingConfigs {
        create("release") {
            val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
            storeFile = File(System.getenv("KEYSTORE_PATH") ?: debugKeystore.path)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        // AGP aggancia gia' lint al task `check` (check -> lint -> lintDebug):
        // qui si alza solo la soglia. Con warningsAsErrors qualsiasi
        // segnalazione di livello warning fa fallire `gradle check`, cosi' un
        // problema non puo' piu' passare inosservato in una build di verifica.
        // Le regole disattivate sotto restano fuori dal conteggio.
        warningsAsErrors = true

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

    // Test: solo il confronto fra versioni e la lettura della risposta di
    // GitHub, cioe' le due cose che decidono se e come l'app si aggiorna.
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
