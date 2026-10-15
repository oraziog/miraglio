package com.miraglio.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.data.update.UpdateManager
import com.miraglio.ui.places.QrShareHelper
import com.miraglio.ui.places.ShareIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val recognitionEnabled by viewModel.recognitionEnabled.collectAsState()
    val outdoorMode by viewModel.outdoorMode.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

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
            UpdateCard(
                state = updateState,
                installedVersion = viewModel.installedVersionName,
                onCheck = viewModel::checkForUpdates,
                onDownload = viewModel::downloadUpdate,
                onCancelDownload = viewModel::cancelDownload,
                onInstall = viewModel::installUpdate
            )
            InstallCard(
                pageUrl = viewModel.installPageUrl,
                message = viewModel.installInviteMessage
            )
        }
    }
}

/**
 * Invito per un altro arciere: link e QR della release piu' recente, da far
 * inquadrare con il telefono di chi deve installarla. Serve anche a se stessi,
 * quando si cambia telefono.
 */
@Composable
private fun InstallCard(
    pageUrl: String,
    message: String
) {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    var showInvite by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Installa su un altro telefono",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Mostra il QR e il link della release: l'altro arciere li " +
                    "apre dal suo telefono e installa Miraglio. Utile anche per te, " +
                    "se cambi telefono.",
                style = MaterialTheme.typography.bodySmall,
                color = contentColorFor(containerColor).copy(alpha = 0.8f)
            )
            Button(onClick = { showInvite = true }) {
                Text("Mostra QR e link")
            }
        }
    }

    if (showInvite) {
        InstallDialog(
            pageUrl = pageUrl,
            message = message,
            onDismiss = { showInvite = false }
        )
    }
}

/**
 * QR del link, link in chiaro e le due condivisioni: il messaggio per chi e'
 * lontano, il QR come immagine per chi e' li'.
 */
@Composable
private fun InstallDialog(
    pageUrl: String,
    message: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Il QR si disegna fuori dal thread della UI: e' una griglia di quadrati
    // da 512 px, non un disegno da tenere nel disegno del frame.
    LaunchedEffect(pageUrl) {
        qrBitmap = withContext(Dispatchers.Default) {
            runCatching { QrCodeRenderer.render(pageUrl, 512) }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Installa su un altro telefono", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = qrBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR della release piu' recente",
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
                Text(
                    text = "Inquadralo con il telefono dell'altro arciere: si " +
                        "apre la pagina della release, con le istruzioni e l'APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                // Il link resta anche scritto, e copiabile: c'e' chi preferisce
                // mandarlo per messaggio invece di far inquadrare uno schermo.
                SelectionContainer {
                    Text(
                        text = pageUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (bmp != null) {
                    TextButton(onClick = { QrShareHelper.shareQr(context, bmp) }) {
                        Text("Condividi il QR come immagine")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { ShareIntents.shareText(context, message, "Condividi invito") }
            ) {
                Text("Condividi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    )
}

/**
 * Aggiornamenti: versione installata, esito dell'ultimo controllo e un solo
 * bottone che fa la cosa giusta per lo stato in cui si trova.
 */
@Composable
private fun UpdateCard(
    state: UpdateManager.State,
    installedVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val secondaryText = contentColorFor(containerColor).copy(alpha = 0.8f)

    val action: (() -> Unit)? = when (state) {
        is UpdateManager.State.Checking -> null
        is UpdateManager.State.Available,
        is UpdateManager.State.Failed -> onDownload

        is UpdateManager.State.ReadyToInstall,
        is UpdateManager.State.PermissionRequired -> onInstall

        is UpdateManager.State.Downloading -> onCancelDownload
        else -> onCheck
    }
    val actionLabel = when (state) {
        is UpdateManager.State.Checking -> "Controllo in corso..."
        is UpdateManager.State.Available -> "Scarica e installa"
        is UpdateManager.State.Failed -> "Riprova"
        is UpdateManager.State.ReadyToInstall,
        is UpdateManager.State.PermissionRequired -> "Installa"

        is UpdateManager.State.Downloading -> "Annulla download"
        else -> "Controlla aggiornamenti"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Aggiornamenti",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Versione installata: $installedVersion",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText
            )
            Text(
                text = updateStatusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state is UpdateManager.State.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    secondaryText
                }
            )
            if (state is UpdateManager.State.Downloading) {
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Button(
                onClick = { action?.invoke() },
                enabled = action != null
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Riga di stato dell'aggiornamento, in parole povere. */
private fun updateStatusText(state: UpdateManager.State): String = when (state) {
    is UpdateManager.State.Idle -> "Non ancora controllato in questa sessione."
    is UpdateManager.State.Checking -> "Chiedo a GitHub se c'e' una versione nuova..."
    is UpdateManager.State.UpToDate -> "Sei all'ultima versione."
    is UpdateManager.State.Available -> "Disponibile la versione " +
        "${state.release.displayVersion}."

    is UpdateManager.State.Downloading -> "Sto scaricando la versione " +
        "${state.release.displayVersion}..."

    is UpdateManager.State.ReadyToInstall -> "Versione ${state.release.displayVersion} " +
        "scaricata, pronta da installare."

    is UpdateManager.State.PermissionRequired -> "Prima concedi ad Android il " +
        "permesso di installare app da Miraglio, poi premi Installa."

    is UpdateManager.State.Failed -> "L'aggiornamento non e' riuscito: ${state.message}"
    is UpdateManager.State.CheckFailed -> "Controllo non riuscito: ${state.message}"
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
