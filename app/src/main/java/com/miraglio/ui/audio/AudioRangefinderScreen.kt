package com.miraglio.ui.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.miraglio.R
import com.miraglio.data.audio.AcousticRangefinder
import com.miraglio.data.profile.BowProfile
import com.miraglio.data.weather.TemperatureReading
import com.miraglio.domain.audio.ShotConfidence
import com.miraglio.domain.audio.ShotConfidenceReason
import com.miraglio.domain.audio.ShotReport
import com.miraglio.domain.audio.WaveformBuffer
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.theme.GreenDark
import com.miraglio.ui.theme.GreenLight
import com.miraglio.ui.theme.LocalOutdoorMode
import com.miraglio.ui.theme.Orange80
import com.miraglio.ui.theme.OutdoorAmber
import com.miraglio.ui.theme.OutdoorGreen
import com.miraglio.ui.theme.OutdoorRed
import java.io.File

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

    // Cartella scelta dall'utente per il WAV: il selettore di sistema (SAF) dà
    // il permesso di scrittura solo su quella, senza chiedere accesso a tutto
    // lo storage. Il file si crea qui, non durante la misura.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.savePendingAudio(uri) }

    LaunchedEffect(Unit) { viewModel.refreshTemperature() }

    // Ascolto automatico: entrando nel telemetro con un modello calibrato il
    // microfono si apre da solo, senza passare dal pulsante. Il permesso deve
    // essere già concesso: chiederlo di sorpresa all'apertura no.
    LaunchedEffect(hasMicrophone, state.canMeasure) {
        if (hasMicrophone) viewModel.autoStart()
    }

    // Il WAV dell'ultimo tiro è pronto in cache: il chooser si apre dal contesto
    // della schermata (quello dell'applicazione non può avviare activity) e
    // subito dopo lo stato torna vuoto.
    LaunchedEffect(state.shareAudioPath) {
        val path = state.shareAudioPath ?: return@LaunchedEffect
        AudioDebugShare.share(context, File(path))
        viewModel.onAudioShared()
    }

    // Con l'ascolto aperto lo schermo resta acceso: se si spegne, l'arciere
    // deve sbloccarlo e rientrare proprio mentre sta per tirare.
    val activity = context.findActivity()
    DisposableEffect(state.listening) {
        if (state.listening) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // In background Android 9 e successivi consegnano silenzio alle app senza
    // foreground service: meglio chiudere il microfono che tenerlo aperto a
    // leggere niente. Al rientro l'ascolto riparte da solo (se l'utente non
    // aveva premuto Ferma).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopForBackground() }

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
                releaseMarker = state.releaseMarker,
                impactMarker = state.impactMarker,
                zoom = state.zoom,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onZoom = viewModel::setZoom,
                resultPulse = state.resultPulse
            )

            // Scadenza della sessione: non è un guasto del microfono, è il
            // tempo scaduto. Dirlo con le parole giuste evita di far cercare
            // altre app aperte che non c'entrano.
            if (state.timedOut) {
                val minutiTimeout = (AcousticRangefinder.DEFAULT_IDLE_TIMEOUT_S / 60).toInt()
                Text(
                    text = pluralStringResource(
                        R.plurals.audio_session_timeout,
                        minutiTimeout,
                        minutiTimeout
                    ),
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.pendingAudio) {
                PendingAudioCard(
                    savedName = state.audioSavedName,
                    failed = state.audioSaveFailed,
                    onSave = { folderLauncher.launch(null) },
                    onShare = viewModel::sharePendingAudio
                )
            }

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

/**
 * La scheda Misura. `internal` e non `private` perché lo screenshot test la
 * disegna da sola: una schermata intera non si può costruire sul JVM (apre
 * microfono e ViewModel Hilt), ma la scheda vera serve a vedere i colori e il
 * testo che l'utente legge davvero.
 */
@Composable
internal fun MeasurementCard(
    stageText: String,
    listening: Boolean,
    captureFailed: Boolean,
    report: ShotReport?,
    hasProfile: Boolean,
    canMeasure: Boolean,
    hasMicrophone: Boolean,
    waveform: List<Float>,
    releaseMarker: Float?,
    impactMarker: Float?,
    zoom: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onZoom: (Float) -> Unit,
    resultPulse: Int = 0
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

        // Senza due distanze di calibrazione il modello non esiste: ascoltare
        // darebbe solo un tempo, non una distanza. Il pulsante resta spento
        // finché il profilo non è "regolare" (almeno due misure valide).
        Button(
            onClick = if (listening) onStop else onStart,
            enabled = hasMicrophone && hasProfile && (canMeasure || listening)
        ) {
            Text(
                stringResource(
                    when {
                        listening -> R.string.audio_stop
                        report != null -> R.string.audio_resume
                        else -> R.string.audio_start
                    }
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
            // Lampeggio breve a ogni tiro: in una serie il numero può restare
            // identico (stessa distanza), quindi non basta confrontare i
            // valori — serve un contatore che avanza a ogni misura. Il colore
            // si stende dietro al blocco del risultato e si spegne da solo,
            // senza spostare niente nel layout.
            val flash = remember { Animatable(0f) }
            LaunchedEffect(resultPulse) {
                if (resultPulse <= 0) return@LaunchedEffect
                flash.snapTo(1f)
                flash.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = PULSE_DURATION_MS)
                )
            }
            val flashColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f * flash.value)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRoundRect(
                            color = flashColor,
                            cornerRadius = CornerRadius(8.dp.toPx())
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Distanza e velocità in una griglia di due celle sulla stessa
                // linea: le etichette occupano sempre due righe, così i due numeri
                // partono dalla stessa altezza qualunque sia la lingua, e l'unità
                // sta accanto al valore con un corpo più piccolo — il peso resta
                // al numero, che è quello che si legge al bersaglio.
                val cells = buildList {
                    add(
                        MeasureCell(
                            label = stringResource(R.string.audio_result_distance),
                            value = format.number(format.distanceValue(report.distanceM), 1),
                            unit = format.distanceUnit,
                            color = confidenceColor(report.quality.confidence)
                        )
                    )
                    report.speedMs?.let { speed ->
                        add(
                            MeasureCell(
                                label = stringResource(R.string.audio_result_speed),
                                value = format.number(speed * MS_TO_KMH, 1),
                                unit = stringResource(R.string.audio_speed_unit_label)
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    cells.forEach { cell ->
                        Text(
                            text = cell.label,
                            style = MaterialTheme.typography.bodySmall,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    cells.forEach { cell ->
                        Row(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cell.value,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = cell.color ?: MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.alignByBaseline()
                            )
                            Text(
                                text = cell.unit,
                                style = MaterialTheme.typography.labelLarge,
                                color = cell.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .alignByBaseline()
                            )
                        }
                    }
                }
                ResultRow(
                    label = stringResource(R.string.audio_result_time),
                    value = stringResource(
                        R.string.audio_seconds,
                        format.number(report.totalTimeS, 3)
                    )
                )
                Text(
                    text = stringResource(confidenceReasonLabel(report.quality.reason)),
                    color = confidenceColor(report.quality.confidence),
                    style = MaterialTheme.typography.bodySmall
                )
                ResultRow(
                    label = stringResource(R.string.audio_clicker),
                    value = stringResource(
                        if (report.clickerDetected) R.string.audio_clicker_yes
                        else R.string.audio_clicker_no
                    )
                )
            }
        }

        // L'avviso vale anche quando a schermo c'è ancora un risultato: dopo
        // aver cancellato le misure quel numero non è più calcolabile.
        if (hasProfile && !canMeasure) {
            Text(
                text = stringResource(R.string.audio_need_calibration),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text(
            text = stringResource(R.string.audio_waveform_title),
            style = MaterialTheme.typography.labelLarge
        )
        WaveformGraph(
            levels = waveform,
            releaseAt = releaseMarker,
            impactAt = impactMarker,
            modifier = Modifier.fillMaxWidth().height(144.dp)
        )

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

/**
 * Una cella della griglia dei risultati: etichetta, numero e unità.
 *
 * Il numero sta in `headlineMedium` (28 sp) e l'unità in `labelLarge` (14 sp):
 * "16,7" + "m" invece di "16,7 m" tutto grande, così il valore si stacca
 * dall'unità senza aggiungere un'altra riga.
 */
private data class MeasureCell(
    val label: String,
    val value: String,
    val unit: String,
    val color: Color? = null
)

/** Da m/s a km/h: quello che si legge al bersaglio è la velocità in km/h. */
private const val MS_TO_KMH = 3.6f

@Composable
private fun ResultRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // L'etichetta prende il peso e quindi va a capo invece di spingere il
        // numero fuori dalla card: in inglese "Distance (line of sight)" è il
        // caso più largo della stessa riga.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Verde, giallo e rosso leggibili sui tre fondi dell'app.
 *
 * I colori non possono essere fissi: il verde scuro che regge sulla card chiara
 * sparisce su un fondo scuro. Si sceglie per luminanza della superficie del
 * tema (chiaro/scuro, qualunque sia l'impostazione di sistema) e per la
 * modalità outdoor, che ha già i suoi toni ad alto contrasto sul nero.
 */
@Composable
private fun confidenceColor(confidence: ShotConfidence): Color {
    val scuro = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (confidence) {
        ShotConfidence.HIGH -> when {
            LocalOutdoorMode.current -> OutdoorGreen
            scuro -> GreenLight
            else -> GreenDark
        }

        ShotConfidence.MEDIUM -> when {
            LocalOutdoorMode.current -> OutdoorAmber
            scuro -> Orange80
            else -> AmberOnLight
        }

        ShotConfidence.LOW -> when {
            LocalOutdoorMode.current -> OutdoorRed
            scuro -> RedOnDark
            else -> RedOnLight
        }
    }
}

/** Arancione scuro abbastanza da reggere il testo piccolo sulla card chiara. */
private val AmberOnLight = Color(0xFF8A4B00)

/** Rosso Material su fondo chiaro: ~5,4:1 di contrasto. */
private val RedOnLight = Color(0xFFB3261E)

/** Rosso chiaro per il tema scuro: il rosso scuro sul nero non si legge. */
private val RedOnDark = Color(0xFFFF8A80)

@Composable
private fun confidenceReasonLabel(reason: ShotConfidenceReason): Int = when (reason) {
    ShotConfidenceReason.OK -> R.string.audio_quality_ok
    ShotConfidenceReason.NO_BROADBAND_PROOF -> R.string.audio_quality_no_broadband
    ShotConfidenceReason.IMPACT_JUST_OUTSIDE_FIELD -> R.string.audio_quality_near_band
    ShotConfidenceReason.IMPACT_OUT_OF_BAND -> R.string.audio_quality_out_of_band
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
private fun WaveformGraph(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    releaseAt: Float? = null,
    impactAt: Float? = null
) {
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
        val lastIndex = maxOf(levels.size - 1, 1).toFloat()
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

        // Le due linee bianche che segnano il tiro: dove il motore fa partire il
        // cronometro (scocco) e dove lo ferma (impatto). Il filo scuro sotto
        // serve a farle restare visibili anche sulla card chiara, dove il bianco
        // da solo sparirebbe.
        fun marker(at: Float?) {
            val position = at?.coerceIn(0f, lastIndex) ?: return
            val x = position * step
            drawLine(
                color = Color.Black.copy(alpha = 0.45f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 4f
            )
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
        }
        marker(releaseAt)
        marker(impactAt)
    }
}

/** Durata del lampeggio che conferma un tiro nuovo. */
private const val PULSE_DURATION_MS = 420

/** L'Activity che ospita la schermata, per il flag di schermo acceso. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
