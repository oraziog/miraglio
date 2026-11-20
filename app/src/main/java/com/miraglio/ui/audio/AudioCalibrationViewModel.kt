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
import com.miraglio.data.profile.CalibrationShot
import com.miraglio.data.sensor.Inclinometer
import com.miraglio.data.sensor.MotionMonitor
import com.miraglio.data.weather.WeatherRepository
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingState
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.sensor.ShotOrientationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * La calibrazione guidata: un tiro per volta, a una distanza nota.
 *
 * Le regole del fit sono nel dominio ([BallisticCalculator]); qui c'è solo la
 * procedura: prendere la distanza vera, misurare, decidere se il punto è
 * sospetto e — se lo è — **chiedere** prima di salvarlo o scartarlo. Il punto
 * sospetto resta fuori dal modello solo dopo quella conferma, come chiede la
 * specifica.
 */
@HiltViewModel
class AudioCalibrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: BowProfileStore,
    private val weather: WeatherRepository,
    private val rangefinder: AcousticRangefinder,
    private val motion: MotionMonitor,
    private val location: LocationTracker,
    private val inclinometer: Inclinometer
) : ViewModel() {

    private val orientation = ShotOrientationManager(inclinometer.pitchAngleDegrees)

    /** Un tiro misurato che il modello attuale giudica sospetto. */
    data class PendingShot(
        val distanceM: Float,
        val totalTimeS: Float,
        val airTempC: Float,
        val pitchDeg: Float,
        val deviationM: Float
    )

    /** Cosa è appena successo, per il messaggio sotto il pulsante. */
    enum class Notice { SAVED, SAVED_WITHOUT_TIME, INVALID_DISTANCE, CAPTURE_FAILED }

    data class UiState(
        val profiles: List<BowProfile> = emptyList(),
        val profile: BowProfile? = null,
        val shots: List<CalibrationShot> = emptyList(),
        val distanceField: String = "",
        val stage: ShotStage = ShotStage.IDLE,
        val listening: Boolean = false,
        val pending: PendingShot? = null,
        val notice: Notice? = null,
        val model: ArrowSpeedModel? = null,
        val canMeasure: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: Job? = null

    init {
        viewModelScope.launch {
            combine(profileStore.profiles, profileStore.activeProfile) { list, active ->
                list to active
            }.collect { (list, active) ->
                val model = active?.let { BallisticCalculator.fit(it.fitPoints) }
                _state.value = _state.value.copy(
                    profiles = list,
                    profile = active,
                    shots = active?.shots.orEmpty(),
                    model = model,
                    canMeasure = model != null
                )
            }
        }
        viewModelScope.launch {
            location.currentLocation.collect { fix ->
                if (fix != null && weather.manualCelsius() == null) {
                    // Solo per tenere la temperatura pronta: il tiro la salva
                    // col suo valore, ma il campo deve mostrarla prima.
                    resolveTemperature(weather, location)
                }
            }
        }
    }

    fun setDistance(text: String) {
        _state.value = _state.value.copy(distanceField = text, notice = null)
    }

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

    /** Misura un tiro alla distanza scritta nel campo. */
    fun measure() {
        if (session?.isActive == true) return
        val profile = _state.value.profile ?: return
        val distance = parseDecimal(_state.value.distanceField)
        if (distance == null || distance <= 0f) {
            _state.value = _state.value.copy(notice = Notice.INVALID_DISTANCE)
            return
        }

        session = viewModelScope.launch {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = _state.value.copy(
                    stage = ShotStage.IDLE,
                    listening = false,
                    notice = Notice.CAPTURE_FAILED
                )
                return@launch
            }

            inclinometer.start()
            val lockedPitch = orientation.lock()
            runCatching { motion.start() }
            runCatching { location.startLocationUpdates() }

            _state.value = _state.value.copy(
                listening = true,
                stage = ShotStage.SETTLING,
                notice = null,
                pending = null
            )

            var cancelled = false
            try {
                val finalState = rangefinder.measure(
                    useClicker = profile.useClicker,
                    releaseOffsetMs = profile.releaseOffsetMs,
                    stability = motion.stable,
                    onState = { publishStage(it) }
                )
                handleResult(finalState, distance, lockedPitch, profile.id)
            } catch (stop: CancellationException) {
                cancelled = true
                _state.value = _state.value.copy(stage = ShotStage.IDLE)
                throw stop
            } finally {
                runCatching { motion.stop() }
                runCatching { location.stopLocationUpdates() }
                inclinometer.stop()
                val failed = !cancelled && _state.value.stage == ShotStage.IDLE
                _state.value = _state.value.copy(
                    listening = false,
                    notice = if (failed) Notice.CAPTURE_FAILED else _state.value.notice
                )
            }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
    }

    /**
     * Salva il tiro sospetto: è la conferma esplicita richiesta dalla spec.
     */
    fun keepPending() {
        val pending = _state.value.pending ?: return
        val profile = _state.value.profile ?: return
        saveShot(profile, pending.distanceM, pending.totalTimeS, pending.airTempC, pending.pitchDeg)
        _state.value = _state.value.copy(pending = null, notice = Notice.SAVED)
    }

    fun discardPending() {
        _state.value = _state.value.copy(pending = null)
    }

    fun deleteShot(shotId: Long) {
        val profile = _state.value.profile ?: return
        profileStore.deleteShot(profile.id, shotId)
    }

    /** Toglie o rimette un punto dal modello (il punto resta salvato). */
    fun setDiscarded(shotId: Long, discarded: Boolean) {
        val profile = _state.value.profile ?: return
        profileStore.setShotDiscarded(profile.id, shotId, discarded)
    }

    private fun publishStage(state: ShotTimingState) {
        _state.value = _state.value.copy(stage = state.stage)
    }

    private suspend fun handleResult(
        state: ShotTimingState,
        distance: Float,
        pitchDeg: Float,
        profileId: String
    ) {
        val temperature = resolveTemperature(weather, location).celsius
        val profile = _state.value.profile
        val measurement = state.measurement

        if (measurement == null) {
            profileStore.addShot(profileId, distance, null, temperature, pitchDeg)
            _state.value = _state.value.copy(
                stage = state.stage,
                notice = Notice.SAVED_WITHOUT_TIME
            )
            return
        }

        val model = profile?.let { BallisticCalculator.fit(it.fitPoints) }
        val deviation = model?.let {
            BallisticCalculator.deviationM(it, distance, measurement.totalTimeS, temperature)
        }

        if (deviation != null && abs(deviation) > BallisticCalculator.DEFAULT_OUTLIER_THRESHOLD_M) {
            _state.value = _state.value.copy(
                stage = state.stage,
                pending = PendingShot(
                    distanceM = distance,
                    totalTimeS = measurement.totalTimeS,
                    airTempC = temperature,
                    pitchDeg = pitchDeg,
                    deviationM = deviation
                )
            )
            return
        }

        saveShot(profile, distance, measurement.totalTimeS, temperature, pitchDeg)
        _state.value = _state.value.copy(stage = state.stage, notice = Notice.SAVED)
    }

    private fun saveShot(
        profile: BowProfile?,
        distanceM: Float,
        totalTimeS: Float,
        airTempC: Float = resolveCachedTemperature(),
        pitchDeg: Float = orientation.aimPitchDeg
    ) {
        val target = profile ?: _state.value.profile ?: return
        profileStore.addShot(target.id, distanceM, totalTimeS, airTempC, pitchDeg)
    }

    /** Temperatura già risolta (manuale o ripiego): il salvataggio non aspetta la rete. */
    private fun resolveCachedTemperature(): Float =
        weather.manualCelsius() ?: com.miraglio.data.weather.WeatherCachePolicy.FALLBACK_CELSIUS
}
