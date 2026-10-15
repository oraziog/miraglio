package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.domain.format.ItalianFormat
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.ui.theme.OnPanel
import com.miraglio.ui.theme.PanelContainer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: PlaceDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val record = state.record

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(record?.label ?: "Luogo", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rinomina")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Elimina")
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
                Text("Luogo non disponibile")
                TextButton(onClick = onNavigateBack) { Text("Torna alla lista") }
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
                        contentDescription = "Foto del luogo",
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
                            if (record.kind == PlaceKind.GPS) "Misura GPS senza foto" else "Foto non disponibile",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            if (state.missingPhoto) {
                Text(
                    "Il file della foto è stato rimosso",
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
                        DetailRow("Distanza", ItalianFormat.meters(it))
                    }
                    record.horizontalDistanceM?.let {
                        DetailRow("Orizzontale", ItalianFormat.meters(it))
                    }
                    record.walkedM?.let {
                        DetailRow("Percorso a piedi", ItalianFormat.meters(it))
                    }
                    record.gpsErrorM?.let {
                        DetailRow("Errore stimato", "±${ItalianFormat.meters(it)}")
                    }
                    record.measuredSizeM?.let {
                        DetailRow("Bersaglio", ItalianFormat.meters(it, 2))
                    }
                    record.bearingDeg?.let {
                        DetailRow(
                            "Direzione",
                            "${ShareMessageComposer.compassLabel(it)} " +
                                "(${ItalianFormat.number(it, 0)}°)"
                        )
                    }
                    record.pitchDeg?.let {
                        DetailRow("Inclinazione", "${ItalianFormat.number(it, 1)}°")
                    }
                    // Le coordinate restano col punto decimale: sono dati da
                    // incollare in mappe e altre app, non valori da leggere.
                    record.targetFix?.let {
                        DetailRow("Bersaglio", coordinateRow(it))
                    }
                    record.startFix?.let {
                        DetailRow("Partenza", coordinateRow(it))
                    }
                    record.endFix?.let {
                        DetailRow("Arrivo", coordinateRow(it))
                    }
                    record.shotFix?.let {
                        DetailRow("Scatto", coordinateRow(it))
                    }
                    record.lensLabel?.let { DetailRow("Lente", it) }
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
                        "Naviga",
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
                        "Condividi",
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
            title = { Text("Eliminare il luogo?", fontWeight = FontWeight.Bold) },
            text = { Text("Verranno rimossi la foto e i dati della misura.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onDone = onNavigateBack)
                }) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Annulla") }
            }
        )
    }
}

/**
 * Riga di coordinate: numeri col punto decimale (si incollano in mappe e in
 * altre app) e accuratezza con la virgola, come tutto il resto del display.
 */
private fun coordinateRow(fix: GpsFix): String =
    String.format(Locale.US, "%.6f, %.6f", fix.latitudeDeg, fix.longitudeDeg) +
        " (±${ItalianFormat.number(fix.accuracyM, 0)} m)"

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
        title = { Text("QR del luogo", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = qrBitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR del luogo",
                        modifier = Modifier.size(260.dp)
                    )
                    content == null -> Text(
                        "Nessun contenuto da codificare",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
                if (content != null) {
                    Text(
                        text = if (content.startsWith("https://maps.google.com")) {
                            "Inquadralo con la fotocamera per aprire la mappa " +
                                "(funziona anche da iPhone)."
                        } else {
                            "Questo luogo non ha un fix GPS: il QR contiene " +
                                "il testo della misura."
                        },
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
            ) { Text("Condividi QR") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
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
        title = { Text("Rinomina luogo", fontWeight = FontWeight.Bold) },
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
            ) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
