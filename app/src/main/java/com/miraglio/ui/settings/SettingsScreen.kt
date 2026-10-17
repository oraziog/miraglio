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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.data.update.UpdateManager
import com.miraglio.domain.format.UnitSystem
import com.miraglio.i18n.AppLanguageChoice
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
    val portraitLocked by viewModel.portraitLocked.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
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
            LanguageCard(
                selected = appLanguage,
                onSelect = viewModel::setAppLanguage
            )
            MeasurementUnitsCard(
                selected = unitSystem,
                onSelect = viewModel::setUnitSystem
            )
            RecognitionCard(
                enabled = recognitionEnabled,
                onToggle = viewModel::setRecognitionEnabled
            )
            SettingSwitchCard(
                title = stringResource(R.string.settings_outdoor_title),
                description = stringResource(
                    if (outdoorMode) R.string.settings_outdoor_on
                    else R.string.settings_outdoor_off
                ),
                enabled = outdoorMode,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                onToggle = viewModel::setOutdoorMode
            )
            SettingSwitchCard(
                title = stringResource(R.string.settings_rotation_title),
                description = stringResource(
                    if (portraitLocked) R.string.settings_rotation_locked
                    else R.string.settings_rotation_free
                ),
                enabled = portraitLocked,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                onToggle = viewModel::setPortraitLock
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
                message = viewModel.installInviteMessage()
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
                text = stringResource(R.string.settings_install_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_install_description),
                style = MaterialTheme.typography.bodySmall,
                color = contentColorFor(containerColor).copy(alpha = 0.8f)
            )
            Button(onClick = { showInvite = true }) {
                Text(stringResource(R.string.settings_install_show))
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
            Text(
                stringResource(R.string.settings_install_title),
                fontWeight = FontWeight.Bold
            )
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
                        contentDescription =
                            stringResource(R.string.settings_install_qr_description),
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
                Text(
                    text = stringResource(R.string.settings_install_qr_hint),
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
                        Text(stringResource(R.string.settings_install_share_qr))
                    }
                }
            }
        },
        confirmButton = {
            // Il titolo del chooser si legge qui: dentro onClick si e' fuori dal
            // contesto Compose, dove stringResource non e' chiamabile.
            val shareTitle = stringResource(R.string.settings_install_share_title)
            TextButton(
                onClick = { ShareIntents.shareText(context, message, shareTitle) }
            ) {
                Text(stringResource(R.string.action_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
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
        is UpdateManager.State.Checking ->
            stringResource(R.string.settings_updates_action_checking)

        is UpdateManager.State.Available ->
            stringResource(R.string.action_download_install)

        is UpdateManager.State.Failed -> stringResource(R.string.action_retry)

        is UpdateManager.State.ReadyToInstall,
        is UpdateManager.State.PermissionRequired -> stringResource(R.string.action_install)

        is UpdateManager.State.Downloading ->
            stringResource(R.string.settings_updates_action_cancel)

        else -> stringResource(R.string.settings_updates_action_check)
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
                text = stringResource(R.string.settings_updates_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_updates_installed, installedVersion),
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

/**
 * Riga di stato dell'aggiornamento, in parole povere.
 *
 * E' `@Composable` perche' il testo vive nelle risorse: da una funzione
 * normale `stringResource` non e' chiamabile.
 */
@Composable
private fun updateStatusText(state: UpdateManager.State): String = when (state) {
    is UpdateManager.State.Idle -> stringResource(R.string.settings_updates_status_idle)
    is UpdateManager.State.Checking -> stringResource(R.string.settings_updates_status_checking)
    is UpdateManager.State.UpToDate -> stringResource(R.string.settings_updates_status_uptodate)
    is UpdateManager.State.Available -> stringResource(
        R.string.settings_updates_status_available,
        state.release.displayVersion
    )

    is UpdateManager.State.Downloading -> stringResource(
        R.string.settings_updates_status_downloading,
        state.release.displayVersion
    )

    is UpdateManager.State.ReadyToInstall -> stringResource(
        R.string.settings_updates_status_ready,
        state.release.displayVersion
    )

    is UpdateManager.State.PermissionRequired ->
        stringResource(R.string.settings_updates_status_permission)

    is UpdateManager.State.Failed ->
        stringResource(R.string.settings_updates_status_failed, state.message)

    is UpdateManager.State.CheckFailed ->
        stringResource(R.string.settings_updates_status_checkfailed, state.message)
}

/** Una voce di una card di scelta: etichetta gia' tradotta e se e' quella attiva. */
private data class ChoiceRow(val label: String, val selected: Boolean)

/**
 * Card con un elenco di scelte esclusive, con il pallino di fianco.
 *
 * La usano la lingua e il sistema di unita': due scelte diverse (una riguarda i
 * testi, l'altra i numeri) ma fatte allo stesso modo, e con una forma sola si
 * comportano allo stesso modo anche per chi non vede lo schermo.
 */
@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    rows: List<ChoiceRow>,
    onSelect: (Int) -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = contentColorFor(containerColor).copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            rows.forEachIndexed { index, row ->
                // La riga intera e' selezionabile e il pallino non ha callback
                // proprio (come le righe con interruttore): il tocco non
                // scatta due volte e il lettore di schermo legge una voce sola.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = row.selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = row.selected, onClick = null)
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Lingua dell'app: la scelta dell'utente o quella del telefono.
 *
 * I nomi delle lingue sono tradotti come tutto il resto ("Italian" in inglese,
 * "Italiano" in italiano, "Italiano/Español/Inglés" in spagnolo), ma restano
 * riconoscibili: chi cerca la sua lingua la trova comunque.
 */
@Composable
private fun LanguageCard(
    selected: AppLanguageChoice,
    onSelect: (AppLanguageChoice) -> Unit
) {
    val choices = AppLanguageChoice.entries
    ChoiceCard(
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_description),
        rows = choices.map { ChoiceRow(languageLabel(it), it == selected) },
        onSelect = { onSelect(choices[it]) }
    )
}

/**
 * Sistema di unita' delle distanze, delle dimensioni e dell'incertezza.
 *
 * Sta accanto alla lingua ma **non la segue**: un inglese che tira in Italia
 * misura in metri, un italiano a un campo americano in iarde. Il testo dice
 * cosa cambia, cosi' non serve provarlo per capirlo.
 */
@Composable
private fun MeasurementUnitsCard(
    selected: UnitSystem,
    onSelect: (UnitSystem) -> Unit
) {
    val choices = UnitSystem.entries
    ChoiceCard(
        title = stringResource(R.string.settings_units_title),
        description = stringResource(R.string.settings_units_description),
        rows = choices.map { ChoiceRow(unitLabel(it), it == selected) },
        onSelect = { onSelect(choices[it]) }
    )
}

/**
 * Nome della voce, tradotto: "come il telefono" cambia con la lingua, i nomi
 * delle lingue no.
 *
 * E' `@Composable` perche' il testo vive nelle risorse: da una funzione
 * normale `stringResource` non e' chiamabile.
 */
@Composable
private fun languageLabel(choice: AppLanguageChoice): String = when (choice) {
    AppLanguageChoice.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguageChoice.ITALIAN -> stringResource(R.string.settings_language_italian)
    AppLanguageChoice.SPANISH -> stringResource(R.string.settings_language_spanish)
    AppLanguageChoice.ENGLISH -> stringResource(R.string.settings_language_english)
}

/** Nome del sistema di unita': anche qui le sigle (m, cm, yd, in) non si traducono. */
@Composable
private fun unitLabel(unit: UnitSystem): String = when (unit) {
    UnitSystem.METRIC -> stringResource(R.string.settings_units_metric)
    UnitSystem.IMPERIAL -> stringResource(R.string.settings_units_imperial)
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
        title = stringResource(R.string.settings_recognition_title),
        description = stringResource(
            if (enabled) R.string.settings_recognition_on
            else R.string.settings_recognition_off
        ),
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
