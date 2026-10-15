package com.miraglio.ui.measurement

import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.data.places.PlaceSaveState
import com.miraglio.data.places.PlacesRepository
import com.miraglio.data.session.ShotVerificationStore
import com.miraglio.domain.format.ItalianFormat
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.location.Haversine
import com.miraglio.domain.location.MeasurementRules
import com.miraglio.domain.location.WalkFix
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
    /**
     * Errore stimato della distanza finale (accuratezze dei due fix combinate
     * in quadratura). Accompagna il numero anche quando la misura e' stata
     * accettata nonostante l'errore elevato, e finisce nel luogo salvato e
     * nel messaggio di condivisione.
     */
    val finalErrorM: Float? = null,
    val fixAccuracyM: Float? = null,
    /** Provider del fix usato ("gps"): l'accuratezza dipende dall'origine. */
    val fixProvider: String? = null,
    /**
     * Vero se l'ultimo fix satellitare e' di adesso e abbastanza preciso da
     * misurare: sotto, il numero mostrato e' vecchio o inaffidabile e la
     * misura non si puo' chiudere.
     */
    val fixUsable: Boolean = false,
    /** Vero se il fix corrente basta anche a bloccare la partenza. */
    val fixReadyForStart: Boolean = false,
    /** In attesa del fix di partenza: nessuna partenza bloccata finora. */
    val waitingForFix: Boolean = false,
    val gpsEnabled: Boolean = true,
    /** "FINO A QUI" si abilita solo con partenza bloccata e fix di adesso. */
    val canFinish: Boolean = false,
    val estimatedDistanceM: Float? = null,
    val message: String? = null,
    /**
     * Errore stimato della misura (m), non null quando "FINO A QUI" attende
     * conferma: il valore supera la soglia oltre cui il numero finale non e'
     * piu' affidabile di quella soglia stessa.
     */
    val finishWarningErrorM: Float? = null,
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
    /** Ultimo fix usato per la misura: serve a sommare i passi e come arrivo. */
    private val _previous = MutableStateFlow<WalkFix?>(null)
    /** Ultimo fix del satellite ricevuto, anche se non utilizzabile. */
    private val _lastGnss = MutableStateFlow<Location?>(null)
    /** Partenza candidata: il fix successivo la conferma o la sostituisce. */
    private val _candidate = MutableStateFlow<WalkFix?>(null)
    /** Contatore che sveglia il ricalcolo dello stato (freschezza dei fix). */
    private val _tick = MutableStateFlow(0L)
    /** Istante in cui e' stata armata la misura: oltre START_GIVE_UP_MS si parte comunque. */
    private var armedAtMs = 0L
    private val _walked = MutableStateFlow(0f)
    private val _waitingForFix = MutableStateFlow(false)
    private val _gpsEnabled = MutableStateFlow(true)
    private val _message = MutableStateFlow<String?>(null)

    /** Non null quando la chiusura della misura attende conferma (errore > soglia). */
    private val _finishWarning = MutableStateFlow<Float?>(null)

    /** Stato di salvataggio del luogo (misura GPS). */
    private val _placeSaveState = MutableStateFlow(PlaceSaveState.IDLE)
    val placeSaveState: StateFlow<PlaceSaveState> = _placeSaveState.asStateFlow()

    val phase: StateFlow<MeasurementPhase> = _phase.asStateFlow()

    private data class LocalState(
        val phase: MeasurementPhase,
        val start: Location?,
        val current: Location?,
        val lastGnss: Location?,
        val walked: Float,
        val waitingForFix: Boolean,
        val gpsEnabled: Boolean
    )

    private fun nowElapsedMs(): Long = SystemClock.elapsedRealtime()

    private fun distanceBetween(a: Location, b: Location): Float =
        Haversine.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()

    /**
     * Errore combinato della misura: l'accuratezza dei fix e' un raggio di
     * confidenza, quindi i due errori si sommano in quadratura (due fix da
     * ±4 m danno ~±5,7 m sulla distanza).
     */
    private fun measurementErrorM(start: Location, end: Location): Float {
        val a = start.accuracy
        val b = end.accuracy
        return kotlin.math.sqrt(a * a + b * b)
    }

    private data class Trio(
        val start: Location?,
        val current: Location?,
        val lastGnss: Location?
    )

    private val positions = combine(_start, _current, _lastGnss) { start, current, last ->
        Trio(start, current, last)
    }

    private val localState: StateFlow<LocalState> = combine(
        positions,
        _phase,
        _walked,
        _waitingForFix,
        _gpsEnabled
    ) { positions, phase, walked, waiting, gpsEnabled ->
        LocalState(
            phase,
            positions.start,
            positions.current,
            positions.lastGnss,
            walked,
            waiting,
            gpsEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalState(
        phase = MeasurementPhase.IDLE,
        start = null,
        current = null,
        lastGnss = null,
        walked = 0f,
        waitingForFix = false,
        gpsEnabled = true
    ))

    val uiState: StateFlow<MeasurementUiState> = combine(
        localState,
        store.estimatedDistanceM,
        _finishWarning,
        _tick
    ) { local, estimated, finishWarning, _ ->
        // Freschezza e qualita' del fix si valutano adesso, a ogni ricalcolo:
        // un fix di dieci secondi fa non e' piu' "di adesso".
        val now = nowElapsedMs()
        val lastGnss = local.lastGnss?.let { WalkFix.from(it) }
        val fixUsable = lastGnss != null &&
            MeasurementRules.isUsable(lastGnss, now, MeasurementRules.TRACK_MAX_ACCURACY_M)
        val fixReadyForStart = lastGnss != null &&
            MeasurementRules.isUsable(lastGnss, now, MeasurementRules.START_MAX_ACCURACY_M)
        val liveDistance = if (local.phase != MeasurementPhase.IDLE &&
            local.start != null && local.current != null
        ) {
            distanceBetween(local.start!!, local.current!!)
        } else {
            0f
        }
        val finalDistance = if (local.phase == MeasurementPhase.DONE) liveDistance else null
        // Errore stimato della distanza: stesso calcolo della soglia di
        // conferma, esposto affinche' resti attaccato al numero (schermata,
        // luogo salvato, condivisione).
        val finalError = if (finalDistance != null &&
            local.start != null && local.current != null
        ) {
            measurementErrorM(local.start!!, local.current!!)
        } else {
            null
        }
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
            finalErrorM = finalError,
            fixAccuracyM = local.lastGnss?.accuracy,
            fixProvider = local.lastGnss?.provider,
            fixUsable = fixUsable,
            fixReadyForStart = fixReadyForStart,
            waitingForFix = local.waitingForFix,
            gpsEnabled = local.gpsEnabled,
            canFinish = local.phase == MeasurementPhase.TRACKING &&
                local.start != null && fixUsable,
            estimatedDistanceM = estimated,
            message = _message.value,
            finishWarningErrorM = finishWarning,
            shareMessage = if (finalDistance != null && endFix != null) {
                ShareMessageComposer.gpsPlace(finalDistance, startFix, endFix, finalError)
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

        // Si ascolta il solo provider satellitare: un fix di rete "sembra"
        // accurato ma puo' stare decine di metri piu' in la', e come partenza
        // inventerebbe metri.
        viewModelScope.launch {
            tracker.gnssLocation.collect { location ->
                if (location == null) return@collect
                _lastGnss.value = location
                if (_phase.value != MeasurementPhase.TRACKING) return@collect
                onGnssFix(location)
            }
        }
    }

    /**
     * Un fix satellitare nuovo durante una misura: o blocca la partenza, o
     * somma il passo percorso e aggiorna l'arrivo provvisorio.
     */
    private fun onGnssFix(location: Location) {
        val now = nowElapsedMs()
        val fix = WalkFix.from(location)

        if (_start.value == null) {
            when {
                // Fix buono: il successivo lo conferma. Il primo fix dopo
                // l'aggancio puo' essere un valore isolato a decine di metri e
                // come partenza sarebbe una bugia.
                MeasurementRules.isUsable(fix, now, MeasurementRules.START_MAX_ACCURACY_M) -> {
                    val candidate = _candidate.value
                    if (candidate != null &&
                        MeasurementRules.distanceM(candidate, fix) <=
                        MeasurementRules.START_CONFIRM_DISTANCE_M
                    ) {
                        lockStart(location, warning = null)
                    } else {
                        _candidate.value = fix
                    }
                }
                // Nessun fix buono dopo il tempo di grazia: si parte con
                // quello che c'e', dichiarando l'accuratezza reale.
                now - armedAtMs >= MeasurementRules.START_GIVE_UP_MS &&
                    MeasurementRules.isUsable(fix, now, MeasurementRules.TRACK_MAX_ACCURACY_M) -> {
                    lockStart(
                        location,
                        warning = "Partenza con precisione ridotta: " +
                            "±${ItalianFormat.meters(fix.accuracyM)}"
                    )
                }
                else -> _candidate.value = null
            }
            return
        }

        // Partenza bloccata: un fix scadente non entra nella misura (ne' nei
        // passi, ne' come arrivo), ma resta visibile come precisione in corso.
        if (!MeasurementRules.isUsable(fix, now, MeasurementRules.TRACK_MAX_ACCURACY_M)) return
        _previous.value?.let { previous ->
            MeasurementRules.stepMeters(previous, fix)?.let { step -> _walked.value += step }
        }
        _previous.value = fix
        _current.value = location
    }

    private fun lockStart(location: Location, warning: String?) {
        _start.value = location
        _current.value = location
        _previous.value = WalkFix.from(location)
        _candidate.value = null
        _waitingForFix.value = false
        _message.value = warning
    }

    fun refreshGpsStatus() {
        _gpsEnabled.value = tracker.isGpsEnabled()
        // Sveglia il ricalcolo: senza, lo stato resterebbe convinto che il fix
        // sia ancora fresco anche se il GPS ha smesso di aggiornarlo.
        _tick.value++
    }

    /**
     * Cancella dall'handle ogni traccia della misura: serve all'inizio e alla
     * fine di ognuna, altrimenti una morte del processo durante la misura
     * nuova ripristinerebbe partenza e percorso di quella precedente.
     */
    private fun clearPersistedMeasurement() {
        savedStateHandle[KEY_ACTIVE] = false
        savedStateHandle[KEY_PHASE] = MeasurementPhase.IDLE.ordinal
        savedStateHandle[KEY_WALKED] = 0f
        listOf(
            KEY_START_LAT, KEY_START_LON, KEY_START_ACC, KEY_START_TIME, KEY_START_BEARING,
            KEY_CURRENT_LAT, KEY_CURRENT_LON, KEY_CURRENT_ACC, KEY_CURRENT_TIME,
            KEY_CURRENT_BEARING
        ).forEach { savedStateHandle.remove<Any>(it) }
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
        val startFix = readFixFields(isStart = true)
        val currentFix = readFixFields(isStart = false)
        // Una misura ripresa dopo troppo tempo non e' piu' quella misura: la
        // partenza apparteneva a un altro momento e a un altro punto. Si torna
        // a IDLE, non si arma niente con quella partenza.
        val startAgeMs = startFix?.let { System.currentTimeMillis() - it.timeMs }
        if (startAgeMs != null && startAgeMs > RESTORE_MAX_AGE_MS) {
            clearPersistedMeasurement()
            return
        }
        _phase.value = phase
        _walked.value = savedStateHandle.get<Float>(KEY_WALKED) ?: 0f
        _candidate.value = null
        armedAtMs = nowElapsedMs()
        _start.value = startFix?.let { locationFrom(it) }
        _current.value = currentFix?.let { locationFrom(it) }
        // Prossima tappa della distanza a piedi: riparte dal fix corrente.
        _previous.value = _current.value?.let { WalkFix.from(it) }
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
        // Azzeramento: nessun valore della misura precedente (ne' il fix di
        // prima salvato nell'handle) deve entrare in questa.
        clearPersistedMeasurement()
        _start.value = null
        _current.value = null
        _previous.value = null
        _candidate.value = null
        _walked.value = 0f
        _message.value = null
        _finishWarning.value = null
        _placeSaveState.value = PlaceSaveState.IDLE
        armedAtMs = nowElapsedMs()
        // La partenza si blocca solo con un fix di adesso: fino ad allora la
        // distanza non si mostra (un numero vecchio sarebbe peggio di niente).
        _waitingForFix.value = true
        _phase.value = MeasurementPhase.TRACKING
        // Finestra GPS di 10 minuti in background: tempo di arrivare a piedi
        // sul punto finale (vedi BackgroundPowerController).
        powerController.setMeasurementTracking(true)
    }

    fun finishMeasurement() {
        if (_phase.value != MeasurementPhase.TRACKING) return
        val start = _start.value
        if (start == null) {
            _message.value = "Attendi il fix di partenza: serve un fix satellitare (all'aperto)"
            return
        }
        val end = validEndFix()
        if (end == null) {
            _message.value = "Fix GPS non aggiornato: attendi qualche secondo in cielo aperto e riprova"
            return
        }
        // Sotto la soglia si chiude subito; sopra si chiede conferma, perche'
        // il numero finale sarebbe sbagliato di oltre la soglia stessa.
        val error = measurementErrorM(start, end)
        if (error > FINISH_WARN_ERROR_METERS) {
            _finishWarning.value = error
            return
        }
        completeMeasurement(start, end)
    }

    /** "Continua": chiude la misura nonostante l'errore stimato elevato. */
    fun confirmFinishMeasurement() {
        if (_phase.value != MeasurementPhase.TRACKING) return
        val start = _start.value ?: return
        val end = validEndFix()
        if (end == null) {
            _finishWarning.value = null
            _message.value = "Fix GPS non aggiornato: attendi qualche secondo in cielo aperto e riprova"
            return
        }
        _finishWarning.value = null
        completeMeasurement(start, end)
    }

    /**
     * Fix con cui chiudere la misura: l'ultimo utilizzato, se e' ancora di
     * adesso e accurato. Un fix vecchio come arrivo darebbe una distanza che
     * non e' quella percorsa.
     */
    private fun validEndFix(): Location? {
        val end = _current.value ?: return null
        val now = nowElapsedMs()
        if (!MeasurementRules.isUsable(WalkFix.from(end), now, MeasurementRules.TRACK_MAX_ACCURACY_M)) {
            return null
        }
        return end
    }

    /** "Aspetta": resta in TRACKING in attesa di un fix migliore. */
    fun dismissFinishWarning() {
        _finishWarning.value = null
    }

    private fun completeMeasurement(start: Location, end: Location) {
        val distance = distanceBetween(start, end)
        _phase.value = MeasurementPhase.DONE
        _candidate.value = null
        _waitingForFix.value = false
        _finishWarning.value = null
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
        _candidate.value = null
        _walked.value = 0f
        _waitingForFix.value = false
        _message.value = null
        _finishWarning.value = null
        _placeSaveState.value = PlaceSaveState.IDLE
        clearPersistedMeasurement()
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
        /** Oltre questa eta' una misura ripresa non e' piu' attendibile. */
        private const val RESTORE_MAX_AGE_MS = 15L * 60_000

        /**
         * Oltre questo errore combinato sui due fix la distanza finale non e'
         * affidabile: si chiede conferma prima di registrarla.
         */
        const val FINISH_WARN_ERROR_METERS = 5f

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