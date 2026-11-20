package com.miraglio.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.profile.BowProfile
import com.miraglio.data.weather.TemperatureReading
import com.miraglio.domain.audio.ShotReport
import com.miraglio.domain.audio.WaveformBuffer
import com.miraglio.ui.i18n.LocalDisplayFormat

/**
 * La schermata del telemetro acustico: profilo arco, temperatura, misura.
 *
 * Non simula niente: il pulsante apre il microfono davvero e quello che si
 * legge è lo stato della macchina a stati di `ShotTimingMachine`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRangefinderScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    viewModel: AudioRangefinderViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val format = LocalDisplayFormat.current

    var hasMicrophone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicrophone = granted
        if (granted) viewModel.refreshTemperature()
    }

    LaunchedEffect(Unit) { viewModel.refreshTemperature() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.audio_title),
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
                    IconButton(onClick = onNavigateToCalibration) {
                        Icon(
                            Icons.Default.Timeline,
                            contentDescription = stringResource(R.string.audio_calibration_title)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasMicrophone) {
                MicrophonePermissionCard(
                    onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }

            ProfileSelectorCard(
                profiles = state.profiles,
                active = state.profile,
                enabled = !state.listening,
                onSelect = viewModel::selectProfile,
                onCreate = viewModel::createProfile
            )

            state.profile?.let { profile ->
                ClickerCard(
                    profile = profile,
                    enabled = !state.listening,
                    onClicker = viewModel::setClicker,
                    onOffset = viewModel::setReleaseOffset
                )
            }

            TemperatureCard(
                reading = state.temperature,
                format = format.number(state.temperature.celsius, 0),
                onManual = viewModel::applyManualTemperature,
                onUseWeather = viewModel::clearManualTemperature
            )

            MeasurementCard(
                stageText = stringResource(stageLabel(state.stage)),
                listening = state.listening,
                captureFailed = state.captureFailed,
                report = state.report,
                hasProfile = state.profile != null,
                canMeasure = state.canMeasure,
                hasMicrophone = hasMicrophone,
                waveform = state.waveform,
                zoom = state.zoom,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onZoom = viewModel::setZoom
            )

            TextButton(onClick = onNavigateToCalibration) {
                Text(stringResource(R.string.audio_calibration_title))
            }
        }
    }
}

@Composable
private fun MicrophonePermissionCard(onRequest: () -> Unit) {
    AudioSection(title = stringResource(R.string.audio_permission_title)) {
        Text(stringResource(R.string.audio_permission_body))
        Button(onClick = onRequest) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Text(
                text = stringResource(R.string.audio_permission_grant),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            text = stringResource(R.string.audio_permission_denied),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ClickerCard(
    profile: BowProfile,
    enabled: Boolean,
    onClicker: (Boolean) -> Unit,
    onOffset: (String) -> Unit
) {
    // Il testo dell'offset si tiene locale: riscriverlo dallo store a ogni
    // pressione di tasto riportare "25" a "25.0" mentre si digita.
    var offsetText by remember(profile.id) { mutableStateOf(plainNumber(profile.releaseOffsetMs)) }

    AudioSection(title = stringResource(R.string.audio_clicker)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = profile.useClicker,
                onCheckedChange = onClicker,
                enabled = enabled
            )
            Text(
                text = stringResource(
                    if (profile.useClicker) R.string.audio_clicker_on
                    else R.string.audio_clicker_off
                ),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (profile.useClicker) {
            OutlinedTextField(
                value = offsetText,
                onValueChange = { text ->
                    offsetText = text
                    onOffset(text)
                },
                label = { Text(stringResource(R.string.audio_offset_label)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@Composable
private fun TemperatureCard(
    reading: TemperatureReading,
    format: String,
    onManual: (String) -> Unit,
    onUseWeather: () -> Unit
) {
    var manualText by remember { mutableStateOf("") }

    AudioSection(title = stringResource(R.string.audio_temperature_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.audio_celsius,
                    format
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(temperatureSourceLabel(reading.source)),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = stringResource(R.string.audio_temperature_explain),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = manualText,
            onValueChange = { text ->
                manualText = text
                onManual(text)
            },
            label = { Text(stringResource(R.string.audio_temperature_manual_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        TextButton(onClick = onUseWeather) {
            Text(stringResource(R.string.audio_temperature_use_weather))
        }
    }
}

@Composable
private fun MeasurementCard(
    stageText: String,
    listening: Boolean,
    captureFailed: Boolean,
    report: ShotReport?,
    hasProfile: Boolean,
    canMeasure: Boolean,
    hasMicrophone: Boolean,
    waveform: List<Float>,
    zoom: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onZoom: (Float) -> Unit
) {
    val format = LocalDisplayFormat.current

    AudioSection(title = stringResource(R.string.audio_stage_title)) {
        Text(
            text = stageText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (captureFailed) {
            Text(
                text = stringResource(R.string.audio_capture_failed),
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = if (listening) onStop else onStart,
            enabled = hasMicrophone && hasProfile
        ) {
            Text(
                stringResource(
                    if (listening) R.string.audio_stop else R.string.audio_start
                )
            )
        }

        if (!hasProfile) {
            Text(
                text = stringResource(R.string.audio_no_profile),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (report != null) {
            ResultRow(
                label = stringResource(R.string.audio_result_time),
                value = stringResource(R.string.audio_seconds, format.number(report.totalTimeS, 3))
            )
            ResultRow(
                label = stringResource(R.string.audio_result_distance),
                value = format.distance(report.distanceM)
            )
            ResultRow(
                label = stringResource(R.string.audio_result_horizontal),
                value = format.distance(report.horizontalDistanceM)
            )
            report.speedMs?.let { speed ->
                ResultRow(
                    label = stringResource(R.string.audio_result_speed),
                    value = stringResource(R.string.audio_speed_unit, format.number(speed, 1))
                )
            }
            ResultRow(
                label = stringResource(R.string.audio_result_pitch),
                value = stringResource(R.string.audio_degrees, format.number(report.pitchDeg, 0))
            )
        } else if (hasProfile && !canMeasure) {
            Text(
                text = stringResource(R.string.audio_need_calibration),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = stringResource(R.string.audio_waveform_title),
            style = MaterialTheme.typography.labelLarge
        )
        WaveformGraph(levels = waveform, modifier = Modifier.fillMaxWidth().height(72.dp))

        Text(
            text = stringResource(
                R.string.audio_zoom,
                format.number(zoom.coerceIn(WaveformBuffer.MIN_ZOOM, WaveformBuffer.MAX_ZOOM), 0)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = zoom,
            onValueChange = onZoom,
            valueRange = WaveformBuffer.MIN_ZOOM..WaveformBuffer.MAX_ZOOM,
            steps = 6
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Forma d'onda dei livelli: una barra verticale per blocco letto.
 *
 * È il modo più diretto di vedere *perché* una misura non parte: se mentre si
 * tira il grafico resta piatto, il microfono non sta sentendo niente, e nessuna
 * soglia lo farà funzionare. Lo zoom restringe la finestra temporale, non
 * l'ampiezza.
 */
@Composable
private fun WaveformGraph(levels: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Canvas(modifier = modifier) {
        val middle = size.height / 2f
        drawLine(
            color = gridColor,
            start = Offset(0f, middle),
            end = Offset(size.width, middle),
            strokeWidth = 1f
        )
        if (levels.isEmpty()) return@Canvas

        val step = size.width / maxOf(levels.size - 1, 1)
        val halfHeight = size.height / 2f
        levels.forEachIndexed { index, level ->
            val amplitude = level.coerceIn(0f, 1f) * halfHeight
            val x = index * step
            drawLine(
                color = lineColor,
                start = Offset(x, middle - amplitude),
                end = Offset(x, middle + amplitude),
                strokeWidth = 1.5f
            )
        }
    }
}
