package com.miraglio.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.profile.CalibrationShot
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.ui.i18n.LocalDisplayFormat

/**
 * La calibrazione guidata: la procedura che rende utile il telemetro.
 *
 * Senza almeno due distanze note il modello non esiste e la misura non
 * restituisce una distanza (vedi `BallisticCalculator.fit`). Le tre card sono i
 * tre passi: quale arco, a che distanza, e cosa è già stato salvato.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCalibrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: AudioCalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val format = LocalDisplayFormat.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.audio_calibration_title),
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
            ProfileSelectorCard(
                profiles = state.profiles,
                active = state.profile,
                enabled = !state.listening,
                onSelect = viewModel::selectProfile,
                onCreate = viewModel::createProfile
            )

            AudioSection(title = stringResource(R.string.audio_calibration_step_distance)) {
                OutlinedTextField(
                    value = state.distanceField,
                    onValueChange = viewModel::setDistance,
                    label = { Text(stringResource(R.string.audio_calibration_distance_label)) },
                    singleLine = true,
                    enabled = !state.listening,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = if (state.listening) viewModel::stop else viewModel::measure,
                    enabled = state.profile != null
                ) {
                    Text(
                        stringResource(
                            if (state.listening) R.string.audio_stop
                            else R.string.audio_calibration_measure
                        )
                    )
                }
                Text(
                    text = stringResource(stageLabel(state.stage)),
                    style = MaterialTheme.typography.bodyMedium
                )
                state.notice?.let { notice ->
                    Text(
                        text = noticeText(notice, state.distanceField),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            AudioSection(title = stringResource(R.string.audio_calibration_step_points)) {
                if (state.shots.isEmpty()) {
                    Text(
                        text = stringResource(R.string.audio_calibration_no_shots),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                state.shots
                    .sortedByDescending { it.timestampMs }
                    .forEach { shot ->
                        ShotRow(
                            shot = shot,
                            format = format,
                            onToggleDiscard = {
                                viewModel.setDiscarded(shot.id, !shot.discarded)
                            },
                            onDelete = { viewModel.deleteShot(shot.id) }
                        )
                    }

                val model = state.model
                if (model != null) {
                    Text(
                        text = stringResource(
                            R.string.audio_calibration_model,
                            format.number(model.muzzleSpeedMs, 1),
                            format.number(model.decayPerM, 4)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.audio_calibration_ready),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.audio_calibration_need_more,
                            state.profile?.calibratedDistances ?: 0
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    state.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::discardPending,
            title = { Text(stringResource(R.string.audio_calibration_outlier_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.audio_calibration_outlier_message,
                        format.signedDistance(pending.deviationM)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::keepPending) {
                    Text(stringResource(R.string.audio_calibration_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::discardPending) {
                    Text(stringResource(R.string.audio_calibration_discard))
                }
            }
        )
    }
}

@Composable
private fun ShotRow(
    shot: CalibrationShot,
    format: DisplayFormat,
    onToggleDiscard: () -> Unit,
    onDelete: () -> Unit
) {
    val distanceText = shot.distanceM
        ?.let { format.distance(it) }
        ?: stringResource(R.string.audio_calibration_no_distance)
    val label = if (shot.hasValidTime) {
        stringResource(
            R.string.audio_calibration_shot_line,
            distanceText,
            format.number(shot.totalTimeS ?: 0f, 3)
        )
    } else {
        stringResource(R.string.audio_calibration_shot_no_time, distanceText)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (shot.discarded) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleDiscard) {
                Icon(
                    imageVector = if (shot.discarded) {
                        Icons.Default.Visibility
                    } else {
                        Icons.Default.VisibilityOff
                    },
                    contentDescription = stringResource(
                        if (shot.discarded) R.string.audio_restore_point
                        else R.string.audio_discard_point
                    )
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete)
                )
            }
        }
    }
}

@Composable
private fun noticeText(
    notice: AudioCalibrationViewModel.Notice,
    distanceText: String
): String = when (notice) {
    AudioCalibrationViewModel.Notice.SAVED ->
        stringResource(R.string.audio_calibration_saved, distanceText)

    AudioCalibrationViewModel.Notice.SAVED_WITHOUT_TIME ->
        stringResource(R.string.audio_calibration_saved_without_time)

    AudioCalibrationViewModel.Notice.INVALID_DISTANCE ->
        stringResource(R.string.audio_calibration_invalid_distance)

    AudioCalibrationViewModel.Notice.CAPTURE_FAILED ->
        stringResource(R.string.audio_calibration_capture_failed)
}
