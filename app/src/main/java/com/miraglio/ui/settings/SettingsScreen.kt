package com.miraglio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val recognitionEnabled by viewModel.recognitionEnabled.collectAsState()
    val outdoorMode by viewModel.outdoorMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RecognitionCard(
                enabled = recognitionEnabled,
                onToggle = viewModel::setRecognitionEnabled
            )
            SettingSwitchCard(
                title = "Modalita' outdoor",
                description = if (outdoorMode) {
                    "Attiva: sfondo nero, testi gialli e ingranditi, pannelli sopra " +
                        "l'immagine in nero pieno. Si spegne anche dal bottone in alto " +
                        "nella schermata di mira."
                } else {
                    "Spenta: colori normali. Attivala quando tiri all'aperto, per " +
                        "leggere a colpo d'occhio anche con il sole battente o gli " +
                        "occhiali da sole polarizzati."
                },
                enabled = outdoorMode,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                onToggle = viewModel::setOutdoorMode
            )
        }
    }
}

/**
 * Riconoscimento dei bersagli: card con il colore dedicato del tema.
 */
@Composable
private fun RecognitionCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SettingSwitchCard(
        title = "Riconoscimento paglione",
        description = if (enabled) {
            "Attivo: quando scatti il fermo immagine i bersagli riconosciuti " +
                "vengono riquadrati in verde e le linee orizzontali si posizionano " +
                "da sole agli estremi del bersaglio piu' grande."
        } else {
            "Spento: nessun riquadro e linee centrate, esattamente come sempre. " +
                "Il modello di riconoscimento non viene caricato in memoria."
        },
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        onToggle = onToggle
    )
}

/** Riga di impostazione con interruttore, su una card del colore indicato. */
@Composable
private fun SettingSwitchCard(
    title: String,
    description: String,
    enabled: Boolean,
    containerColor: Color,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        // L'intera riga e' un unico controllo accessibile (toggleable + Switch
        // senza callback proprio): il tocco non scatta due volte e la
        // descrizione viene letta insieme all'interruttore.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColorFor(containerColor).copy(alpha = 0.8f)
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
