package com.miraglio

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.data.update.UpdateManager
import com.miraglio.i18n.AppLanguage
import com.miraglio.i18n.languageAwareBaseContext
import com.miraglio.ui.i18n.ProvideDisplayFormat
import com.miraglio.ui.navigation.MiraglioNavGraph
import com.miraglio.ui.theme.MiraglioTheme
import com.miraglio.ui.update.UpdateDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Il tema si sceglie qui, alla radice della UI: la modalita' outdoor e' una
    // preferenza globale, e attivandola cambia aspetto tutta l'app (anche il
    // colore delle barre di sistema).
    @Inject
    lateinit var settings: AppSettingsStore

    // L'aggiornamento e' globale come il tema: il dialog e' disegnato qui,
    // sopra la schermata in uso, cosi' la versione nuova si puo' installare
    // anche stando nel mezzo di una misura.
    @Inject
    lateinit var updates: UpdateManager

    // La lingua scelta nelle Impostazioni: sotto Android 13 va applicata qui,
    // perche' il sistema non la conosce (vedi attachBaseContext).
    @Inject
    lateinit var language: AppLanguage

    /**
     * La lingua dell'activity si decide prima di onCreate: sotto Android 13
     * avvolgendo il contesto di base con la lingua scelta, da Android 13 in poi
     * lasciando fare al sistema (che riscrive la configurazione dell'app).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(languageAwareBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Controllo silenzioso: se c'e' una versione nuova si apre il dialog,
        // altrimenti non si vede niente (senza rete non c'e' nessun errore da
        // mostrare, si riprova al prossimo avvio o dal bottone in Impostazioni).
        updates.checkInBackground()

        setContent {
            val outdoor by settings.outdoorModeEnabled.collectAsState()
            val updateState by updates.state.collectAsState()
            val showUpdateDialog by updates.showDialog.collectAsState()
            // Sistema di unita' scelto nelle Impostazioni: si legge qui, come il
            // tema, e si passa al formattatore di tutta la UI. Cambiarlo
            // ricompone da solo le schermate che mostrano distanze.
            val unitSystem by settings.unitSystem.collectAsState()
            // Rotazione: la scelta dell'utente si applica qui, in un posto solo,
            // come il tema. Il manifest dichiara il portrait (il default) e
            // questo lo scavalca a runtime; UNSPECIFIED vuol dire "decide il
            // sistema", compreso l'interruttore di rotazione automatica del
            // telefono. Non si usa LOCKED, che bloccherebbe sull'orientamento
            // in cui il telefono si trova in quel momento — cioe' anche in
            // orizzontale se l'utente sta gia' cosi'.
            val portraitLocked by settings.portraitLocked.collectAsState()
            LaunchedEffect(portraitLocked) {
                requestedOrientation = if (portraitLocked) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            MiraglioTheme(outdoor = outdoor) {
                // Numeri, date e lunghezze nella lingua e nelle unita' scelte:
                // si fornisce una volta qui, alla radice, e vale per tutte le
                // schermate.
                ProvideDisplayFormat(unitSystem = unitSystem) {
                    // Sotto Android 13 il sistema non sa nulla della lingua
                    // scelta qui: la nuova lingua entra in vigore ricreando
                    // l'activity, che si rilegge la preferenza. Da Android 13
                    // in poi questo flusso resta muto, perche' le activity le
                    // ricrea il sistema e farlo due volte sarebbe uno
                    // sfarfallio.
                    LaunchedEffect(Unit) {
                        language.needsRestart.collect { recreate() }
                    }
                    val navController = rememberNavController()
                    MiraglioNavGraph(navController = navController)
                    if (showUpdateDialog) {
                        UpdateDialog(
                            state = updateState,
                            installedVersion = updates.installedVersionName,
                            onDownload = updates::download,
                            onInstall = updates::install,
                            onCancelDownload = updates::cancelDownload,
                            onPostpone = updates::postpone
                        )
                    }
                }
            }
        }
    }
}
