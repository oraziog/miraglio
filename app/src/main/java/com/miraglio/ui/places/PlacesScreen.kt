package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.history.HistoryEntry
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.ui.i18n.LocalDisplayFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    onNavigateBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel()
) {
    val places by viewModel.places.collectAsState()
    val history by viewModel.history.collectAsState()

    // Stesso gesto del dettaglio, ma dal riepilogo: si copia il punto che
    // rappresenta il luogo (bersaglio o arrivo) senza aprirlo.
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.place_coordinates_copied)
    val onCopyCoordinates: (PlaceRecord) -> Unit = { record ->
        record.coordinatesText()?.let { testo ->
            clipboard.setText(AnnotatedString(testo))
            coroutineScope.launch { snackbarHostState.showSnackbar(copiedMessage) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.places_title), fontWeight = FontWeight.Bold)
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
        // Svuota quando NON c'e' niente da nessuna parte. Con le misure
        // automatiche il caso "luoghi zero" non e' piu' il caso vuoto: la
        // cronologia tiene comunque i numeri che l'utente non ha salvato.
        if (places.isEmpty() && history.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.places_empty_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.places_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (history.isNotEmpty()) {
                    item(key = "history") {
                        HistorySection(
                            entries = history,
                            onClear = viewModel::clearHistory
                        )
                    }
                }
                items(places, key = { it.id }) { record ->
                    PlaceListItem(
                        record = record,
                        thumbnail = viewModel,
                        onClick = { onOpenPlace(record.id) },
                        onLongClick = { onCopyCoordinates(record) }
                    )
                }
            }
        }
    }
}

/**
 * Le ultime misure: quelle che l'app ha registrato **da sole**, senza che
 * nessuno premesse "Salva".
 *
 * Sta in cima alla schermata e sopra i Luoghi per due motivi: e' la cosa che
 * l'utente ha appena perso di vista, ed e' l'unica che sparisce da sola (capo
 * fisso, [HistoryBuffer][com.miraglio.data.history.HistoryBuffer]): il
 * pulsante qui sotto serve a liberare il buffer, non a salvare i Luoghi.
 *
 * Un tap sulla riga **condivide** la misura: e' l'uscita per chi non ha
 * premuto Salva e vuole comunque mandare il numero.
 */
@Composable
private fun HistorySection(
    entries: List<HistoryEntry>,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val format = LocalDisplayFormat.current
    val sharePrefix = stringResource(R.string.history_share_prefix)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                onClick = onClear,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 0.dp
                )
            ) {
                Text(
                    stringResource(R.string.history_clear),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        entries.forEach { entry ->
            val kindLabel = stringResource(
                if (entry.kind == PlaceKind.GPS) R.string.places_kind_gps
                else R.string.places_kind_shot
            )
            val distance = format.distance(entry.distanceM)
            val error = entry.errorM?.let { " (±${format.accuracy(it)})" }.orEmpty()
            val moment = format.dateTime(entry.atMs)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ShareIntents.shareText(
                            context,
                            "$sharePrefix: $distance$error • $moment • $kindLabel"
                        )
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            moment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "$distance$error • $kindLabel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceListItem(
    record: PlaceRecord,
    thumbnail: PlacesViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val format = LocalDisplayFormat.current
    var thumb by remember(record.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id) {
        thumb = thumbnail.thumbnail(record)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.place_coordinates_hint),
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            val currentThumb = thumb
            if (currentThumb != null) {
                Image(
                    bitmap = currentThumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp)
                )
            } else {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val distance = record.distanceM
                    ?: record.horizontalDistanceM
                    ?: record.walkedM
                val kindLabel = stringResource(
                    if (record.kind == PlaceKind.GPS) R.string.places_kind_gps
                    else R.string.places_kind_shot
                )
                Text(
                    text = buildString {
                        append(kindLabel)
                        distance?.let { append("  ·  ${format.distance(it)}") }
                        // Precisione della misura GPS: il numero va bene
                        // anche se incerto, ma l'incertezza si dichiara.
                        record.gpsErrorM?.let {
                            append(
                                "  ·  " + stringResource(
                                    R.string.share_accuracy,
                                    format.accuracy(
                                        it,
                                        ShareMessageComposer.accuracyDecimals(it)
                                    )
                                )
                            )
                        }
                        record.bearingDeg?.let {
                            append("  ·  ${stringResource(ShareMessageComposer.compassLabelRes(it))}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                // Anteprima della nota: l'etichetta e' solo un nome, e quello
                // che si e' scritto dopo una settimana non si ricorda piu'.
                if (record.notes.isNotBlank()) {
                    Text(
                        text = record.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
