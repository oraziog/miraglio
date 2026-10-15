package com.miraglio.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.camera.CameraService
import com.miraglio.data.camera.MiraglioAnalyzer
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.ocr.OcrTextRecognizer
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.data.places.PlaceSaveState
import com.miraglio.data.places.PlacesRepository
import com.miraglio.data.sensor.Compass
import com.miraglio.data.sensor.Inclinometer
import com.miraglio.data.session.ShotVerificationStore
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.data.share.ShareImageComposer
import com.miraglio.domain.camera.CameraIntrinsics
import com.miraglio.domain.camera.PinholeDistanceEstimator
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.location.TargetEstimator
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.domain.vision.DetectionBox
import com.miraglio.domain.vision.ObjectDetector
import com.miraglio.domain.vision.TargetScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos

enum class CaptureDistanceMode { AUTO, TAP, MANUAL, LINEE }

/** Asse di misura delle linee: orizzontali = altezza, verticali = larghezza. */
enum class LineOrientation { HORIZONTAL, VERTICAL }

/**
 * Direzione del calcolo tra le linee sul fermo immagine: DISTANZA = da
 * dimensione nota (comportamento storico), ALTEZZA = da distanza nota
 * viene calcolata la dimensione reale dell'oggetto tra le linee.
 */
enum class MeasureDirection { DISTANZA, ALTEZZA }

/** Miglior box live con dimensioni del frame che lo ha prodotto. */
data class LiveSnap(
    val box: DetectionBox,
    val frameWidth: Int,
    val frameHeight: Int
)

/**
 * Classe di bersaglio selezionata dall'utente per il rilevamento AUTO.
 * [visionLabel] deve corrispondere al nome classe del modello YOLO.
 */
enum class TargetClass(
    val uiLabel: String,
    val visionLabel: String,
    val widthPresets: List<Float>,
    val defaultWidth: Float,
    val heightPresets: List<Float>,
    val defaultHeight: Float
) {
    PAGLIONE(
        uiLabel = "Paglione",
        visionLabel = "circular_target",
        widthPresets = listOf(0.40f, 0.60f, 0.80f, 1.30f),
        defaultWidth = 1.30f,
        heightPresets = listOf(0.40f, 0.60f, 0.80f, 1.30f),
        defaultHeight = 1.30f
    ),
    TARGA(
        uiLabel = "Targa 3D",
        visionLabel = "rectangular_target",
        widthPresets = listOf(0.40f, 0.62f, 0.80f),
        defaultWidth = 0.80f,
        heightPresets = listOf(0.40f, 0.62f, 0.80f),
        defaultHeight = 0.80f
    )
}

/** Esito della misura live: distanza stimata e angolo al momento della lettura. */
data class MeasureResult(
    val distanceMeters: Float,
    val pitchAngleDegrees: Float
) {
    /**
     * Distanza orizzontale compensata con l'angolo di tiro (coseno):
     * e' la distanza su calibrare il tiro quando si punta dall'alto in basso
     * (o viceversa). Con angolo ~0 coincide con la distanza grezza.
     */
    val horizontalDistanceM: Float
        get() = distanceMeters * cos(Math.toRadians(pitchAngleDegrees.toDouble())).toFloat()
}

data class MeasureUiState(
    val result: MeasureResult? = null,
    val measuredSizeM: Float? = null,
    /** Direzione di mira (nord vero) associata alla lettura corrente. */
    val bearingDegrees: Float? = null,
    val tapLeftNorm: Float? = null,
    val tapRightNorm: Float? = null,
    val frameWidthPx: Int = 0,
    val lineTopNorm: Float? = null,
    val lineBottomNorm: Float? = null,
    val lineFrameHeightPx: Int = 0,
    val pitchAngleDegrees: Float? = null
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val cameraService: CameraService,
    private val inclinometer: Inclinometer,
    private val compass: Compass,
    private val locationTracker: LocationTracker,
    private val detector: ObjectDetector,
    private val ocrRecognizer: OcrTextRecognizer,
    private val focalStore: FocalCalibrationStore,
    private val shotStore: ShotVerificationStore,
    private val placesRepository: PlacesRepository,
    private val settings: AppSettingsStore,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Focale teorica (FOV diagonale 67°) alla risoluzione corrente del flusso
    // di analisi; aggiornata quando arriva il primo frame reale.
    @Volatile
    private var theoreticalIntrinsics: CameraIntrinsics =
        CameraIntrinsics.fromDiagonalFov(67f, 1280, 960)

    /** Preview e lifecycle trattenuti solo per i re-bind della camera. */
    private class BoundPreview(
        val lifecycleOwner: LifecycleOwner,
        val previewView: PreviewView
    )

    private var boundPreview: BoundPreview? = null
    private var cameraBoundOnce = false

    private val _latestBoxWidthPx = MutableStateFlow(0f)
    private val _frameWidthPx = MutableStateFlow(960)
    /** Lato lungo del bitmap di analisi corrente (per riscalare le focali salvate). */
    private val _currentFrameLongSide = MutableStateFlow(1280)
    private val _mode = MutableStateFlow(CaptureDistanceMode.LINEE)
    // Il paglione FITA e' 130 cm; 122 cm e' il bersaglio apposto sul paglione.
    private val _heightInputCm = MutableStateFlow("130")
    private val _widthInputCm = MutableStateFlow("130")
    private val _zoomRatio = MutableStateFlow(1f)
    private val _tapLeftNorm = MutableStateFlow<Float?>(null)
    private val _tapRightNorm = MutableStateFlow<Float?>(null)
    private val _frozenBitmap = MutableStateFlow<Bitmap?>(null)
    private val _lineTopNorm = MutableStateFlow<Float?>(null)
    private val _lineBottomNorm = MutableStateFlow<Float?>(null)
    private val _lineFrameHeightPx = MutableStateFlow(0)
    private val _lineFrameWidthPx = MutableStateFlow(0)
    private val _lineOrientation = MutableStateFlow(LineOrientation.HORIZONTAL)
    private val _lineeBoxes = MutableStateFlow<List<DetectionBox>>(emptyList())
    private val _liveSnap = MutableStateFlow<LiveSnap?>(null)
    val liveSnap: StateFlow<LiveSnap?> = _liveSnap.asStateFlow()

    /** Bersagli rilevati sul fermo immagine; vuoti a riconoscimento spento. */
    val detectionBoxes: StateFlow<List<DetectionBox>> = _lineeBoxes.asStateFlow()

    private val _measureDirection = MutableStateFlow(MeasureDirection.DISTANZA)
    private val _measureDistanceInput = MutableStateFlow("")

    /** Pitch bloccato al momento del fermo immagine (per una lettura stabile). */
    private val _frozenPitchDegrees = MutableStateFlow<Float?>(null)

    // --- Luoghi: direzione e posizione congelate allo scatto ---
    private val _frozenAzimuthDegrees = MutableStateFlow<Float?>(null)
    private val _frozenGpsFix = MutableStateFlow<GpsFix?>(null)
    private val _gpsRequested = MutableStateFlow(false)
    private val _placeSaveState = MutableStateFlow(PlaceSaveState.IDLE)

    /** Azimuth di mira live (nord vero) per il chip direzione. */
    val azimuthDegrees: StateFlow<Float> = compass.azimuthDegrees
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val placeSaveState: StateFlow<PlaceSaveState> = _placeSaveState.asStateFlow()

    private val _lensId = MutableStateFlow<String?>(null)
    private val _lensLabel = MutableStateFlow<String?>(null)
    /** Etichetta tipo "Lente 2/3": null se il dispositivo ha una sola lente. */
    val lensLabel: StateFlow<String?> = _lensLabel.asStateFlow()

    private data class TapInfo(
        val left: Float?,
        val right: Float?,
        val frameWidth: Int
    )

    private data class LineInfo(
        val top: Float?,
        val bottom: Float?,
        val frameHeight: Int,
        val frameWidth: Int
    )

    private data class ConfigInfo(
        val knownHeight: Float,
        val knownWidth: Float,
        val mode: CaptureDistanceMode,
        val fxPx: Float?,
        val fyPx: Float?,
        val zoomRatio: Float,
        val direction: MeasureDirection,
        val measureDistanceM: Float
    )

    private val tapInfo = combine(
        _tapLeftNorm,
        _tapRightNorm,
        _frameWidthPx
    ) { left, right, frameWidth -> TapInfo(left, right, frameWidth) }

    private val lineInfo = combine(
        _lineTopNorm,
        _lineBottomNorm,
        _lineFrameHeightPx,
        _lineFrameWidthPx
    ) { top, bottom, frameHeight, frameWidth -> LineInfo(top, bottom, frameHeight, frameWidth) }

    private val zoomedFocal = combine(
        focalStore.focalFlow,
        _zoomRatio,
        _currentFrameLongSide
    ) { focal, zoom, frameLongSide ->
        // I valori salvati possono essere in pixel 640: si riscalano alla
        // risoluzione corrente prima di combinarli con lo zoom digitale.
        val scaled = focalStore.focalForFrame(frameLongSide)
        Triple(scaled.first, scaled.second, zoom)
    }

    private data class MeasureConfig(
        val direction: MeasureDirection,
        val distanceM: Float
    )

    private val measureConfig = combine(
        _measureDirection,
        _measureDistanceInput
    ) { direction, distInput ->
        MeasureConfig(
            direction,
            distInput.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } ?: 0f
        )
    }

    private val configInfo = combine(
        _heightInputCm,
        _widthInputCm,
        _mode,
        zoomedFocal,
        measureConfig
    ) { heightCm, widthCm, mode, zoomFocal, measure ->
        ConfigInfo(
            knownHeight = heightCm.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.div(100f)
                ?: TargetClass.PAGLIONE.defaultHeight,
            knownWidth = widthCm.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.div(100f)
                ?: TargetClass.PAGLIONE.defaultWidth,
            mode = mode,
            fxPx = zoomFocal.first,
            fyPx = zoomFocal.second,
            zoomRatio = zoomFocal.third,
            direction = measure.direction,
            measureDistanceM = measure.distanceM
        )
    }

    val heightInputCm: StateFlow<String> = _heightInputCm.asStateFlow()

    val widthInputCm: StateFlow<String> = _widthInputCm.asStateFlow()

    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    val lineOrientation: StateFlow<LineOrientation> = _lineOrientation.asStateFlow()

    val measureDirection: StateFlow<MeasureDirection> = _measureDirection.asStateFlow()

    val measureDistanceInput: StateFlow<String> = _measureDistanceInput.asStateFlow()

    /**
     * Modalita' outdoor: il tema (colori e dimensione dei testi) della UI.
     * Serve qui solo per far vedere lo stato del bottone nella barra in alto:
     * la preferenza vive in AppSettingsStore e la legge MainActivity.
     */
    val outdoorMode: StateFlow<Boolean> = settings.outdoorModeEnabled

    fun toggleOutdoorMode() {
        settings.setOutdoorModeEnabled(!settings.outdoorModeEnabled.value)
    }

    fun toggleLineOrientation() {
        _lineOrientation.value = if (_lineOrientation.value == LineOrientation.HORIZONTAL) {
            LineOrientation.VERTICAL
        } else {
            LineOrientation.HORIZONTAL
        }
    }

    private val tapLineOrientation = combine(
        tapInfo,
        lineInfo,
        _lineOrientation
    ) { tap, line, orientation -> Triple(tap, line, orientation) }

    private val pitchAndBearing = combine(
        inclinometer.pitchAngleDegrees,
        compass.azimuthDegrees,
        _frozenAzimuthDegrees
    ) { pitch, bearing, frozenBearing -> Triple(pitch, bearing, frozenBearing) }

    val captureState: StateFlow<MeasureUiState> = combine(
        tapLineOrientation,
        configInfo,
        _latestBoxWidthPx,
        pitchAndBearing
    ) { tapLineOrientation, config, autoWidthPx, pb ->
        stateFor(
            tap = tapLineOrientation.first,
            config = config,
            line = tapLineOrientation.second,
            orientation = tapLineOrientation.third,
            autoWidthPx = autoWidthPx,
            pitch = pb.first,
            bearingLive = pb.second,
            bearingFrozen = pb.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeasureUiState())

    // ORDINE OBBLIGATORIO: in Kotlin le proprieta' si inizializzano in ordine
    // di sorgente e il corpo di viewModelScope.launch (dispatcher
    // Main.immediate) parte SINCRONO dentro il costruttore. Dichiarata dopo
    // init, questa combine verrebbe letta ancora null -> NPE durante la
    // costruzione del ViewModel, cioe' crash immediato all'avvio dell'app.
    private val persistedLines = combine(
        _lineTopNorm,
        _lineBottomNorm,
        _lineFrameHeightPx,
        _lineFrameWidthPx,
        _lineOrientation
    ) { top, bottom, frameHeight, frameWidth, orientation ->
        SavedLines(top, bottom, frameHeight, frameWidth, orientation)
    }

    init {
        restoreSavedState()

        // Persistenza di processo: input + stato del fermo immagine (linee,
        // sensori congelati, orientamento). La bitmap non è serializzabile
        // nel Bundle della SavedState (TransactionTooLarge): va su file
        // (filesDir/capture_state/frozen.jpg).
        viewModelScope.launch {
            combine(
                persistedLines,
                combine(
                    _frozenPitchDegrees,
                    _frozenAzimuthDegrees,
                    _frozenGpsFix,
                    _mode,
                    _measureDirection
                ) { pitch, azimuth, fix, mode, direction ->
                    SavedSensors(pitch, azimuth, fix, mode, direction)
                },
                _heightInputCm,
                _widthInputCm,
                _measureDistanceInput
            ) { lines, sensors, heightCm, widthCm, distanceInput ->
                SavedState(lines, sensors, heightCm, widthCm, distanceInput)
            }.collect { persisted -> persistAll(persisted) }
        }

        // Declinazione magnetica sempre aggiornata sull'ultima posizione nota.
        viewModelScope.launch {
            locationTracker.currentLocation.collect { location ->
                if (location != null) compass.updateDeclination(location)
            }
        }
        viewModelScope.launch {
            cameraService.defaultBackCameraId.filterNotNull().first().let { defId ->
                focalStore.setDefaultLensId(defId, cameraService.defaultBackCameraFocalMm.value)
                if (_lensId.value == null) _lensId.value = defId
                refreshLensLabel()
            }
        }
        viewModelScope.launch {
            cameraService.boundLensId.filterNotNull().collect { bound ->
                if (_lensId.value != bound) {
                    _lensId.value = bound
                    focalStore.setActiveLens(bound)
                }
                refreshLensLabel()
            }
        }
        // Handoff verso la verifica GPS: la stima da confrontare e' la distanza
        // orizzontale compensata, che e' quella percorsa a piedi sul terreno.
        viewModelScope.launch {
            captureState.collect { state ->
                state.result?.let { shotStore.setEstimated(it.horizontalDistanceM) }
            }
        }
    }

    val sensorActive = inclinometer.sensorActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val mode: StateFlow<CaptureDistanceMode> = _mode.asStateFlow()

    val frozenBitmap: StateFlow<Bitmap?> = _frozenBitmap.asStateFlow()

    /** Snapshot delle linee sul fermo immagine, per la persistenza. */
    private data class SavedLines(
        val top: Float?,
        val bottom: Float?,
        val frameHeightPx: Int,
        val frameWidthPx: Int,
        val orientation: LineOrientation
    )

    private data class SavedSensors(
        val pitch: Float?,
        val azimuth: Float?,
        val fix: GpsFix?,
        val mode: CaptureDistanceMode,
        val direction: MeasureDirection
    )

    private data class SavedState(
        val lines: SavedLines,
        val sensors: SavedSensors,
        val heightInputCm: String,
        val widthInputCm: String,
        val distanceInput: String
    )

    /** Linee centrate con apertura di default: nessun bersaglio riconosciuto. */
    private fun centerFreeLines() {
        _lineTopNorm.value = 0.5f - DEFAULT_FREE_LINE_GAP / 2f
        _lineBottomNorm.value = 0.5f + DEFAULT_FREE_LINE_GAP / 2f
    }

    fun freezeFrame() {
        freezeShotSensors()
        _frozenBitmap.value = analyzer.getLatestBitmap()
        _frozenBitmap.value?.let { persistFrozenImage(it) }
    }

    fun unfreeze() {
        _frozenBitmap.value = null
        _lineeBoxes.value = emptyList()
        // Azzera le linee: senza fermo immagine non deve esserci nessun calcolo.
        _lineTopNorm.value = null
        _lineBottomNorm.value = null
        _lineFrameHeightPx.value = 0
        _lineFrameWidthPx.value = 0
        _frozenPitchDegrees.value = null
        _frozenAzimuthDegrees.value = null
        _frozenGpsFix.value = null
        _placeSaveState.value = PlaceSaveState.IDLE
        // La chiave-mestra a null disattiva il restore del fermo immagine: le
        // chiavi residue di linee/sensori restano ignorate al ripristino.
        saveStateHandleFailsafeNull()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { frozenImageFile().delete() }
        }
    }

    /** Scrittura null protetta su SavedStateHandle (chiave-mestra). */
    private fun saveStateHandleFailsafeNull() {
        runCatching { savedStateHandle.set(KEY_FROZEN_FILE, null) }
    }

    /** Congela pitch, direzione di mira e fix GPS nell'istante dello scatto. */
    private fun freezeShotSensors() {
        _frozenPitchDegrees.value = inclinometer.pitchAngleDegrees.value
        _frozenAzimuthDegrees.value = compass.azimuthDegrees.value
        _frozenGpsFix.value = locationTracker.currentLocation.value?.let { GpsFix.fromLocation(it) }
    }

    // ------------------------------------------------------------------
    // Luoghi: condivisione e salvataggio dello scatto corrente
    // ------------------------------------------------------------------

    /** Fix stimato del bersaglio (scatto + bussola + distanza orizzontale). */
    private fun cameraTargetFix(): GpsFix? {
        val shot = _frozenGpsFix.value ?: return null
        val azimuth = _frozenAzimuthDegrees.value ?: return null
        val horizontal = captureState.value.result?.horizontalDistanceM ?: return null
        return TargetEstimator.estimateTarget(shot, azimuth, horizontal)
    }

    /** Messaggio di condivisione dello scatto corrente (null: niente da condividere). */
    fun cameraShareMessage(): String? {
        val state = captureState.value
        val result = state.result ?: return null
        return ShareMessageComposer.cameraPlace(
            fix = _frozenGpsFix.value,
            bearingDeg = _frozenAzimuthDegrees.value ?: compass.azimuthDegrees.value,
            distanceM = result.distanceMeters,
            horizontalDistanceM = result.horizontalDistanceM,
            pitchDeg = result.pitchAngleDegrees,
            measuredSizeM = state.measuredSizeM,
            target = cameraTargetFix()
        )
    }

    /** Contenuto del QR: pin del bersaglio stimato, altrimenti pin dello
     *  scatto; senza alcun fix si codifica il messaggio di condivisione. */
    fun cameraQrContent(): String? {
        val fix = cameraTargetFix() ?: _frozenGpsFix.value
        return fix?.let { ShareMessageComposer.mapsLink(it) } ?: cameraShareMessage()
    }

    /**
     * Foto da condividere: fermo immagine + banda dati, scritta in cache.
     * null se non c'e' nulla di condivisibile o la scrittura fallisce.
     */
    fun cameraShareImageUri(): Uri? {
        val bitmap = _frozenBitmap.value ?: return null
        val message = cameraShareMessage() ?: return null
        val lines = message.lineSequence().filter { it.isNotBlank() }.take(4).toList()
        val composed = ShareImageComposer.compose(bitmap, lines) ?: bitmap
        return runCatching {
            val dir = File(appContext.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "place_share.jpg")
            file.outputStream().use { out ->
                composed.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    /** Salva il luogo (foto congelata + misura + GPS + bersaglio stimato). */
    fun saveCameraPlace() {
        if (_placeSaveState.value == PlaceSaveState.SAVING) return
        val bitmap = _frozenBitmap.value ?: return
        val state = captureState.value
        val result = state.result ?: return
        _placeSaveState.value = PlaceSaveState.SAVING
        viewModelScope.launch(Dispatchers.IO) {
            val shot = _frozenGpsFix.value
            val now = System.currentTimeMillis()
            val record = PlaceRecord(
                id = placesRepository.newId(),
                createdAtMs = now,
                kind = PlaceKind.CAMERA,
                label = placesRepository.defaultLabel(now),
                photoFile = null,
                shotFix = shot,
                bearingDeg = _frozenAzimuthDegrees.value,
                pitchDeg = result.pitchAngleDegrees,
                distanceM = result.distanceMeters,
                horizontalDistanceM = result.horizontalDistanceM,
                zoomRatio = _zoomRatio.value,
                lensLabel = _lensLabel.value,
                targetFix = cameraTargetFix(),
                measuredSizeM = state.measuredSizeM
            )
            val saved = placesRepository.save(PlaceKind.CAMERA, bitmap, record)
            if (saved != null) {
                val photo = placesRepository.photoFile(saved)
                val fix = shot
                if (photo != null && fix != null) {
                    ShareImageComposer.writeExifGps(photo, fix)
                }
                _placeSaveState.value = PlaceSaveState.SAVED
            } else {
                _placeSaveState.value = PlaceSaveState.FAILED
            }
        }
    }

    /** Scatta il fermo immagine ed esegue il rilevamento per la modalita' LINEE. */
    fun freezeAndAnalyze() {
        val bmp = analyzer.getLatestBitmap() ?: return
        freezeShotSensors()
        _frozenBitmap.value = bmp
        persistFrozenImage(bmp)
        _lineFrameHeightPx.value = bmp.height
        _lineFrameWidthPx.value = bmp.width
        // Parti dallo snap live (continuità visiva), altrimenti linee centrate spaziate
        val snap = _liveSnap.value
        if (snap != null) {
            if (_lineOrientation.value == LineOrientation.HORIZONTAL) {
                _lineTopNorm.value = snap.box.top.coerceIn(0f, 1f)
                _lineBottomNorm.value = snap.box.bottom.coerceIn(0f, 1f)
            } else {
                _lineTopNorm.value = snap.box.left.coerceIn(0f, 1f)
                _lineBottomNorm.value = snap.box.right.coerceIn(0f, 1f)
            }
        } else {
            centerFreeLines()
        }
        analyzeFrozenLines()
    }

    /**
     * Rilevamento sul fermo immagine e aggancio delle linee al bersaglio piu'
     * grande inquadrato. Gira solo con "Riconoscimento paglione" acceso:
     * spento, non compare nessun riquadro e le linee restano quelle di sempre.
     */
    fun analyzeFrozenLines() {
        val bmp = _frozenBitmap.value ?: return
        _lineFrameHeightPx.value = bmp.height
        _lineFrameWidthPx.value = bmp.width
        if (!settings.recognitionEnabled.value) {
            _lineeBoxes.value = emptyList()
            centerFreeLines()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { detector.detect(bmp) }.getOrNull() ?: return@launch
            // Nessun filtro di classe: vince il bersaglio piu' grande
            // inquadrato, paglione o targa che sia.
            val boxes = result.detections
            _lineeBoxes.value = boxes
            val best = TargetScorer.largest(boxes)
            if (best != null) {
                if (_lineOrientation.value == LineOrientation.HORIZONTAL) {
                    _lineTopNorm.value = best.top.coerceIn(0f, 1f)
                    _lineBottomNorm.value = best.bottom.coerceIn(0f, 1f)
                } else {
                    _lineTopNorm.value = best.left.coerceIn(0f, 1f)
                    _lineBottomNorm.value = best.right.coerceIn(0f, 1f)
                }
            } else {
                // Nessun rilevamento: linee centrate sulla crocetta (centro immagine)
                centerFreeLines()
            }
        }
    }

    /**
     * Tap sul fermo immagine in modalita' LINEE: aggancia le linee al box
     * rilevato piu' vicino (soglia 0.18), altrimenti piazza linee libere
     * centrate sul punto di tap.
     */
    fun registerLinesTap(normalized: Float) {
        val v = normalized.coerceIn(0f, 1f)
        val horizontal = _lineOrientation.value == LineOrientation.HORIZONTAL
        val snap = if (horizontal) {
            _lineeBoxes.value
                .minByOrNull { abs(it.centerY - v) }
                ?.takeIf { abs(it.centerY - v) <= SNAP_THRESHOLD_NORM }
        } else {
            _lineeBoxes.value
                .minByOrNull { abs(it.centerX - v) }
                ?.takeIf { abs(it.centerX - v) <= SNAP_THRESHOLD_NORM }
        }
        if (snap != null) {
            if (horizontal) {
                _lineTopNorm.value = snap.top.coerceIn(0f, 1f)
                _lineBottomNorm.value = snap.bottom.coerceIn(0f, 1f)
            } else {
                _lineTopNorm.value = snap.left.coerceIn(0f, 1f)
                _lineBottomNorm.value = snap.right.coerceIn(0f, 1f)
            }
            return
        }
        val gap = currentLineGapOrDefault()
        var a = v - gap / 2f
        var b = v + gap / 2f
        if (a < 0f) {
            b -= a
            a = 0f
        }
        if (b > 1f) {
            a -= (b - 1f)
            b = 1f
        }
        _lineTopNorm.value = a.coerceIn(0f, 1f)
        _lineBottomNorm.value = b.coerceIn(0f, 1f).coerceAtLeast(a.coerceIn(0f, 1f) + MIN_LINE_GAP)
    }

    /** Sposta entrambe le linee in verticale preservando l'apertura (zona SX). */
    fun panLines(dyNorm: Float) {
        val top = _lineTopNorm.value ?: return
        val bottom = _lineBottomNorm.value ?: return
        val gap = (bottom - top).coerceAtLeast(MIN_LINE_GAP)
        val newTop = (top + dyNorm).coerceIn(0f, 1f - gap)
        _lineTopNorm.value = newTop
        _lineBottomNorm.value = newTop + gap
    }

    /** Sposta solo la linea superiore/sinistra, rispettando il gap minimo. */
    fun moveFirstLine(deltaNorm: Float) {
        val top = _lineTopNorm.value ?: return
        val bottom = _lineBottomNorm.value ?: return
        _lineTopNorm.value = (top + deltaNorm).coerceIn(0f, (bottom - MIN_LINE_GAP).coerceAtLeast(0f))
    }

    /** Sposta solo la linea inferiore/destra, rispettando il gap minimo. */
    fun moveSecondLine(deltaNorm: Float) {
        val top = _lineTopNorm.value ?: return
        val bottom = _lineBottomNorm.value ?: return
        _lineBottomNorm.value = (bottom + deltaNorm).coerceIn((top + MIN_LINE_GAP).coerceAtMost(1f), 1f)
    }

    /**
     * Allarga (delta > 0) o stringe (delta < 0) le linee in modo simmetrico
     * rispetto al punto medio (zona DX).
     */
    fun spreadLines(deltaNorm: Float) {
        val top = _lineTopNorm.value ?: return
        val bottom = _lineBottomNorm.value ?: return
        val mid = (top + bottom) / 2f
        val newGap = ((bottom - top) + deltaNorm).coerceIn(MIN_LINE_GAP, 1f)
        var newTop = mid - newGap / 2f
        var newBottom = mid + newGap / 2f
        if (newTop < 0f) {
            newBottom -= newTop
            newTop = 0f
        }
        if (newBottom > 1f) {
            newTop -= (newBottom - 1f)
            newBottom = 1f
        }
        _lineTopNorm.value = newTop.coerceIn(0f, 1f)
        _lineBottomNorm.value = newBottom.coerceIn(0f, 1f)
    }

    fun resetLines() {
        _lineTopNorm.value = null
        _lineBottomNorm.value = null
    }

    private fun currentLineGapOrDefault(): Float {
        val top = _lineTopNorm.value
        val bottom = _lineBottomNorm.value
        return if (top != null && bottom != null && bottom > top) {
            bottom - top
        } else {
            DEFAULT_FREE_LINE_GAP
        }
    }

    // Nessun detector: l'analizzatore fornisce solo il fotogramma. Il
    // riconoscimento gira al fermo immagine, dentro analyzeFrozenLines().
    private val analyzer = MiraglioAnalyzer(
        ocrRecognizer = ocrRecognizer
    )

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        context: Context
    ) {
        boundPreview = BoundPreview(lifecycleOwner, previewView)
        cameraBoundOnce = true
        cameraService.setAnalyzer(analyzer)
        cameraService.bindCamera(
            context, lifecycleOwner, previewView,
            onAnalyzerReady = {},
            cameraId = _lensId.value
        )

        viewModelScope.launch {
            analyzer.frameState.collect { state ->
                val frameW = if (state.frameWidth > 0) state.frameWidth else 960
                val frameH = if (state.frameHeight > 0) state.frameHeight else 1280
                val box = TargetScorer.bestTarget(
                    state.detectionBoxes,
                    frameW,
                    frameH,
                    preferredLabels = listOf(
                        TargetClass.PAGLIONE.visionLabel,
                        TargetClass.TARGA.visionLabel
                    )
                )
                // Il box e' normalizzato 0-1: convertire in pixel per lo stimatore AUTO
                _latestBoxWidthPx.value =
                    box?.takeIf { it.box.label == TargetClass.PAGLIONE.visionLabel }
                        ?.let { (it.box.right - it.box.left) * frameW } ?: 0f
                // Snap live: miglior box qualunque sia la classe, per l'overlay sul preview
                val liveBest = TargetScorer.bestTarget(
                    state.detectionBoxes,
                    frameW,
                    frameH,
                    preferredLabels = listOf(
                        TargetClass.PAGLIONE.visionLabel,
                        TargetClass.TARGA.visionLabel
                    )
                )
                _liveSnap.value = liveBest?.let { LiveSnap(it.box, frameW, frameH) }
                if (state.frameWidth > 0) {
                    _frameWidthPx.value = state.frameWidth
                    val longSide = maxOf(state.frameWidth, state.frameHeight)
                    _currentFrameLongSide.value = longSide
                    theoreticalIntrinsics = CameraIntrinsics.fromDiagonalFov(
                        67f, state.frameWidth, state.frameHeight
                    )
                }

            }
        }
    }

    fun startSensors() {
        inclinometer.start()
        compass.start()
        // GPS on-demand per i Luoghi: la prima volta che la camera va in live
        // si richiedono gli aggiornamenti (il tracker filtra i fix > 40 m).
        if (!_gpsRequested.value) {
            _gpsRequested.value = true
            locationTracker.startLocationUpdates()
        }
    }

    fun stopSensors() {
        inclinometer.stop()
        compass.stop()
    }

    /**
     * La bitmap del fermo immagine non va nel Bundle (JPEG troppo grande:
     * TransactionTooLargeException): va su file. KEY_FROZEN_FILE e' la
     * chiave-mestra: solo quando ha un valore il restore ripristina lo stato
     * del fermo immagine; le chiavi residue (linee/sensori di una sessione
     * passata) restano cosi' ignorate.
     */
    private fun persistAll(state: SavedState) {
        savedStateHandle[KEY_HEIGHT_INPUT] = state.heightInputCm
        savedStateHandle[KEY_WIDTH_INPUT] = state.widthInputCm
        savedStateHandle[KEY_DISTANCE_INPUT] = state.distanceInput
        savedStateHandle[KEY_MODE] = state.sensors.mode.ordinal
        savedStateHandle[KEY_DIRECTION] = state.sensors.direction.ordinal
        state.sensors.pitch?.let { savedStateHandle[KEY_FROZEN_PITCH] = it }
        state.sensors.azimuth?.let { savedStateHandle[KEY_FROZEN_AZIMUTH] = it }
        state.sensors.fix?.let { fix ->
            savedStateHandle[KEY_GPS_LAT] = fix.latitudeDeg
            savedStateHandle[KEY_GPS_LON] = fix.longitudeDeg
            savedStateHandle[KEY_GPS_ACC] = fix.accuracyM
            savedStateHandle[KEY_GPS_TIME] = fix.timeMs
            fix.bearingDeg?.let { savedStateHandle[KEY_GPS_BEARING] = it }
        }
        val lines = state.lines
        if (lines.top != null && lines.bottom != null && lines.frameHeightPx > 0) {
            savedStateHandle[KEY_LINE_TOP] = lines.top
            savedStateHandle[KEY_LINE_BOTTOM] = lines.bottom
            savedStateHandle[KEY_LINE_FRAME_H] = lines.frameHeightPx
            savedStateHandle[KEY_LINE_FRAME_W] = lines.frameWidthPx
            savedStateHandle[KEY_LINE_ORIENTATION] = lines.orientation.ordinal
        }
    }

    /** Ripristina i valori se il fermo immagine era attivo (chiave-mestra). */
    private fun restoreSavedState() {
        savedStateHandle.get<String>(KEY_HEIGHT_INPUT)?.let { _heightInputCm.value = it }
        savedStateHandle.get<String>(KEY_WIDTH_INPUT)?.let { _widthInputCm.value = it }
        savedStateHandle.get<String>(KEY_DISTANCE_INPUT)?.let { _measureDistanceInput.value = it }
        savedStateHandle.get<Int>(KEY_MODE)?.let { ord ->
            CaptureDistanceMode.entries.getOrNull(ord)?.let { _mode.value = it }
        }
        savedStateHandle.get<Int>(KEY_DIRECTION)?.let { ord ->
            MeasureDirection.entries.getOrNull(ord)?.let { _measureDirection.value = it }
        }
        if (savedStateHandle.get<String>(KEY_FROZEN_FILE) == null) return

        savedStateHandle.get<Int>(KEY_LINE_ORIENTATION)?.let { ord ->
            LineOrientation.entries.getOrNull(ord)?.let { _lineOrientation.value = it }
        }
        savedStateHandle.get<Float>(KEY_LINE_TOP)?.let { _lineTopNorm.value = it }
        savedStateHandle.get<Float>(KEY_LINE_BOTTOM)?.let { _lineBottomNorm.value = it }
        savedStateHandle.get<Int>(KEY_LINE_FRAME_H)?.let { _lineFrameHeightPx.value = it }
        savedStateHandle.get<Int>(KEY_LINE_FRAME_W)?.let { _lineFrameWidthPx.value = it }
        savedStateHandle.get<Float>(KEY_FROZEN_PITCH)?.let { _frozenPitchDegrees.value = it }
        savedStateHandle.get<Float>(KEY_FROZEN_AZIMUTH)?.let { _frozenAzimuthDegrees.value = it }
        val lat = savedStateHandle.get<Double>(KEY_GPS_LAT)
        val lon = savedStateHandle.get<Double>(KEY_GPS_LON)
        if (lat != null && lon != null) {
            _frozenGpsFix.value = GpsFix(
                latitudeDeg = lat,
                longitudeDeg = lon,
                accuracyM = savedStateHandle.get<Float>(KEY_GPS_ACC) ?: 0f,
                timeMs = savedStateHandle.get<Long>(KEY_GPS_TIME) ?: 0L,
                bearingDeg = savedStateHandle.get<Float>(KEY_GPS_BEARING)
            )
        }
        // Bitmap su file: decode asincrono. Se il file non e' piu' leggibile
        // il fermo immagine resta a null e la chiave-mestra si azzera; le
        // linee e lo scopo si sviluppano senza immagine.
        viewModelScope.launch(Dispatchers.IO) {
            val file = frozenImageFile()
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (bmp != null) {
                _frozenBitmap.value = bmp
                // Rilevamento solo per riarmare lo snap ai tap: qui NON si
                // sovrascrivono le linee (analyzeFrozenLines lo farebbe), e a
                // riconoscimento spento non si tocca affatto il modello.
                if (settings.recognitionEnabled.value) {
                    runCatching { detector.detect(bmp) }.getOrNull()?.let { res ->
                        _lineeBoxes.value = res.detections
                    }
                }
            } else {
                saveStateHandleFailsafeNull()
                _frozenPitchDegrees.value = null
                _frozenAzimuthDegrees.value = null
            }
        }
    }

    /** Scrive la bitmap del fermo immagine su file e attiva la chiave-mestra. */
    private fun persistFrozenImage(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val file = frozenImageFile()
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }.isSuccess
            if (ok) {
                savedStateHandle[KEY_FROZEN_FILE] = FROZEN_IMAGE_FILE
            }
        }
    }

    private fun frozenImageFile(): File =
        File(File(appContext.filesDir, "capture_state"), FROZEN_IMAGE_FILE)

    /**
     * Rilega la camera (es. al ritorno da un'altra schermata che usa CameraX):
     * ripristina analyzer e use-case sul PreviewView originale.
     */
    fun rebindCamera() {
        if (!cameraBoundOnce) {
            return
        }
        val bound = boundPreview ?: return
        cameraService.setAnalyzer(analyzer)
        cameraService.bindCamera(
            bound.previewView.context.applicationContext,
            bound.lifecycleOwner, bound.previewView,
            onAnalyzerReady = {},
            cameraId = _lensId.value
        )
    }

    /**
     * Cicla tra le lenti posteriori disponibili (bottone unico). Se l'id
     * corrente non e' in lista (bind ripiegato sulla default), si parte dalla
     * posizione della lente principale cosi' il primo tocco cambia davvero lente.
     */
    fun cycleLens() {
        val ids = cameraService.backCameraIds()
        if (ids.size < 2) return
        val current = _lensId.value ?: cameraService.defaultBackCameraId.value
        val currentIdx = ids.indexOf(current).takeIf { it >= 0 }
            ?: ids.indexOf(cameraService.defaultBackCameraId.value).takeIf { it >= 0 }
            ?: 0
        switchLens(ids[(currentIdx + 1) % ids.size])
    }

    private fun switchLens(lensId: String) {
        _lensId.value = lensId
        _zoomRatio.value = 1f
        cameraService.resetZoom()
        focalStore.setActiveLens(lensId)
        refreshLensLabel()
        val bound = boundPreview ?: return
        cameraService.setAnalyzer(analyzer)
        cameraService.bindCamera(
            bound.previewView.context.applicationContext,
            bound.lifecycleOwner, bound.previewView,
            onAnalyzerReady = {},
            cameraId = lensId
        )
    }

    private fun refreshLensLabel() {
        val ids = cameraService.backCameraIds()
        val current = _lensId.value ?: cameraService.defaultBackCameraId.value
        if (ids.size <= 1 || current == null) {
            _lensLabel.value = null
            return
        }
        val idx = ids.indexOf(current).let { if (it < 0) 0 else it }
        // Fattore d'ingrandimento rispetto alla lente principale (da LENS_FOCAL_LENGTH):
        // aiuta a riconoscere ultra (~0.6x) e tele (~2-3x) senza dover indovinare.
        val lenses = cameraService.backCameras()
        val focal = lenses.firstOrNull { it.id == current }?.focalLengthMm
        val focalMain = cameraService.defaultBackCameraId.value?.let { defId ->
            lenses.firstOrNull { it.id == defId }?.focalLengthMm
        }
        val factor = if (focal != null && focalMain != null && focalMain > 0f) focal / focalMain else 1f
        val factorLabel = when {
            factor < 0.95f -> " \u2248${"%.1f".format(factor)}\u00d7"
            factor > 1.05f -> " \u2248${"%.1f".format(factor)}\u00d7"
            else -> ""
        }
        _lensLabel.value = "Lente ${idx + 1}/${ids.size}$factorLabel"
    }

    /** Input manuale altezza nota in cm (accetta virgola o punto). */
    fun setHeightInputCm(text: String) {
        _heightInputCm.value = text
    }

    /** Input manuale larghezza nota in cm (linee verticali). */
    fun setWidthInputCm(text: String) {
        _widthInputCm.value = text
    }

    fun setMeasureDirection(direction: MeasureDirection) {
        _measureDirection.value = direction
    }

    /** Toggle immediato tra misurazione distanza e misurazione altezza. */
    fun toggleMeasureDirection() {
        _measureDirection.value = if (_measureDirection.value == MeasureDirection.DISTANZA) {
            MeasureDirection.ALTEZZA
        } else {
            MeasureDirection.DISTANZA
        }
    }

    /** Input manuale distanza nota in m per la modalità ALTEZZA. */
    fun setMeasureDistanceInput(text: String) {
        _measureDistanceInput.value = text
    }

    /** Pinch live: moltiplica lo zoom corrente per il fattore del gesto. */
    fun zoomByFactor(factor: Float) {
        if (factor <= 0f || !factor.isFinite()) {
            return
        }
        val (minZoom, maxZoom) = cameraService.zoomRange.value
        val next = (_zoomRatio.value * factor).coerceIn(minZoom, maxZoom)
        if (next != _zoomRatio.value) {
            _zoomRatio.value = next
            cameraService.setZoomRatio(next)
        }
    }

    fun resetZoom() {
        _zoomRatio.value = 1f
        cameraService.resetZoom()
    }

    private fun stateFor(
        tap: TapInfo,
        config: ConfigInfo,
        line: LineInfo,
        orientation: LineOrientation,
        autoWidthPx: Float,
        pitch: Float,
        bearingLive: Float,
        bearingFrozen: Float?
    ): MeasureUiState {
        val estimator = estimatorFor(config)
        // In modalita' LINEE l'angolo viene bloccato al momento dello scatto:
        // cosi' il valore mostrato resta stabile e leggibile.
        val pitch = if (config.mode == CaptureDistanceMode.LINEE) {
            _frozenPitchDegrees.value ?: pitch
        } else {
            pitch
        }
        // Stessa logica per la direzione di mira: congelata allo scatto.
        val bearing = if (config.mode == CaptureDistanceMode.LINEE) {
            bearingFrozen ?: bearingLive
        } else {
            bearingLive
        }
        val distance: Float? = when (config.mode) {
            CaptureDistanceMode.TAP -> {
                val left = tap.left
                val right = tap.right
                if (left != null && right != null && right > left && tap.frameWidth > 0) {
                    val pixelWidth = (right - left) * tap.frameWidth
                    estimator.estimateDistanceMeters(config.knownWidth, pixelWidth)
                } else {
                    null
                }
            }
            CaptureDistanceMode.AUTO -> {
                if (autoWidthPx > 0f) {
                    estimator.estimateDistanceMeters(config.knownWidth, autoWidthPx)
                } else {
                    null
                }
            }
            CaptureDistanceMode.LINEE -> {
                val top = line.top
                val bottom = line.bottom
                val horizontal = orientation == LineOrientation.HORIZONTAL
                val frameDim = if (horizontal) line.frameHeight else line.frameWidth
                val knownSize = if (horizontal) config.knownHeight else config.knownWidth
                if (top != null && bottom != null && bottom > top && frameDim > 0) {
                    val pixelExtent = (bottom - top) * frameDim
                    if (horizontal) {
                        estimator.estimateDistanceByHeightMeters(knownSize, pixelExtent)
                    } else {
                        estimator.estimateDistanceMeters(knownSize, pixelExtent)
                    }
                } else {
                    null
                }
            }
            CaptureDistanceMode.MANUAL -> null
        }
        // Modalita' ALTEZZA: distanza nota -> dimensione reale tra le linee.
        val measuredSize: Float? = if (config.mode == CaptureDistanceMode.LINEE &&
            config.direction == MeasureDirection.ALTEZZA
        ) {
            val top = line.top
            val bottom = line.bottom
            val horizontal = orientation == LineOrientation.HORIZONTAL
            val frameDim = if (horizontal) line.frameHeight else line.frameWidth
            if (top != null && bottom != null && bottom > top && frameDim > 0 &&
                config.measureDistanceM > 0f
            ) {
                measuredSizeFromLines((bottom - top) * frameDim, horizontal, config)
            } else {
                null
            }
        } else {
            null
        }

        return MeasureUiState(
            result = distance?.let { MeasureResult(it, pitch) },
            measuredSizeM = measuredSize,
            bearingDegrees = bearing.takeIf { config.mode != CaptureDistanceMode.MANUAL },
            tapLeftNorm = tap.left,
            tapRightNorm = tap.right,
            frameWidthPx = tap.frameWidth,
            lineTopNorm = line.top,
            lineBottomNorm = line.bottom,
            lineFrameHeightPx = line.frameHeight,
            pitchAngleDegrees = pitch
        )
    }

    /**
     * Dimensione reale tra le linee data la distanza nota (misurazione inversa).
     * Deve coincidere con l'inversa della stima di distanza:
     * dimensione = px * distanza / focale_effettiva, con focale 1x * zoom.
     */
    private fun measuredSizeFromLines(
        pixelExtent: Float,
        horizontal: Boolean,
        config: ConfigInfo
    ): Float? {
        val zoom = config.zoomRatio.coerceAtLeast(MIN_ZOOM_RATIO)
        val focal1x = (if (horizontal) config.fyPx else config.fxPx)
            ?.takeIf { it > 0f }
            ?: if (horizontal) theoreticalIntrinsics.fyPixels else theoreticalIntrinsics.fxPixels
        if (focal1x <= 0f) return null
        return pixelExtent * config.measureDistanceM / (focal1x * zoom)
    }

    private fun estimatorFor(config: ConfigInfo): PinholeDistanceEstimator {
        // Lo zoom digitale moltiplica la focale effettiva (focale x z).
        // Ogni asse usa la focale calibrata se disponibile, altrimenti quella
        // teorica: calibrando solo l'altezza (fy) non si deve perdere la
        // calibrazione per la misura in altezza.
        val zoom = config.zoomRatio.coerceAtLeast(MIN_ZOOM_RATIO)
        val fx = config.fxPx?.takeIf { it > 0f } ?: theoreticalIntrinsics.fxPixels
        val fy = config.fyPx?.takeIf { it > 0f } ?: theoreticalIntrinsics.fyPixels
        return PinholeDistanceEstimator(
            CameraIntrinsics(
                fxPixels = fx * zoom,
                fyPixels = fy * zoom,
                cxPixels = theoreticalIntrinsics.cxPixels,
                cyPixels = theoreticalIntrinsics.cyPixels
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        inclinometer.stop()
        detector.close()
        boundPreview = null
    }

    companion object {
        private const val SNAP_THRESHOLD_NORM = 0.18f
        private const val MIN_LINE_GAP = 0.02f
        private const val DEFAULT_FREE_LINE_GAP = 0.35f

        /** Nome file del fermo immagine persistito tra processi. */
        private const val FROZEN_IMAGE_FILE = "frozen.jpg"
        private const val KEY_FROZEN_FILE = "capture.frozenFile"
        private const val KEY_HEIGHT_INPUT = "capture.heightInputCm"
        private const val KEY_WIDTH_INPUT = "capture.widthInputCm"
        private const val KEY_DISTANCE_INPUT = "capture.distanceInput"
        private const val KEY_MODE = "capture.modeOrdinal"
        private const val KEY_DIRECTION = "capture.directionOrdinal"
        private const val KEY_LINE_ORIENTATION = "capture.lineOrientationOrdinal"
        private const val KEY_LINE_TOP = "capture.lineTopNorm"
        private const val KEY_LINE_BOTTOM = "capture.lineBottomNorm"
        private const val KEY_LINE_FRAME_H = "capture.lineFrameHeightPx"
        private const val KEY_LINE_FRAME_W = "capture.lineFrameWidthPx"
        private const val KEY_FROZEN_PITCH = "capture.frozenPitchDeg"
        private const val KEY_FROZEN_AZIMUTH = "capture.frozenAzimuthDeg"
        private const val KEY_GPS_LAT = "capture.gpsLat"
        private const val KEY_GPS_LON = "capture.gpsLon"
        private const val KEY_GPS_ACC = "capture.gpsAccuracyM"
        private const val KEY_GPS_TIME = "capture.gpsTimeMs"
        private const val KEY_GPS_BEARING = "capture.gpsBearingDeg"
        /** Come in FocalCaptureViewModel.currentImpliedFocal: il pinch non scende sotto questo valore. */
        private const val MIN_ZOOM_RATIO = 0.01f
    }
}
