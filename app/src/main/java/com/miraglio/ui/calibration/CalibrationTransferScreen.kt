package com.miraglio.ui.calibration

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.domain.calibration.MappedLens
import com.miraglio.ui.places.QrShareHelper
import com.miraglio.ui.places.ShareIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Altezza dell'anteprima mentre si inquadra il QR: un riquadro, non tutto schermo. */
private val ScanPreviewHeight = 300.dp

/**
 * Condivisione della calibrazione fra due telefoni: si manda un **testo** (un
 * messaggio, un QR) e si importa quello che arriva.
 *
 * La marca e il modello di chi ha esportato stanno scritti **sopra** il pulsante
 * di conferma e non dopo l'importazione: la focale e' una proprieta' fisica di
 * quel telefono, quindi e' l'informazione che dice se quella calibrazione ha
 * senso qui. Chi importa deve poterci rinunciare vedendola, non scoprirla dopo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationTransferScreen(
    onNavigateBack: () -> Unit,
    viewModel: CalibrationTransferViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showQr by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scanRequested by remember { mutableStateOf(false) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        // Sull'unico motivo per cui la camera serve qui: leggere il QR.
        if (granted) scanRequested = true
    }

    // Il QR si disegna a parte, mentre la finestra si apre: encodare 512 punti
    // sul thread dell'interfaccia bloccherebbe il disegno della finestra stessa.
    LaunchedEffect(showQr, state.exportText) {
        qrBitmap = if (showQr && state.exportText.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                runCatching { QrCodeRenderer.render(state.exportText, 720) }.getOrNull()
            }
        } else {
            null
        }
    }

    // Un QR letto chiude la scansione da solo (la ViewModel la ferma): qui si
    // toglie di mezzo l'anteprima della camera.
    LaunchedEffect(state.scanning) {
        if (!state.scanning) scanRequested = false
    }

    // Uscendo dalla schermata l'analizzatore si spegne: la camera resta legata
    // al lifecycle, ma i fotogrammi non si decodificano piu' per nessuno.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.calibration_transfer_title),
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
            ExportCard(
                state = state,
                onShareText = { ShareIntents.shareText(context, state.exportText) },
                onShowQr = { showQr = true }
            )

            ImportCard(
                state = state,
                scanning = scanRequested,
                onInputChange = viewModel::onImportInputChange,
                onRead = viewModel::readImport,
                onScan = {
                    if (hasPermission) scanRequested = true
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onStopScan = {
                    scanRequested = false
                    viewModel.stopScan()
                },
                onPreviewReady = { previewView ->
                    viewModel.startScan(lifecycleOwner, previewView)
                }
            )

            state.message?.let { messaggio ->
                Text(
                    text = messaggio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showQr) {
        QrDialog(content = qrBitmap, onDismiss = { showQr = false })
    }

    state.pending?.let { pending ->
        ConfirmImportDialog(
            device = pending.device,
            fileLensCount = pending.lensCount,
            pointCount = pending.totalPoints,
            mapping = state.mapping,
            localLensCount = state.localLensCount,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::cancelImport
        )
    }
}

@Composable
private fun ExportCard(
    state: TransferUiState,
    onShareText: () -> Unit,
    onShowQr: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.calibration_transfer_export_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.calibration_transfer_device, state.device),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            if (state.hasExport) {
                Text(
                    text = stringResource(
                        R.string.calibration_transfer_summary,
                        state.exportLensCount.toString(),
                        state.exportPointCount.toString()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.calibration_transfer_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onShareText,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.calibration_transfer_share_text),
                            maxLines = 1
                        )
                    }
                    FilledTonalButton(
                        onClick = onShowQr,
                        enabled = !state.tooLongForQr,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.calibration_transfer_show_qr), maxLines = 1)
                    }
                }
                if (state.tooLongForQr) {
                    Text(
                        text = stringResource(R.string.calibration_transfer_qr_too_long),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.calibration_transfer_no_points),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ImportCard(
    state: TransferUiState,
    scanning: Boolean,
    onInputChange: (String) -> Unit,
    onRead: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.calibration_transfer_import_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.calibration_transfer_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            if (scanning) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ScanPreviewHeight)
                        .background(Color.Black)
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                onPreviewReady(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = stringResource(R.string.calibration_transfer_scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = onStopScan) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.importInput,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.calibration_transfer_import_field)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRead,
                        enabled = state.importInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.calibration_transfer_import_action), maxLines = 1)
                    }
                    FilledTonalButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.calibration_transfer_scan_action),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cosa si sta per importare, **prima** di scriverlo: da quale telefono arriva,
 * quanti punti sono, e su quale lente finisce ognuno.
 *
 * Senza questa finestra l'importazione sarebbe un pulsante che cancella la
 * calibrazione della lente e ne mette un'altra, con la sola differenza che la
 * seconda viene da un telefono che l'utente non ha mai visto.
 */
@Composable
private fun ConfirmImportDialog(
    device: String,
    fileLensCount: Int,
    pointCount: Int,
    mapping: List<MappedLens>,
    localLensCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.calibration_transfer_confirm_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.calibration_transfer_device, device),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.calibration_transfer_summary,
                        fileLensCount.toString(),
                        pointCount.toString()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Lente per lente: dove va a finire quello che si importa.
                mapping.forEach { riga ->
                    val da = stringResource(
                        R.string.lens_label,
                        riga.fromPosition.toString(),
                        fileLensCount.toString()
                    )
                    Text(
                        text = if (riga.localIndex != null) {
                            stringResource(
                                R.string.calibration_transfer_map_row,
                                da,
                                stringResource(
                                    R.string.lens_label,
                                    (riga.localIndex + 1).toString(),
                                    localLensCount.toString()
                                )
                            )
                        } else {
                            stringResource(R.string.calibration_transfer_map_skipped, da)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (mapping.any { it.skipped }) {
                    Text(
                        text = stringResource(R.string.calibration_transfer_skipped_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (fileLensCount != localLensCount) {
                    Text(
                        text = stringResource(
                            R.string.calibration_transfer_count_mismatch,
                            fileLensCount.toString(),
                            localLensCount.toString()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.calibration_transfer_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.calibration_transfer_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Il QR da inquadrare con l'altro telefono, con la condivisione come immagine. */
@Composable
private fun QrDialog(content: Bitmap?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.calibration_transfer_show_qr), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (content != null) {
                    androidx.compose.foundation.Image(
                        bitmap = content.asImageBitmap(),
                        contentDescription =
                            stringResource(R.string.calibration_transfer_qr_hint),
                        modifier = Modifier.size(260.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    // Se il disegno non e' riuscito (testo oltre quello che un QR
                    // puo' portare) lo si dice, invece di lasciare la finestra
                    // con dentro solo la spiegazione di cosa sarebbe.
                    text = stringResource(
                        if (content != null) R.string.calibration_transfer_qr_hint
                        else R.string.calibration_transfer_qr_too_long
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { content?.let { QrShareHelper.shareQr(context, it) } },
                enabled = content != null
            ) {
                Text(stringResource(R.string.action_share_qr_title))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
