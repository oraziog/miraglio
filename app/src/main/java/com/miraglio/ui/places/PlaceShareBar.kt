package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miraglio.data.places.PlaceSaveState
import com.miraglio.data.share.QrCodeRenderer
import com.miraglio.ui.theme.PanelContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Riga di azioni "Luoghi": Salva (foto + misura + GPS), Condividi (foto +
 * messaggio con pin Maps) e QR (il pin come codice, per lo scambio in
 * presenza). Riutilizzata da Capture, Verifica GPS e dettaglio Luoghi.
 *
 * Il QR usa [qrContent] (link Maps) e, senza fix GPS, cade sul testo di
 * [shareMessage]: il bottone resta premibile e lo scanner legge la misura.
 */
@Composable
fun PlaceShareBar(
    saveState: PlaceSaveState,
    hasPin: Boolean,
    qrContent: String?,
    shareMessage: String?,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Con il pin il QR apre la mappa; senza pin si codifica il messaggio
    // di condivisione, che uno scanner legge lo stesso.
    val effectiveQr = qrContent ?: shareMessage

    LaunchedEffect(showQr, effectiveQr) {
        if (showQr && effectiveQr != null) {
            qrBitmap = withContext(Dispatchers.Default) {
                QrCodeRenderer.render(effectiveQr, 512)
            }
        } else {
            qrBitmap = null
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Padding compatto e testo su una sola riga: "Salvato ✓" e
            // "Condividi" devono stare su un rigo, non spezzarsi a capo.
            val compactPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            if (onSaveClick != null) {
                Button(
                    onClick = onSaveClick,
                    enabled = saveState == PlaceSaveState.IDLE ||
                        saveState == PlaceSaveState.SAVED ||
                        saveState == PlaceSaveState.FAILED,
                    contentPadding = compactPadding,
                    modifier = Modifier.weight(1f)
                ) {
                    when (saveState) {
                        PlaceSaveState.SAVING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "  Salvo...",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        PlaceSaveState.SAVED -> Text(
                            "Salvato ✓",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                        else -> Text(
                            "Salva",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            // Condividi resta possibile anche senza pin: il messaggio contiene
            // l'avviso e la misura, solo il QR richiede il fix GPS.
            FilledTonalButton(
                onClick = onShareClick,
                enabled = shareMessage != null,
                contentPadding = compactPadding,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "Condividi",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false
                )
            }
            FilledTonalButton(
                onClick = { showQr = true },
                enabled = effectiveQr != null || hasPin,
                contentPadding = compactPadding,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "QR",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    if (showQr) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("Pin della posizione", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR della posizione",
                            modifier = Modifier.size(260.dp)
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                    Text(
                        text = if (qrContent != null) {
                            "Inquadralo per aprire la mappa (anche da iPhone)."
                        } else {
                            "Nessun fix GPS: il QR contiene il testo della misura."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        // Condivisione del QR come immagine: si passa dal
                        // FileProvider tramite il salvataggio temporaneo.
                        QrShareHelper.shareQr(context, bmp)
                    }
                }) {
                    Text("Condividi QR")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQr = false }) { Text("Chiudi") }
            }
        )
    }
}
