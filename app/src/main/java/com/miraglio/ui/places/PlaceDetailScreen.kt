package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.miraglio.data.places.PlaceKind
import com.miraglio.domain.share.ShareMessageComposer
import java.util.Locale

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
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xB3FFFFFF))) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    record.distanceM?.let {
                        DetailRow("Distanza", String.format(Locale.US, "%.1f m", it))
                    }
                    record.horizontalDistanceM?.let {
                        DetailRow("Orizzontale", String.format(Locale.US, "%.1f m", it))
                    }
                    record.walkedM?.let {
                        DetailRow("Percorso a piedi", String.format(Locale.US, "%.1f m", it))
                    }
                    record.measuredSizeM?.let {
                        DetailRow("Bersaglio", String.format(Locale.US, "%.2f m", it))
                    }
                    record.bearingDeg?.let {
                        DetailRow(
                            "Direzione",
                            "${ShareMessageComposer.compassLabel(it)} (${String.format(Locale.US, "%.0f°", it)})"
                        )
                    }
                    record.pitchDeg?.let {
                        DetailRow("Inclinazione", String.format(Locale.US, "%.1f°", it))
                    }
                    record.targetFix?.let {
                        DetailRow(
                            "Bersaglio",
                            String.format(Locale.US, "%.6f, %.6f (±%.0f m)", it.latitudeDeg, it.longitudeDeg, it.accuracyM)
                        )
                    }
                    record.startFix?.let {
                        DetailRow(
                            "Partenza",
                            String.format(Locale.US, "%.6f, %.6f (±%.0f m)", it.latitudeDeg, it.longitudeDeg, it.accuracyM)
                        )
                    }
                    record.endFix?.let {
                        DetailRow(
                            "Arrivo",
                            String.format(Locale.US, "%.6f, %.6f (±%.0f m)", it.latitudeDeg, it.longitudeDeg, it.accuracyM)
                        )
                    }
                    record.shotFix?.let {
                        DetailRow(
                            "Scatto",
                            String.format(Locale.US, "%.6f, %.6f (±%.0f m)", it.latitudeDeg, it.longitudeDeg, it.accuracyM)
                        )
                    }
                    record.lensLabel?.let { DetailRow("Lente", it) }
                }
            }

            // Azioni principali
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val navFix = record.targetFix
                    ?: record.endFix
                    ?: record.shotFix
                FilledTonalButton(
                    onClick = {
                        navFix?.let {
                            com.miraglio.ui.places.ShareIntents.navigateTo(
                                context, it.latitudeDeg, it.longitudeDeg
                            )
                        }
                    },
                    enabled = navFix != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null)
                    Text("  Naviga")
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Condividi")
                }
                FilledTonalButton(
                    onClick = { showQr = true },
                    enabled = state.qrContent != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("QR")
                }
            }

            // QR condivisibile
            val qr = state.qrContent
            if (showQr && qr != null) {
                QrShareCard(qr)
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

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun QrShareCard(content: String) {
    val context = LocalContext.current
    var qrBitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(content) {
        qrBitmap = com.miraglio.data.share.QrCodeRenderer.render(content, 512)
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xB3FFFFFF))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR della posizione",
                    modifier = Modifier.size(240.dp)
                )
                TextButton(onClick = { QrShareHelper.shareQr(context, it) }) {
                    Text("Condividi QR")
                }
            }
        }
    }
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
