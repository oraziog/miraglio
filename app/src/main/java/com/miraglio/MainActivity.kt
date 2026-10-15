package com.miraglio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.ui.navigation.MiraglioNavGraph
import com.miraglio.ui.theme.MiraglioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Il tema si sceglie qui, alla radice della UI: la modalita' outdoor e' una
    // preferenza globale, e attivandola cambia aspetto tutta l'app (anche il
    // colore delle barre di sistema).
    @Inject
    lateinit var settings: AppSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val outdoor by settings.outdoorModeEnabled.collectAsState()
            MiraglioTheme(outdoor = outdoor) {
                val navController = rememberNavController()
                MiraglioNavGraph(navController = navController)
            }
        }
    }
}
