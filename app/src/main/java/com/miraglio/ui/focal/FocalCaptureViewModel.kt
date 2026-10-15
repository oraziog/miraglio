package com.miraglio.ui.focal

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.camera.CameraService
import com.miraglio.data.camera.LatestBitmapAnalyzer
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.domain.format.ItalianFormat
import com.miraglio.ui.capture.LineOrientation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FocalCaptureUiState(
    val frozen: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val orientation: LineOrientation = LineOrientation.HORIZONTAL,
    val lineA: Float? = null,
    val lineB: Float? = null,
    // Il paglione FITA e' 130 cm; il bersaglio applicato sul paglione e' 122 cm.
    val knownSizeM: Float = 1.30f,
    val sizeInputCm: String = "130",
    val distanceInput: String = "",
    val statusMessage: String? = null,
    val savedCount: Int = 0
) {
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
    private val focalStore: FocalCalibrationStore
) : ViewModel() {

    private val analyzer = LatestBitmapAnalyzer()

    private val _uiState = MutableStateFlow(FocalCaptureUiState())
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
            factor < 0.95f || factor > 1.05f -> " \u2248${ItalianFormat.number(factor, 1)}\u00d7"
            else -> ""
        }
        _lensLabel.value = "Lente ${idx + 1}/${ids.size}$factorLabel"
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

    /** Input manuale dimensione nota in cm (accetta virgola o punto). */
    fun setSizeInputCm(text: String) {
        val s = _uiState.value.copy(sizeInputCm = text, statusMessage = null)
        val cm = text.replace(',', '.').toFloatOrNull()
        _uiState.value = if (cm != null && cm > 0f) {
            s.copy(knownSizeM = cm / 100f)
        } else {
            s
        }
    }

    fun setDistanceInput(text: String) {
        _uiState.value = _uiState.value.copy(distanceInput = text, statusMessage = null)
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

    fun parsedDistance(): Float? =
        _uiState.value.distanceInput.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
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
            _uiState.value = s.copy(statusMessage = "Inserisci una distanza valida e posiziona le linee")
            return false
        }
        val axis = if (s.orientation == LineOrientation.HORIZONTAL) "H" else "V"
        val point = focalStore.addPoint(distance, pixels, s.knownSizeM, axis, _zoomRatio.value)
        _uiState.value = s.copy(
            distanceInput = "",
            savedCount = s.savedCount + 1,
            statusMessage = "Punto salvato: ${ItalianFormat.meters(distance)} · " +
                "${pixels.toInt()} px · f\u2248${point.impliedFocalPx.toInt()} px"
        )
        return true
    }

    override fun onCleared() {
        super.onCleared()
        boundPreview = null
    }

    companion object {
        private const val MIN_GAP = 0.02f
        private const val DEFAULT_GAP = 0.25f
    }
}
