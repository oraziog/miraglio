package com.miraglio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.data.update.UpdateManager
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
            MiraglioTheme(outdoor = outdoor) {
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
