package com.miraglio.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miraglio.data.update.UpdateManager
import java.util.Locale

/**
 * Dialog dell'aggiornamento: un solo punto di ingresso, guidato dallo stato
 * del [UpdateManager]. Compare sopra qualsiasi schermata (e' disegnato dalla
 * radice dell'app), quindi l'aggiornamento si puo' avviare anche mentre si
 * sta misurando.
 *
 * Le note di rilascio sono markdown su GitHub ma vengono mostrate come testo
 * semplice: renderizzare il markdown richiederebbe un'altra libreria per un
 * contenuto che e' comunque nostro e di poche righe.
 */
@Composable
fun UpdateDialog(
    state: UpdateManager.State,
    installedVersion: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onPostpone: () -> Unit
) {
    val release = state.releaseOrNull() ?: return

    val downloading = state is UpdateManager.State.Downloading
    AlertDialog(
        // Durante il download la chiusura annulla il download: chiudere il
        // dialog lasciando un file a meta' non avrebbe senso.
        onDismissRequest = { if (downloading) onCancelDownload() else onPostpone() },
        title = {
            Text(
                text = if (downloading) {
                    "Scarico la versione ${release.displayVersion}"
                } else {
                    "Aggiornamento disponibile"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Versione ${release.displayVersion} (installata: $installedVersion)" +
                        sizeSuffix(release.apkSizeBytes),
                    style = MaterialTheme.typography.bodyMedium
                )
                when (state) {
                    is UpdateManager.State.Downloading -> DownloadProgress(state.progress)

                    is UpdateManager.State.ReadyToInstall -> Text(
                        text = "Scaricato. Premi Installa e conferma nella " +
                            "schermata di sistema che si apre.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is UpdateManager.State.PermissionRequired -> Text(
                        text = "Android deve autorizzare Miraglio a installare " +
                            "app: attiva la voce nella schermata appena aperta, " +
                            "poi torna qui e premi Installa.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is UpdateManager.State.Failed -> Text(
                        text = "Non e' riuscito: ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    else -> if (release.notes.isNotEmpty()) {
                        Text(
                            text = release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            // Le note possono essere lunghe: scorrono dentro
                            // il dialog invece di farlo crescere a dismisura.
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateManager.State.Available -> TextButton(onClick = onDownload) {
                    Text("Scarica e installa")
                }

                is UpdateManager.State.ReadyToInstall,
                is UpdateManager.State.PermissionRequired -> TextButton(onClick = onInstall) {
                    Text("Installa")
                }

                is UpdateManager.State.Failed -> TextButton(onClick = onDownload) {
                    Text("Riprova")
                }

                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = { if (downloading) onCancelDownload() else onPostpone() }) {
                Text(if (downloading) "Annulla" else "Piu' tardi")
            }
        }
    )
}

/** Avanzamento del download: percentuale se nota, barra indefinita altrimenti. */
@Composable
private fun DownloadProgress(progress: Float?) {
    if (progress == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Release a cui si riferisce lo stato, quando ce n'e' una. */
private fun UpdateManager.State.releaseOrNull() = when (this) {
    is UpdateManager.State.Available -> release
    is UpdateManager.State.Downloading -> release
    is UpdateManager.State.ReadyToInstall -> release
    is UpdateManager.State.PermissionRequired -> release
    is UpdateManager.State.Failed -> release
    else -> null
}

private fun sizeSuffix(bytes: Long): String =
    if (bytes <= 0L) "" else " - %.1f MB".format(Locale.getDefault(), bytes / 1_048_576.0)
