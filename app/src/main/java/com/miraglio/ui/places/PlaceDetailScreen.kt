package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.theme.OnPanel
import com.miraglio.ui.theme.PanelContainer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: PlaceDetailViewModel = hiltViewModel()
) {
    val format = LocalDisplayFormat.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val record = state.record

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    // Tap lungo su una riga di coordinate: lat, lon negli appunti, senza
    // l'accuratezza attaccata (i due numeri si incollano in un'altra app; il
    // "±3 m" no). L'esito si dice con uno snackbar breve, in basso.
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val coordinatesCopiedMessage = stringResource(R.string.place_coordinates_copied)
    val onCopyCoordinates: (GpsFix) -> Unit = { fix ->
        clipboard.setText(
            AnnotatedString(
                String.format(Locale.US, "%.6f, %.6f", fix.latitudeDeg, fix.longitudeDeg)
            )
        )
        coroutineScope.launch {
            snackbarHostState.showSnackbar(coordinatesCopiedMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        record?.label ?: stringResource(R.string.place_untitled),
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
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.place_action_rename)
                        )
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (record == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.place_detail_unavailable))
                TextButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.place_detail_back_to_list))
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Foto (o segnaposto per le misure GPS)
            val thumb = state.thumbnail
            if (thumb != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = stringResource(R.string.place_detail_photo),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(
                                if (record.kind == PlaceKind.GPS) R.string.place_detail_gps_no_photo
                                else R.string.place_detail_no_photo
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            if (state.missingPhoto) {
                Text(
                    stringResource(R.string.place_detail_photo_removed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Dati della misura
            Card(colors = CardDefaults.cardColors(containerColor = PanelContainer)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    record.distanceM?.let {
                        DetailRow(stringResource(R.string.detail_distance), format.distance(it))
                    }
                    record.horizontalDistanceM?.let {
                        DetailRow(
                            stringResource(R.string.detail_horizontal),
                            format.distance(it)
                        )
                    }
                    record.walkedM?.let {
                        DetailRow(stringResource(R.string.detail_walked), format.distance(it))
                    }
                    record.gpsErrorM?.let {
                        DetailRow(
                            stringResource(R.string.detail_error),
                            "±${format.accuracy(it)}"
                        )
                    }
                    // La dimensione misurata e' la grandezza del bersaglio, non
                    // una distanza: metri o pollici, come sul campo della mira.
                    record.measuredSizeM?.let {
                        DetailRow(
                            stringResource(R.string.detail_target),
                            format.objectSize(it)
                        )
                    }
                    record.bearingDeg?.let {
                        DetailRow(
                            stringResource(R.string.detail_direction),
                            "${stringResource(ShareMessageComposer.compassLabelRes(it))} " +
                                "(${format.number(it, 0)}°)"
                        )
                    }
                    record.pitchDeg?.let {
                        DetailRow(
                            stringResource(R.string.detail_pitch),
                            "${format.number(it, 1)}°"
                        )
                    }
                    // Le coordinate restano col punto decimale: sono dati da
                    // incollare in mappe e altre app, non valori da leggere.
                    // E si copiano col TAP LUNGO sulla riga: lat, lon soli,
                    // pronti per essere incollati altrove.
                    record.targetFix?.let {
                        CoordinateRow(stringResource(R.string.detail_target), it, onCopyCoordinates)
                    }
                    record.startFix?.let {
                        CoordinateRow(stringResource(R.string.detail_start), it, onCopyCoordinates)
                    }
                    record.endFix?.let {
                        CoordinateRow(stringResource(R.string.detail_arrival), it, onCopyCoordinates)
                    }
                    record.shotFix?.let {
                        CoordinateRow(stringResource(R.string.places_kind_shot), it, onCopyCoordinates)
                    }
                    // Il tap lungo e' un gesto che non si vede: senza questa
                    // riga nessuno lo scoprirebbe.
                    if (record.hasAnyPin) {
                        Text(
                            stringResource(R.string.place_coordinates_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnPanel.copy(alpha = 0.7f)
                        )
                    }
                    record.lensLabel?.let { DetailRow(stringResource(R.string.detail_lens), it) }
                }
            }

            // Nota libera: quello che dopo una settimana non si ricorda piu'
            // ("facciata nord", "misurato con zoom 2x", "dubbio sulla distanza").
            // Resta qui dentro: non entra nell'etichetta, ne' nella condivisione.
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.place_notes_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedTextField(
                        value = state.notesDraft,
                        onValueChange = viewModel::onNotesChange,
                        placeholder = { Text(stringResource(R.string.place_notes_hint)) },
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.saveNotes() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Salvato" resta finche' non si ritocca il campo: e' la
                        // conferma che il testo qui sotto e' quello su disco.
                        if (state.notesSaved) {
                            Text(
                                stringResource(R.string.places_action_saved),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(
                            onClick = viewModel::saveNotes,
                            // Nessuna differenza dal disco: non c'e' niente da
                            // scrivere, quindi non c'e' un pulsante premibile.
                            enabled = state.notesDraft.trim() != record.notes,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.places_action_save))
                        }
                    }
                }
            }

            // Azioni principali. Padding orizzontale ridotto + testo su una
            // sola riga: con il padding di default (24.dp per lato) "Naviga"
            // e "Condividi" andavano a capo ("Navi\nga").
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                // Stesso ordine di preferenza del pin QR: qualsiasi fix
                // presente su questo luogo basta per navigarci sopra.
                val navFix = record.targetFix
                    ?: record.endFix
                    ?: record.shotFix
                    ?: record.startFix
                FilledTonalButton(
                    onClick = {
                        navFix?.let {
                            com.miraglio.ui.places.ShareIntents.navigateTo(
                                context, it.latitudeDeg, it.longitudeDeg
                            )
                        }
                    },
                    enabled = navFix != null,
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null)
                    Text(
                        stringResource(R.string.place_action_navigate),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val message = state.shareMessage
                        val uri = state.shareImageUri
                        if (message != null) {
                            if (uri != null) {
                                ShareIntents.shareImageAndText(context, uri, message)
                            } else {
                                ShareIntents.shareText(context, message)
                            }
                        }
                    },
                    enabled = state.shareMessage != null,
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(R.string.action_share),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                FilledTonalButton(
                    onClick = { showQr = true },
                    enabled = state.qrContent != null,
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "QR",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // QR condivisibile: finestra modale. Il blocco inline compariva
            // in fondo alla colonna, sotto la piega dello schermo, e il tocco
            // sembrava non fare nulla.
            if (showQr) {
                QrDialog(
                    content = state.qrContent,
                    onDismiss = { showQr = false }
                )
            }
        }
    }

    if (showRename && record != null) {
        RenameDialog(
            currentLabel = record.label,
            onConfirm = {
                viewModel.rename(it)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = {
                Text(stringResource(R.string.place_delete_title), fontWeight = FontWeight.Bold)
            },
            text = { Text(stringResource(R.string.place_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onDone = onNavigateBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Riga di coordinate: numeri col punto decimale (si incollano in mappe e in
 * altre app) e accuratezza con la virgola, come tutto il resto del display.
 */
@Composable
private fun coordinateRow(fix: GpsFix): String {
    val format = LocalDisplayFormat.current
    val accuracy = stringResource(
        R.string.share_accuracy,
        format.accuracy(fix.accuracyM, ShareMessageComposer.accuracyDecimals(fix.accuracyM))
    )
    return String.format(Locale.US, "%.6f, %.6f", fix.latitudeDeg, fix.longitudeDeg) +
        " ($accuracy)"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // La card ha sfondo bianco semitrasparente: il colore di tema
        // ereditato era un grigio poco leggibile, come nella card di
        // "Verifica distanza".
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnPanel)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = OnPanel
        )
    }
}

/**
 * Riga di coordinate con sopra il **tap lungo**, che le copia negli appunti.
 *
 * Il gesto non ha nessun indizio visivo (niente freccia, niente icona):
 * per questo la card mette sotto una riga che lo dice, e il copia avviene
 * sull'intera riga e non sulle cifre — mirare a due numeri da sei cifre non e'
 * un bersaglio da usare fuori, con le mani fredde.
 *
 * Si copiano solo lat, lon (sei cifre, punto decimale): l'accuratezza resta
 * scritta sulla riga ma non entra negli appunti, perche' chi copia le
 * coordinate le vuole incollare in un'altra app, non dentro una frase.
 */
@Composable
private fun CoordinateRow(
    label: String,
    fix: GpsFix,
    onCopy: (GpsFix) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(fix) {
                detectTapGestures(onLongPress = { onCopy(fix) })
            },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OnPanel)
        Text(
            coordinateRow(fix),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = OnPanel
        )
    }
}

/**
 * Finestra del QR, stessa esperienza della schermata di misura: QR grande,
 * spinner durante la generazione, "Condividi QR" e una didascalia che dice
 * cosa contiene (mappa oppure testo della misura, senza fix GPS).
 */
@Composable
private fun QrDialog(
    content: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content) {
        qrBitmap = if (content == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { QrCodeRenderer.render(content, 512) }.getOrNull()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.place_qr_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = qrBitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.place_qr_title),
                        modifier = Modifier.size(260.dp)
                    )
                    content == null -> Text(
                        stringResource(R.string.place_qr_no_content),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
                if (content != null) {
                    Text(
                        text = stringResource(
                            if (content.startsWith("https://maps.google.com")) {
                                R.string.place_detail_qr_hint_map
                            } else {
                                R.string.place_detail_qr_hint_text
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { qrBitmap?.let { QrShareHelper.shareQr(context, it) } },
                enabled = qrBitmap != null
            ) { Text(stringResource(R.string.action_share_qr_title)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun RenameDialog(
    currentLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.place_rename_title), fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.places_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
