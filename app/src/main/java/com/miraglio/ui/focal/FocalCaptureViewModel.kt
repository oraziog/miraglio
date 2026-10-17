package com.miraglio.ui.focal

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.R
import com.miraglio.data.camera.CameraService
import com.miraglio.data.camera.LatestBitmapAnalyzer
import com.miraglio.data.feedback.SaveFeedback
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.domain.focal.FocalVerification
import com.miraglio.domain.location.CalibrationDistance
import com.miraglio.domain.location.GpsFix
import com.miraglio.i18n.Texts
import com.miraglio.ui.capture.LineOrientation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FocalCaptureUiState(
    val frozen: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val orientation: LineOrientation = LineOrientation.HORIZONTAL,
    val lineA: Float? = null,
    val lineB: Float? = null,
    // Il paglione FITA e' 130 cm; il bersaglio applicato sul paglione e' 122 cm.
    val knownSizeM: Float = DEFAULT_KNOWN_SIZE_M,
    // Il testo del campo lo compila il ViewModel, nell'unita' in vigore (130 in
    // metrico, 51 in imperiale): qui vuoto perche' un default fisso verrebbe
    // letto come pollici da chi ha scelto le iarde.
    val sizeInput: String = "",
    val distanceInput: String = "",
    val statusMessage: String? = null,
    val savedCount: Int = 0
) {

    companion object {
        /** Altezza del paglione FITA, in metri: e' il valore di partenza del campo. */
        const val DEFAULT_KNOWN_SIZE_M = 1.30f
    }
    val gapNorm: Float? =
        if (lineA != null && lineB != null && lineB > lineA) lineB - lineA else null

    /** Pixel misurati lungo l'asse di misura (H -> altezza frame, V -> larghezza). */
    fun measuredPixels(): Float? {
        val gap = gapNorm ?: return null
        val dim = if (orientation == LineOrientation.HORIZONTAL) frameHeight else frameWidth
        if (dim <= 0) return null
        return gap * dim
    }

    fun impliedFocalPx(distanceM: Float?): Float? {
        val px = measuredPixels() ?: return null
        val d = distanceM ?: return null
        if (d <= 0f || knownSizeM <= 0f) return null
        return d * px / knownSizeM
    }
}

@HiltViewModel
class FocalCaptureViewModel @Inject constructor(
    private val cameraService: CameraService,
    private val focalStore: FocalCalibrationStore,
    private val texts: Texts,
    private val settings: AppSettingsStore,
    private val saveFeedback: SaveFeedback,
    private val locationTracker: LocationTracker
) : ViewModel() {

    private val analyzer = LatestBitmapAnalyzer()

    private val _uiState = MutableStateFlow(
        FocalCaptureUiState(
            sizeInput = sizeText(FocalCaptureUiState.DEFAULT_KNOWN_SIZE_M)
        )
    )

    init {
        // Cambio di sistema di unita' con la schermata aperta: i campi gia'
        // compilati vanno riscritti nell'unita' nuova, altrimenti lo stesso
        // numero letto in pollici invece che in centimetri sarebbe un oggetto
        // 2,54 volte piu' grande, e la focale calcolata con lui sbaglierebbe
        // della stessa misura.
        viewModelScope.launch {
            var previous = settings.unitSystem.value
            settings.unitSystem.collect { unit ->
                if (unit != previous) {
                    val s = _uiState.value
                    _uiState.value = s.copy(
                        sizeInput = texts.format.rewriteSizeInput(s.sizeInput, previous),
                        distanceInput =
                            texts.format.rewriteDistanceInput(s.distanceInput, previous)
                    )
                    previous = unit
                }
            }
        }
    }
    val uiState: StateFlow<FocalCaptureUiState> = _uiState.asStateFlow()

    private val _frozenBitmap = MutableStateFlow<Bitmap?>(null)
    val frozenBitmap: StateFlow<Bitmap?> = _frozenBitmap.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _lensId = MutableStateFlow<String?>(null)
    private val _lensLabel = MutableStateFlow<String?>(null)
    /** Etichetta tipo "Lente 2/3": null se il dispositivo ha una sola lente. */
    val lensLabel: StateFlow<String?> = _lensLabel.asStateFlow()

    /** Preview e lifecycle trattenuti solo per i re-bind della camera. */
    private class BoundPreview(
        val lifecycleOwner: LifecycleOwner,
        val previewView: PreviewView
    )

    private var boundPreview: BoundPreview? = null

    init {
        viewModelScope.launch {
            cameraService.defaultBackCameraId.filterNotNull().first().let { defId ->
                focalStore.setDefaultLensId(defId, cameraService.defaultBackCameraFocalMm.value)
                _zoomRatio.value = 1f
                refreshLensLabel()
            }
        }
        // La lente legata guida selezione e calibrazione: se il bind ripiega
        // sulla default (lente non usabile su questo dispositivo), ci si riallinea.
        viewModelScope.launch {
            cameraService.boundLensId.filterNotNull().collect { bound ->
                if (_lensId.value != bound) {
                    _lensId.value = bound
                    focalStore.setActiveLens(bound)
                }
                refreshLensLabel()
            }
        }
    }

    /** Pinch sul live: lo zoom moltiplica la focale effettiva (compensato al salvataggio). */
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

    val savedPoints = focalStore.pointsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Focale che ci si aspetta dalla calibrazione importata per la lente in
     * uso, o null se non c'e' niente da verificare. La schermata la mostra
     * sopra i campi: e' il numero contro cui la prima misura di prova verra'
     * confrontata.
     */
    val verificationExpected: StateFlow<Pair<Float?, Float?>?> = combine(
        focalStore.verificationExpected,
        focalStore.activeLens
    ) { attese, lente ->
        lente?.let { attese[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        context: Context
    ) {
        boundPreview = BoundPreview(lifecycleOwner, previewView)
        cameraService.setAnalyzer(analyzer)
        cameraService.bindCamera(
            context, lifecycleOwner, previewView,
            onAnalyzerReady = {},
            cameraId = _lensId.value
        )
    }

    /**
     * Cicla tra le lenti posteriori disponibili (bottone unico, solo prima del
     * fermo). Come in CaptureViewModel: parte dalla lente principale se l'id
     * corrente non e' in lista, cosi' il primo tocco cambia davvero lente.
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
        val lenses = cameraService.backCameras()
        val focal = lenses.firstOrNull { it.id == current }?.focalLengthMm
        val focalMain = cameraService.defaultBackCameraId.value?.let { defId ->
            lenses.firstOrNull { it.id == defId }?.focalLengthMm
        }
        val factor = if (focal != null && focalMain != null && focalMain > 0f) focal / focalMain else 1f
        val factorLabel = when {
            factor < 0.95f || factor > 1.05f -> " \u2248${texts.format.number(factor, 1)}\u00d7"
            else -> ""
        }
        _lensLabel.value = texts.get(R.string.lens_label, idx + 1, ids.size) + factorLabel
    }

    fun freezeFrame() {
        val bmp: Bitmap = analyzer.getLatestBitmap() ?: return
        _frozenBitmap.value = bmp
        _uiState.value = _uiState.value.copy(
            frozen = true,
            frameWidth = bmp.width,
            frameHeight = bmp.height,
            // Due linee parallele al centro del fotogramma catturato.
            lineA = 0.5f - DEFAULT_GAP / 2f,
            lineB = 0.5f + DEFAULT_GAP / 2f,
            statusMessage = null
        )
    }

    fun unfreeze() {
        _frozenBitmap.value = null
        resetZoom()
        _uiState.value = _uiState.value.copy(
            frozen = false,
            lineA = null,
            lineB = null,
            statusMessage = null
        )
    }

    fun toggleOrientation() {
        val s = _uiState.value
        val next = if (s.orientation == LineOrientation.HORIZONTAL) {
            LineOrientation.VERTICAL
        } else {
            LineOrientation.HORIZONTAL
        }
        _uiState.value = s.copy(
            orientation = next,
            lineA = 0.5f - DEFAULT_GAP / 2f,
            lineB = 0.5f + DEFAULT_GAP / 2f,
            statusMessage = null
        )
    }

    /**
     * Input manuale della dimensione nota, nell'unita' che il campo chiede
     * (`format.sizeUnit`: centimetri o pollici). Accetta virgola o punto.
     */
    fun setSizeInput(text: String) {
        val s = _uiState.value.copy(sizeInput = text, statusMessage = null)
        val meters = texts.format.parseSize(text)
        _uiState.value = if (meters != null) s.copy(knownSizeM = meters) else s
    }

    fun setDistanceInput(text: String) {
        _uiState.value = _uiState.value.copy(distanceInput = text, statusMessage = null)
    }

    // ------------------------------------------ distanza GPS di calibrazione

    /** Azione in attesa di un fix buono: il telefono si muove, chi aspetta no. */
    private enum class PendingGps { MARK, USE }

    private var pendingGps: PendingGps? = null

    init {
        // Chi preme un pulsante GPS con il fix non ancora pronto (o vecchio) non
        // deve premere due volte: l'azione ripente da sola appena arriva una
        // posizione fresca, e intanto il messaggio dice cosa si aspetta.
        viewModelScope.launch {
            locationTracker.gnssLocation.collect { location ->
                val action = pendingGps ?: return@collect
                if (location == null) return@collect
                val fix = GpsFix.fromLocation(location)
                if (isStale(fix)) return@collect
                pendingGps = null
                if (action == PendingGps.MARK) {
                    rememberMark(fix)
                } else {
                    fillGpsDistance(fix)
                }
            }
        }
    }

    /**
     * Segna dove si trova il bersaglio: si preme stando DAVANTI all'oggetto di
     * dimensione nota, poi ci si torna a mirare. Il pin resta salvato anche fra
     * due sessioni — il bersaglio, di solito, resta dove l'hanno messo.
     */
    fun markTargetHere() {
        if (!locationTracker.hasFinePermission()) {
            showStatus(R.string.focal_gps_no_permission)
            return
        }
        locationTracker.startLocationUpdates()
        val location = locationTracker.gnssLocation.value
        if (location == null || isStale(GpsFix.fromLocation(location))) {
            pendingGps = PendingGps.MARK
            showStatus(R.string.focal_gps_wait_fix)
            return
        }
        rememberMark(GpsFix.fromLocation(location))
    }

    /**
     * Compila il campo distanza con la distanza fra il fix di adesso e il
     * bersaglio segnato: niente numero da digitare con le mani fredde, e il
     * valore che entra nel campo e' lo stesso che la riga sotto rilegge.
     */
    fun useGpsDistance() {
        if (!locationTracker.hasFinePermission()) {
            showStatus(R.string.focal_gps_no_permission)
            return
        }
        if (focalStore.targetFix.value == null) {
            showStatus(R.string.focal_gps_no_mark)
            return
        }
        locationTracker.startLocationUpdates()
        val location = locationTracker.gnssLocation.value
        if (location == null || isStale(GpsFix.fromLocation(location))) {
            pendingGps = PendingGps.USE
            showStatus(R.string.focal_gps_wait_fix)
            return
        }
        fillGpsDistance(GpsFix.fromLocation(location))
    }

    /** Il bersaglio segnato e' adesso questo punto, con la sua accuratezza. */
    private fun rememberMark(fix: GpsFix) {
        focalStore.setTargetFix(fix)
        showStatus(R.string.focal_gps_marked, texts.format.accuracy(fix.accuracyM))
    }

    private fun fillGpsDistance(fix: GpsFix) {
        val mark = focalStore.targetFix.value ?: return
        val meters = CalibrationDistance.between(fix, mark)
        if (meters == null) {
            // Con entrambi i punti presenti l'unico risultato nullo e' zero:
            // il bersaglio segnato coincide con dove si sta.
            showStatus(R.string.focal_gps_same_point)
            return
        }
        val accuracy = CalibrationDistance.accuracyMeters(fix, mark) ?: 0f
        _uiState.value = _uiState.value.copy(
            distanceInput = texts.format.number(texts.format.distanceValue(meters), 1),
            statusMessage = texts.get(
                R.string.focal_gps_distance,
                texts.format.distance(meters),
                texts.format.accuracy(accuracy)
            )
        )
    }

    /**
     * Un fix si usa solo se e' di adesso: il tracker conserva l'ultimo
     * ricevuto, e quello di dieci minuti fa (mentre eri da un'altra parte)
     * darebbe una distanza inventata senza che si veda.
     */
    private fun isStale(fix: GpsFix): Boolean =
        fix.timeMs > 0L && System.currentTimeMillis() - fix.timeMs > FRESH_FIX_MAX_AGE_MS

    private fun showStatus(id: Int, vararg args: Any) {
        _uiState.value = _uiState.value.copy(statusMessage = texts.get(id, *args))
    }

    /** Tap lungo l'asse di misura: centra il gap corrente sul punto. */
    fun registerAxisTap(norm: Float) {
        val s = _uiState.value
        val y = norm.coerceIn(0f, 1f)
        val gap = s.gapNorm ?: DEFAULT_GAP
        var a = y - gap / 2f
        var b = y + gap / 2f
        if (a < 0f) {
            b -= a
            a = 0f
        }
        if (b > 1f) {
            a -= (b - 1f)
            b = 1f
        }
        _uiState.value = s.copy(
            lineA = a.coerceIn(0f, 1f),
            lineB = b.coerceIn(0f, 1f)
        )
    }

    /** Sposta entrambe le linee preservando l'apertura (striscia SX). */
    fun panLines(dyNorm: Float) {
        val s = _uiState.value
        val a = s.lineA ?: return
        val b = s.lineB ?: return
        val gap = (b - a).coerceAtLeast(MIN_GAP)
        val newA = (a + dyNorm).coerceIn(0f, 1f - gap)
        _uiState.value = s.copy(lineA = newA, lineB = newA + gap)
    }

    /** Sposta solo la prima linea (superiore/sinistra), rispettando il gap minimo. */
    fun moveFirstLine(deltaNorm: Float) {
        val s = _uiState.value
        val a = s.lineA ?: return
        val b = s.lineB ?: return
        _uiState.value = s.copy(lineA = (a + deltaNorm).coerceIn(0f, (b - MIN_GAP).coerceAtLeast(0f)))
    }

    /** Sposta solo la seconda linea (inferiore/destra), rispettando il gap minimo. */
    fun moveSecondLine(deltaNorm: Float) {
        val s = _uiState.value
        val a = s.lineA ?: return
        val b = s.lineB ?: return
        _uiState.value = s.copy(lineB = (b + deltaNorm).coerceIn((a + MIN_GAP).coerceAtMost(1f), 1f))
    }

    /** Allarga (delta > 0) o stringe in modo simmetrico (striscia DX). */
    fun spreadLines(deltaNorm: Float) {
        val s = _uiState.value
        val a = s.lineA ?: return
        val b = s.lineB ?: return
        val mid = (a + b) / 2f
        val newGap = ((b - a) + deltaNorm).coerceIn(MIN_GAP, 1f)
        var newA = mid - newGap / 2f
        var newB = mid + newGap / 2f
        if (newA < 0f) {
            newB -= newA
            newA = 0f
        }
        if (newB > 1f) {
            newA -= (newB - 1f)
            newB = 1f
        }
        _uiState.value = s.copy(
            lineA = newA.coerceIn(0f, 1f),
            lineB = newB.coerceIn(0f, 1f)
        )
    }

    /** Distanza digitata, in metri (il campo la chiede in metri o in iarde). */
    fun parsedDistance(): Float? = texts.format.parseDistance(_uiState.value.distanceInput)

    /** Precompilazione del campo dimensione con una misura in metri. */
    private fun sizeText(meters: Float): String =
        texts.format.number(texts.format.sizeValue(meters), 0)

    /**
     * Compila la schermata dal grafico della calibrazione: la misura da rifare
     * arriva con la distanza, la dimensione del bersaglio e l'asse che aveva.
     *
     * Senza questa precompilazione "rifai la misura" voleva dire ricopiare a
     * mano tre numeri al sole, ed e' il momento in cui si sbaglia.
     */
    fun prefillDistance(distanceM: Float?, sizeM: Float?, axis: String?) {
        val s = _uiState.value
        var next = s
        if (sizeM != null && sizeM > 0f) {
            next = next.copy(knownSizeM = sizeM, sizeInput = sizeText(sizeM))
        }
        if (distanceM != null && distanceM > 0f) {
            next = next.copy(
                distanceInput = texts.format.number(texts.format.distanceValue(distanceM), 1)
            )
        }
        if (axis == "H" || axis == "V") {
            next = next.copy(
                orientation = if (axis == "H") LineOrientation.HORIZONTAL
                else LineOrientation.VERTICAL
            )
        }
        _uiState.value = next
    }

    /** Focale implicita compensata per lo zoom corrente. */
    fun currentImpliedFocal(): Float? {
        val s = _uiState.value
        val px = s.measuredPixels() ?: return null
        val d = parsedDistance() ?: return null
        val zoom = _zoomRatio.value.coerceAtLeast(0.01f)
        if (s.knownSizeM <= 0f) return null
        return d * px / (s.knownSizeM * zoom)
    }

    /** Salva il punto (metri + pixel) nello store. Ritorna false se input non valido. */
    fun savePoint(): Boolean {
        val s = _uiState.value
        val distance = parsedDistance()
        val pixels = s.measuredPixels()
        if (distance == null || pixels == null || pixels <= 0f || s.knownSizeM <= 0f) {
            _uiState.value = s.copy(statusMessage = texts.get(R.string.focal_invalid_distance))
            return false
        }
        val axis = if (s.orientation == LineOrientation.HORIZONTAL) "H" else "V"
        val point = focalStore.addPoint(distance, pixels, s.knownSizeM, axis, _zoomRatio.value)
        // Se questa lente ha una calibrazione appena importata, il punto e' la
        // misura di prova: la sua focale si confronta con quella arrivata dal
        // file, e uno scarto grande si dice subito (il punto resta, cosi' lo si
        // puo' cancellare o rifare senza perdere il resto).
        val lens = focalStore.activeLens.value ?: focalStore.defaultLensId.value
        val expectedForAxis = focalStore.verificationFor(lens)?.let { (fx, fy) ->
            if (axis == "H") fy else fx
        }
        val relativeError = expectedForAxis?.let {
            FocalVerification.relativeError(it, point.impliedFocalPx)
        }
        val status = when {
            expectedForAxis == null || relativeError == null -> texts.get(
                R.string.focal_point_saved,
                texts.format.distance(distance),
                pixels.toInt(),
                point.impliedFocalPx.toInt()
            )
            relativeError > FocalVerification.TOLERANCE -> texts.get(
                R.string.focal_verify_mismatch,
                point.impliedFocalPx.toInt(),
                expectedForAxis.toInt(),
                texts.format.number(relativeError * 100f, 0)
            )
            else -> {
                focalStore.clearVerification(lens)
                texts.get(
                    R.string.focal_verify_ok,
                    point.impliedFocalPx.toInt(),
                    expectedForAxis.toInt()
                )
            }
        }
        _uiState.value = s.copy(
            distanceInput = "",
            savedCount = s.savedCount + 1,
            statusMessage = status
        )
        // Conferma con le mani: in calibrazione si guarda il bersaglio, non il
        // pannello, e il messaggio "punto salvato" si legge dopo.
        saveFeedback.saved()
        return true
    }

    override fun onCleared() {
        super.onCleared()
        boundPreview = null
    }

    companion object {
        private const val MIN_GAP = 0.02f
        private const val DEFAULT_GAP = 0.25f

        /** Un fix piu' vecchio di questo non riempie la distanza: e' di un altro posto. */
        private const val FRESH_FIX_MAX_AGE_MS = 60_000L
    }
}
