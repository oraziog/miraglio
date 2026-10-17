// Serve l'import esplicito: dentro il blocco `android` la parola `java` e'
// l'estensione Java del progetto, quindi `java.util.Properties` non
// risolverebbe.
import java.util.Properties

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
        versionCode = 8
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Repository da cui l'app legge le release (owner/nome). Finisce nel
        // BuildConfig, quindi cambiando repository si cambia questa riga.
        buildConfigField("String", "UPDATE_REPO", "\"oraziog/miraglio\"")
    }

    // ---------------------------------------------------------------------
    // Firma: chiave di rilascio dedicata, custodita FUORI dal repository (in
    // %USERPROFILE%\.miraglio\) e referenziata da `keystore.properties`, che
    // e' in .gitignore. In CI arrivano le stesse quattro voci dalle variabili
    // d'ambiente impostate dal workflow, con i valori presi dai segreti del
    // repository.
    //
    // Senza nessuna configurazione la release esce NON FIRMATA
    // (`app-release-unsigned.apk`). Il ripiego sulla chiave di debug e' stato
    // tolto apposta: quella chiave non e' una credenziale di distribuzione
    // (chi la possiede puo' firmare un APK che il telefono accetta come
    // aggiornamento di Miraglio), e un APK firmato con una chiave diversa da
    // quella installata viene rifiutato in fase di aggiornamento: meglio un
    // file che non si installa affatto che una firma sbagliata da scoprire
    // dopo averlo pubblicato.
    // ---------------------------------------------------------------------
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    // I valori si ripuliscono dagli spazi ai bordi: un segreto di GitHub
    // caricato da file puo' arrivare con un a capo in coda, e una password con
    // dentro un a capo farebbe fallire la firma solo in CI.
    fun firmaDaProprieta(proprieta: String, variabile: String): String? =
        (keystoreProperties.getProperty(proprieta) ?: System.getenv(variabile))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    signingConfigs {
        // Configurazione a meta' (es. c'e' il percorso ma non la password) e'
        // un errore, non un motivo per firmare con qualcos'altro.
        firmaDaProprieta("storeFile", "KEYSTORE_PATH")?.let { percorso ->
            fun obbligatorio(proprieta: String, variabile: String): String =
                firmaDaProprieta(proprieta, variabile) ?: throw GradleException(
                    "Firma di rilascio incompleta: manca \"$proprieta\" in " +
                        "keystore.properties (o la variabile d'ambiente $variabile)."
                )
            create("release") {
                storeFile = File(percorso)
                storePassword = obbligatorio("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = obbligatorio("keyAlias", "KEY_ALIAS")
                keyPassword = obbligatorio("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Chiave dedicata se configurata, altrimenti firma nessuna.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Lingua scelta dentro l'app (Impostazioni -> Lingua, vedi AppLanguage).
    // Un App Bundle, per default, spezza le risorse per lingua: chi scarica
    // l'app riceve solo la lingua del telefono e una lingua scelta dopo non
    // c'e' (servirebbe la libreria Play Core per scaricarla). Qui tutte le
    // lingue restano dentro il modulo base, cosi' la scelta funziona anche se
    // un giorno Miraglio uscisse come AAB e non come APK.
    bundle {
        language {
            enableSplit = false
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
