package com.miraglio.ui.audio

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miraglio.R
import com.miraglio.data.profile.BowProfile
import com.miraglio.data.weather.TemperatureSource
import com.miraglio.domain.audio.ShotStage

/** Card con titolo, usata da entrambe le schermate. */
@Composable
fun AudioSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

/**
 * Scelta del profilo arco e creazione di uno nuovo.
 *
 * Il profilo attivo è quello su cui si misura e si calibra: creandone uno
 * diventa subito attivo, così la schermata non resta senza riferimento.
 */
@Composable
fun ProfileSelectorCard(
    profiles: List<BowProfile>,
    active: BowProfile?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }

    AudioSection(title = stringResource(R.string.audio_profile_title)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = enabled && profiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(active?.nickname ?: stringResource(R.string.audio_profile_none))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.nickname) },
                            onClick = {
                                menuOpen = false
                                onSelect(profile.id)
                            }
                        )
                    }
                }
            }
            OutlinedButton(onClick = { showNewDialog = true }, enabled = enabled) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    }

    if (showNewDialog) {
        NewProfileDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { name ->
                showNewDialog = false
                onCreate(name)
            }
        )
    }
}

@Composable
private fun NewProfileDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_profile_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.audio_profile_name)) },
                    placeholder = { Text(stringResource(R.string.audio_profile_name_hint)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.audio_profile_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Il testo dello stato della macchina a stati, come lo vede l'arciere. */
@StringRes
fun stageLabel(stage: ShotStage): Int = when (stage) {
    ShotStage.IDLE -> R.string.audio_stage_idle
    ShotStage.SETTLING -> R.string.audio_stage_settling
    ShotStage.READY -> R.string.audio_stage_ready
    ShotStage.ARMED -> R.string.audio_stage_armed
    ShotStage.RELEASE_DETECTED -> R.string.audio_stage_release
    ShotStage.IMPACT_WAIT -> R.string.audio_stage_impact
    ShotStage.DONE -> R.string.audio_stage_done
    ShotStage.DONE_NO_IMPACT -> R.string.audio_stage_done_no_impact
}

@StringRes
fun temperatureSourceLabel(source: TemperatureSource): Int = when (source) {
    TemperatureSource.MANUAL -> R.string.audio_temperature_manual
    TemperatureSource.FORECAST -> R.string.audio_temperature_forecast
    TemperatureSource.FALLBACK -> R.string.audio_temperature_fallback
}
