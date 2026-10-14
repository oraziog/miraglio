package com.miraglio.ui.measurement

import android.location.Location
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.data.places.PlaceSaveState
import com.miraglio.data.places.PlacesRepository
import com.miraglio.data.session.ShotVerificationStore
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.location.Haversine
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.power.BackgroundPowerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

enum class MeasurementPhase { IDLE, TRACKING, DONE }

data class MeasurementUiState(
    val phase: MeasurementPhase = MeasurementPhase.IDLE,
    val currentDistanceM: Float = 0f,
    val walkedDistanceM: Float = 0f,
    val finalDistanceM: Float? = null,
    val fixAccuracyM: Float? = null,
    val waitingForFix: Boolean = false,
    val gpsEnabled: Boolean = true,
    val estimatedDistanceM: Float? = null,
    val message: String? = null,
    // --- Luoghi: condivisione e QR della misura terminata ---
    val shareMessage: String? = null,
    val qrContent: String? = null
)

@HiltViewModel
class MeasurementViewModel @Inject constructor(
    private val tracker: LocationTracker,
    private val store: ShotVerificationStore,
    private val placesRepository: PlacesRepository,
    private val powerController: BackgroundPowerController,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _phase = MutableStateFlow(MeasurementPhase.IDLE)
    private val _start = MutableStateFlow<Location?>(null)
    private val _current = MutableStateFlow<Location?>(null)
    private val _previous = MutableStateFlow<Location?>(null)
    private val _walked = MutableStateFlow(0f)
    private val _waitingForFix = MutableStateFlow(false)
    private val _gpsEnabled = MutableStateFlow(true)
    private val _message = MutableStateFlow<String?>(null)

    /** Stato di salvataggio del luogo (misura GPS). */
    private val _placeSaveState = MutableStateFlow(PlaceSaveState.IDLE)
    val placeSaveState: StateFlow<PlaceSaveState> = _placeSaveState.asStateFlow()

    val phase: StateFlow<MeasurementPhase> = _phase.asStateFlow()

    private data class LocalState(
        val phase: MeasurementPhase,
        val start: Location?,
        val current: Location?,
        val walked: Float,
        val waitingForFix: Boolean,
        val gpsEnabled: Boolean
    )

    private fun distanceBetween(a: Location, b: Location): Float =
        Haversine.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()

    private val positions = combine(_start, _current) { start, current ->
        start to current
    }

    private val localState: StateFlow<LocalState> = combine(
        positions,
        _phase,
        _walked,
        _waitingForFix,
        _gpsEnabled
    ) { positions, phase, walked, waiting, gpsEnabled ->
        LocalState(phase, positions.first, positions.second, walked, waiting, gpsEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalState(
        phase = MeasurementPhase.IDLE,
        start = null,
        current = null,
        walked = 0f,
        waitingForFix = false,
        gpsEnabled = true
    ))

    val uiState: StateFlow<MeasurementUiState> = combine(
        localState,
        store.estimatedDistanceM
    ) { local, estimated ->
        val liveDistance = if (local.phase != MeasurementPhase.IDLE &&
            local.start != null && local.current != null
        ) {
            distanceBetween(local.start!!, local.current!!)
        } else {
            0f
        }
        val finalDistance = if (local.phase == MeasurementPhase.DONE) liveDistance else null
        // Nel DONE si espongono messaggio e QR per la condivisione del luogo.
        val startFix = if (local.phase == MeasurementPhase.DONE) {
            local.start?.let { GpsFix.fromLocation(it) }
        } else {
            null
        }
        val endFix = if (local.phase == MeasurementPhase.DONE) {
            local.current?.let { GpsFix.fromLocation(it) }
        } else {
            null
        }
        MeasurementUiState(
            phase = local.phase,
            currentDistanceM = liveDistance,
            walkedDistanceM = local.walked,
            finalDistanceM = finalDistance,
            fixAccuracyM = local.current?.accuracy,
            waitingForFix = local.waitingForFix,
            gpsEnabled = local.gpsEnabled,
            estimatedDistanceM = estimated,
            message = _message.value,
            shareMessage = if (finalDistance != null && endFix != null) {
                ShareMessageComposer.gpsPlace(finalDistance, startFix, endFix)
            } else {
                null
            },
            qrContent = endFix?.let { ShareMessageComposer.mapsLink(it) }
                ?: startFix?.let { ShareMessageComposer.mapsLink(it) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeasurementUiState())

    init {
        restorePersistedMeasurement()

        // Persistenza di processo: fase, fix start/corrente e distanza a
        // piedi sopravvivono alla morte del processo. Chiave-mestra
        // KEY_ACTIVE (chiavi residue di una misura precedente ignorate
        // quando vale false).
        viewModelScope.launch {
            combine(_phase, _walked, _start, _current) { phase, walked, start, current ->
                Persisted(phase, walked, start?.let { GpsFix.fromLocation(it) }, current?.let { GpsFix.fromLocation(it) })
            }.collect { persist ->
                savedStateHandle[KEY_ACTIVE] = persist.phase != MeasurementPhase.IDLE
                if (persist.phase == MeasurementPhase.IDLE) return@collect
                savedStateHandle[KEY_PHASE] = persist.phase.ordinal
                savedStateHandle[KEY_WALKED] = persist.walked
                persist.start?.let { f -> writeFixFields(f, true) }
                persist.current?.let { f -> writeFixFields(f, false) }
            }
        }

        viewModelScope.launch {
            tracker.currentLocation.collect { location ->
                if (location == null) return@collect
                _current.value = location

                if (_phase.value == MeasurementPhase.TRACKING) {
                    val start = _start.value
                    if (start == null) {
                        _start.value = location
                        _previous.value = location
                        _waitingForFix.value = false
                        _message.value = null
                    } else {
                        val previous = _previous.value
                        if (previous != null) {
                            val step = distanceBetween(previous, location)
                            if (step in MIN_STEP_METERS..MAX_STEP_METERS) {
                                _walked.value += step
                            }
                        }
                        _previous.value = location
                    }
                }
            }
        }
    }

    fun refreshGpsStatus() {
        _gpsEnabled.value = tracker.isGpsEnabled()
    }

    // ------------------------------------------------------------------
    // Persistenza di processo (SavedStateHandle)
    // ------------------------------------------------------------------

    private data class Persisted(
        val phase: MeasurementPhase,
        val walked: Float,
        val start: GpsFix?,
        val current: GpsFix?
    )

    /** GpsFix non e' parcelable: coordinate salvate come primitive. */
    private fun writeFixFields(fix: GpsFix, isStart: Boolean) {
        val latKey = if (isStart) KEY_START_LAT else KEY_CURRENT_LAT
        val lonKey = if (isStart) KEY_START_LON else KEY_CURRENT_LON
        val accKey = if (isStart) KEY_START_ACC else KEY_CURRENT_ACC
        val timeKey = if (isStart) KEY_START_TIME else KEY_CURRENT_TIME
        val bearingKey = if (isStart) KEY_START_BEARING else KEY_CURRENT_BEARING
        savedStateHandle[latKey] = fix.latitudeDeg
        savedStateHandle[lonKey] = fix.longitudeDeg
        savedStateHandle[accKey] = fix.accuracyM
        savedStateHandle[timeKey] = fix.timeMs
        fix.bearingDeg?.let { savedStateHandle[bearingKey] = it }
    }

    private fun readFixFields(isStart: Boolean): GpsFix? {
        val latKey = if (isStart) KEY_START_LAT else KEY_CURRENT_LAT
        val lonKey = if (isStart) KEY_START_LON else KEY_CURRENT_LON
        val lat = savedStateHandle.get<Double>(latKey) ?: return null
        val lon = savedStateHandle.get<Double>(lonKey) ?: return null
        val accKey = if (isStart) KEY_START_ACC else KEY_CURRENT_ACC
        val timeKey = if (isStart) KEY_START_TIME else KEY_CURRENT_TIME
        val bearingKey = if (isStart) KEY_START_BEARING else KEY_CURRENT_BEARING
        return GpsFix(
            latitudeDeg = lat,
            longitudeDeg = lon,
            accuracyM = savedStateHandle.get<Float>(accKey) ?: 0f,
            timeMs = savedStateHandle.get<Long>(timeKey) ?: 0L,
            bearingDeg = savedStateHandle.get<Float>(bearingKey)
        )
    }

    /** Ricrea un Location "solo coordinate" per lo stato interno della misura. */
    private fun locationFrom(fix: GpsFix): Location = Location("restore").apply {
        latitude = fix.latitudeDeg
        longitude = fix.longitudeDeg
        accuracy = fix.accuracyM
        time = fix.timeMs
        fix.bearingDeg?.let { bearing = it }
    }

    /**
     * Ripristina la misura in corso (o terminata) dopo la morte del processo.
     * Se era TRACKING: riaccende la finestra GPS da 10 min sul controller.
     * Gli aggiornamenti GPS li riattiva invece la schermata con
     * onPermissionGranted(): fuori dal processo nessun listener resta
     * registrato nel tracker.
     */
    private fun restorePersistedMeasurement() {
        if (savedStateHandle.get<Boolean>(KEY_ACTIVE) != true) return
        val phase = savedStateHandle.get<Int>(KEY_PHASE)?.let { ord ->
            MeasurementPhase.entries.getOrNull(ord)
        } ?: return
        if (phase == MeasurementPhase.IDLE) {
            savedStateHandle[KEY_ACTIVE] = false
            return
        }
        _phase.value = phase
        _walked.value = savedStateHandle.get<Float>(KEY_WALKED) ?: 0f
        val startFix = readFixFields(isStart = true)
        val currentFix = readFixFields(isStart = false)
        _start.value = startFix?.let { locationFrom(it) }
        _current.value = currentFix?.let { locationFrom(it) }
        // Prossima tappa della distanza a piedi: riparte dal fix corrente.
        _previous.value = _current.value
        _waitingForFix.value = _start.value == null
        if (phase == MeasurementPhase.TRACKING) {
            powerController.setMeasurementTracking(true)
        }
    }

    fun onPermissionGranted() {
        tracker.startLocationUpdates()
        refreshGpsStatus()
    }

    fun startMeasurement() {
        if (_phase.value != MeasurementPhase.IDLE) return
        tracker.startLocationUpdates()
        refreshGpsStatus()
        _walked.value = 0f
        _message.value = null
        val current = tracker.currentLocation.value
        if (current != null) {
            _start.value = current
            _current.value = current
            _previous.value = current
            _waitingForFix.value = false
        } else {
            _waitingForFix.value = true
        }
        _phase.value = MeasurementPhase.TRACKING
        // Finestra GPS di 10 minuti in background: tempo di arrivare a piedi
        // sul punto finale (vedi BackgroundPowerController).
        powerController.setMeasurementTracking(true)
    }

    fun finishMeasurement() {
        if (_phase.value != MeasurementPhase.TRACKING) return
        val start = _start.value
        val current = _current.value
        if (start == null || current == null) {
            _message.value = "Fix GPS assente: attendi qualche secondo e riprova"
            return
        }
        val distance = distanceBetween(start, current)
        _phase.value = MeasurementPhase.DONE
        _waitingForFix.value = false
        powerController.setMeasurementTracking(false)
        store.setMeasured(distance)
        tracker.stopLocationUpdates()
    }

    fun reset() {
        tracker.stopLocationUpdates()
        _phase.value = MeasurementPhase.IDLE
        powerController.setMeasurementTracking(false)
        _start.value = null
        _current.value = null
        _previous.value = null
        _walked.value = 0f
        _waitingForFix.value = false
        _message.value = null
        _placeSaveState.value = PlaceSaveState.IDLE
        refreshGpsStatus()
        tracker.startLocationUpdates()
    }

    /** Salva la misura terminata come luogo (senza foto). */
    fun saveGpsPlace() {
        if (_phase.value != MeasurementPhase.DONE) return
        if (_placeSaveState.value == PlaceSaveState.SAVING) return
        val startLoc = _start.value
        val endLoc = _current.value
        if (startLoc == null || endLoc == null) return
        _placeSaveState.value = PlaceSaveState.SAVING
        val start = GpsFix.fromLocation(startLoc)
        val end = GpsFix.fromLocation(endLoc)
        val distance = distanceBetween(startLoc, endLoc)
        val walked = _walked.value
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val record = PlaceRecord(
                id = placesRepository.newId(),
                createdAtMs = now,
                kind = PlaceKind.GPS,
                label = placesRepository.defaultLabel(now),
                photoFile = null,
                shotFix = null,
                startFix = start,
                endFix = end,
                walkedM = walked
            )
            placesRepository.save(PlaceKind.GPS, null, record)
            _placeSaveState.value = PlaceSaveState.SAVED
        }
    }

    override fun onCleared() {
        powerController.setMeasurementTracking(false)
        tracker.stopLocationUpdates()
        super.onCleared()
    }

    companion object {
        private const val MIN_STEP_METERS = 1f
        private const val MAX_STEP_METERS = 100f

        /** Chiave-mestra del restore misura (persistenza di processo). */
        private const val KEY_ACTIVE = "measurement.active"
        private const val KEY_PHASE = "measurement.phaseOrdinal"
        private const val KEY_WALKED = "measurement.walkedM"
        private const val KEY_START_LAT = "measurement.startLat"
        private const val KEY_START_LON = "measurement.startLon"
        private const val KEY_START_ACC = "measurement.startAccuracyM"
        private const val KEY_START_TIME = "measurement.startTimeMs"
        private const val KEY_START_BEARING = "measurement.startBearingDeg"
        private const val KEY_CURRENT_LAT = "measurement.currentLat"
        private const val KEY_CURRENT_LON = "measurement.currentLon"
        private const val KEY_CURRENT_ACC = "measurement.currentAccuracyM"
        private const val KEY_CURRENT_TIME = "measurement.currentTimeMs"
        private const val KEY_CURRENT_BEARING = "measurement.currentBearingDeg"
    }
}