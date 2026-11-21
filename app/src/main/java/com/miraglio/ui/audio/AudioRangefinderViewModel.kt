package com.miraglio.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.audio.AcousticRangefinder
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.profile.BowProfile
import com.miraglio.data.profile.BowProfileStore
import com.miraglio.data.sensor.Inclinometer
import com.miraglio.data.sensor.MotionMonitor
import com.miraglio.data.weather.TemperatureReading
import com.miraglio.data.weather.TemperatureSource
import com.miraglio.data.weather.WeatherCachePolicy
import com.miraglio.data.weather.WeatherRepository
import com.miraglio.domain.audio.ImpactPlausibility
import com.miraglio.domain.audio.ShotReport
import com.miraglio.domain.audio.ShotReportBuilder
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingState
import com.miraglio.domain.audio.WaveformBuffer
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.sensor.ShotOrientationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * La schermata di misura: unisce i pezzi degli Step 1–4 e non fa conti suoi.
 *
 * Il risultato passa da [ShotReportBuilder]: la ViewModel decide *quando*
 * calcolare (alla fine della sessione, con l'angolo bloccato all'avvio), il
 * dominio decide *come*.
 */
@HiltViewModel
class AudioRangefinderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: BowProfileStore,
    private val weather: WeatherRepository,
    private val rangefinder: AcousticRangefinder,
    private val motion: MotionMonitor,
    private val location: LocationTracker,
    private val inclinometer: Inclinometer
) : ViewModel() {

    private val orientation = ShotOrientationManager(inclinometer.pitchAngleDegrees)
    private val waveform = WaveformBuffer()

    data class UiState(
        val profiles: List<BowProfile> = emptyList(),
        val profile: BowProfile? = null,
        val stage: ShotStage = ShotStage.IDLE,
        val listening: Boolean = false,
        val report: ShotReport? = null,
        val temperature: TemperatureReading = TemperatureReading(
            WeatherCachePolicy.FALLBACK_CELSIUS,
            TemperatureSource.FALLBACK
        ),
        val model: ArrowSpeedModel? = null,
        val canMeasure: Boolean = false,
        /** Il microfono non si è aperto: non è un tiro mancato, è un guasto. */
        val captureFailed: Boolean = false,
        val zoom: Float = WaveformBuffer.MIN_ZOOM,
        val waveform: List<Float> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: Job? = null
    private var blocksSinceWaveform = 0

    init {
        viewModelScope.launch {
            combine(profileStore.profiles, profileStore.activeProfile) { list, active ->
                list to active
            }.collect { (list, active) ->
                val model = active?.let { BallisticCalculator.fit(it.fitPoints) }
                _state.value = _state.value.copy(
                    profiles = list,
                    profile = active,
                    model = model,
                    canMeasure = model != null
                )
            }
        }
        viewModelScope.launch {
            // Il GPS serve solo alla temperatura: appena arriva un fix, se non
            // c'è un valore manuale si chiede la previsione di quel posto.
            location.currentLocation.collect { fix ->
                if (fix != null && weather.manualCelsius() == null) refreshTemperature()
            }
        }
        refreshTemperature()
    }

    // ------------------------------------------------------------ sessione

    /** Avvia l'ascolto: blocco dell'angolo, sensori accesi, microfono aperto. */
    fun start() {
        if (session?.isActive == true) return
        val profile = _state.value.profile

        session = viewModelScope.launch {
            // Il permesso si controlla qui e non solo nella schermata: chi
            // chiama questa funzione non deve poter aprire il microfono senza
            // averlo chiesto, e la schermata potrebbe essere ricreata mentre
            // l'utente revoca il permesso dalle impostazioni.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = _state.value.copy(
                    stage = ShotStage.IDLE,
                    listening = false,
                    captureFailed = true
                )
                return@launch
            }

            waveform.clear()
            blocksSinceWaveform = 0
            inclinometer.start()
            val lockedPitch = orientation.lock()
            runCatching { motion.start() }
            runCatching { location.startLocationUpdates() }

            _state.value = _state.value.copy(
                listening = true,
                stage = ShotStage.SETTLING,
                report = null,
                captureFailed = false,
                waveform = emptyList()
            )

            // La finestra in cui un impatto è fisicamente possibile esce dal
            // profilo (modello calibrato + distanze di calibrazione), non da
            // una costante: a 18 m la costante 1,0–2,0 s non conteneva il tiro.
            // `null` (profilo non calibrato) lascia il motore senza veto,
            // come prima: meglio nessun veto che una finestra inventata.
            val impactWindow = ImpactPlausibility.band(
                model = _state.value.model,
                distancesM = profile?.fitPoints?.map { it.distanceM }.orEmpty(),
                airTempC = _state.value.temperature.celsius
            )

            var cancelled = false
            try {
                val finalState = rangefinder.measure(
                    useClicker = profile?.useClicker == true,
                    releaseOffsetMs = profile?.releaseOffsetMs ?: 0f,
                    plausibleTotalTimeS = impactWindow,
                    stability = motion.stable,
                    onState = { publishStage(it) },
                    onLevel = ::onLevel
                )
                publishResult(finalState, lockedPitch)
            } catch (stop: CancellationException) {
                cancelled = true
                _state.value = _state.value.copy(stage = ShotStage.IDLE)
                throw stop
            } finally {
                runCatching { motion.stop() }
                runCatching { location.stopLocationUpdates() }
                inclinometer.stop()
                val failed = !cancelled && _state.value.stage == ShotStage.IDLE
                _state.value = _state.value.copy(listening = false, captureFailed = failed)
            }
        }
    }

    /** Ferma l'ascolto (annulla la sessione in corso). */
    fun stop() {
        session?.cancel()
        session = null
    }

    private fun publishStage(state: ShotTimingState) {
        _state.value = _state.value.copy(stage = state.stage)
    }

    private fun publishResult(state: ShotTimingState, lockedPitch: Float) {
        val report = state.measurement?.let { measurement ->
            ShotReportBuilder.build(
                totalTimeS = measurement.totalTimeS,
                model = _state.value.model,
                airTempC = _state.value.temperature.celsius,
                pitchDeg = lockedPitch
            )
        }
        _state.value = _state.value.copy(stage = state.stage, report = report)
    }

    private fun onLevel(level: Float) {
        waveform.push(level)
        blocksSinceWaveform++
        // Il grafico si ridisegna cinque volte al secondo: a ogni blocco (50
        // volte) sarebbe lavoro inutile per l'occhio e per la CPU.
        if (blocksSinceWaveform % 5 != 0) return
        val visible = WaveformBuffer.visibleCount(_state.value.zoom)
        _state.value = _state.value.copy(waveform = waveform.window(visible).toList())
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(WaveformBuffer.MIN_ZOOM, WaveformBuffer.MAX_ZOOM)
        val visible = WaveformBuffer.visibleCount(clamped)
        _state.value = _state.value.copy(
            zoom = clamped,
            waveform = waveform.window(visible).toList()
        )
    }

    // ------------------------------------------------------------- profilo

    fun createProfile(nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        val created = profileStore.addProfile(trimmed)
        profileStore.setActive(created.id)
    }

    fun selectProfile(id: String) {
        if (_state.value.listening) return
        profileStore.setActive(id)
    }

    fun setClicker(enabled: Boolean) {
        val profile = _state.value.profile ?: return
        if (_state.value.listening) return
        profileStore.setUseClicker(profile.id, enabled)
    }

    fun setReleaseOffset(text: String) {
        val profile = _state.value.profile ?: return
        val value = parseDecimal(text) ?: return
        if (_state.value.listening) return
        profileStore.setReleaseOffsetMs(profile.id, value)
    }

    // --------------------------------------------------------- temperatura

    fun applyManualTemperature(text: String) {
        val value = parseDecimal(text) ?: return
        weather.setManualCelsius(value)
        refreshTemperature()
    }

    fun clearManualTemperature() {
        weather.setManualCelsius(null)
        refreshTemperature()
    }

    /** Rilegge la temperatura: manuale, meteo del luogo, oppure ripiego. */
    fun refreshTemperature() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                temperature = resolveTemperature(weather, location)
            )
        }
    }
}
