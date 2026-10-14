package com.miraglio.data.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.Analyzer
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Una lente posteriore rilevata.
 * Se [parentId] non è null è una fotocamera *fisica* (es. ultra-grandangolo o
 * macro) contenuta nella logica [parentId]: si seleziona legando la logica e
 * impostando l'id fisico via Camera2 (API 28+).
 */
data class LensInfo(
    val id: String,
    val parentId: String?,
    /** Focale nominale in mm (se esposta dall'HAL); null se sconosciuta. */
    val focalLengthMm: Float? = null
) {
    val isPhysical: Boolean get() = parentId != null
}

@Singleton
@OptIn(ExperimentalCamera2Interop::class)
class CameraService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: Analyzer? = null
    private var camera: Camera? = null
    private var lensCache: List<LensInfo>? = null

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _zoomRange = MutableStateFlow(Pair(1f, 8f))
    /** Pair(minZoom, maxZoom) della camera legata. */
    val zoomRange: StateFlow<Pair<Float, Float>> = _zoomRange.asStateFlow()

    private val _defaultBackCameraId = MutableStateFlow<String?>(null)
    /** Id della fotocamera posteriore principale (1x), noto dopo il primo bind. */
    val defaultBackCameraId: StateFlow<String?> = _defaultBackCameraId.asStateFlow()

    private val _defaultBackCameraFocalMm = MutableStateFlow<Float?>(null)
    /** Focale nominale (mm) della lente principale; serve per scalare le focali teoriche. */
    val defaultBackCameraFocalMm: StateFlow<Float?> = _defaultBackCameraFocalMm.asStateFlow()

    private val _boundLensId = MutableStateFlow<String?>(null)
    /** Lente effettivamente legata (quella dei frame). Può differire da quella
     *  richiesta se il bind è ripiegato sulla camera predefinita. */
    val boundLensId: StateFlow<String?> = _boundLensId.asStateFlow()

    /**
     * Lenti posteriori rilevabili: logiche dichiarate in cameraIdList + le
     * fisiche (ultra, macro, tele) esposte dalle logiche. Una fisica che ha
     * anche un proprio id in cameraIdList resta una lente diretta (bind
     * tramite CameraSelector); una fisica nascosta viene legata tramite la
     * logica che la contiene (parent) + setPhysicalCameraId.
     */
    fun backCameras(): List<LensInfo> {
        lensCache?.let { return it }
        val result = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            fun facingOf(id: String): Int? = runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()

            // LENS_INFO_AVAILABLE_FOCAL_LENGTHS (mm): il primo elemento e' la
            // focale nominale. Per gli id fisici non dichiarati la lettura puo'
            // fallire: resta null.
            fun focalOf(id: String): Float? = runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
            }.getOrNull()

            fun physicalsOf(id: String): Set<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }
                        .getOrNull() ?: emptySet()
                } else {
                    emptySet()
                }

            val cameraIds = manager.cameraIdList
            // Fotocamere della lista dichiarate posteriori (logiche e fisiche esposte).
            val allBackIds = cameraIds.filter { facingOf(it) == CameraCharacteristics.LENS_FACING_BACK }
            // Tutte le fisiche esposte dalle posteriori.
            val backPhysical = allBackIds.flatMap { physicalsOf(it) }.toSet()

            val map = LinkedHashMap<String, LensInfo>()
            // 1) Tutte le posteriori dichiarate: bind diretto.
            allBackIds.forEach { map[it] = LensInfo(it, null, focalOf(it)) }
            // 2) Id della lista che sono fisiche di una posteriore anche se la
            //    facing è riportata male da alcuni HAL: espongo come lente diretta.
            cameraIds.forEach { id ->
                if (!map.containsKey(id) && backPhysical.contains(id)) {
                    map[id] = LensInfo(id, null, focalOf(id))
                }
            }
            // 3) Fisiche delle posteriori NON esposte in cameraIdList
            //    (macro/ultra nascosti): con parent per il bind.
            allBackIds.forEach { logical ->
                physicalsOf(logical).forEach { physical ->
                    if (physical != logical && !map.containsKey(physical)) {
                        map[physical] = LensInfo(physical, logical, focalOf(physical))
                    }
                }
            }
            map.values.toList()
        }.getOrDefault(emptyList())
        lensCache = result
        Log.i(TAG, "cameraIdList=" + runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.joinToString { id ->
                val facing = runCatching {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                }.getOrNull()
                "$id(f=$facing)"
            }
        }.getOrDefault("?"))
        Log.i(TAG, "Lenti posteriori: " + result.joinToString { l ->
            if (l.isPhysical) "${l.id} (fisica di ${l.parentId})" else l.id
        })
        return result
    }

    fun backCameraIds(): List<String> = backCameras().map { it.id }

    fun bindCamera(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: PreviewView,
        onAnalyzerReady: (ImageAnalysis) -> Unit,
        cameraId: String? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            // Lente principale (1x): la back camera predefinita di CameraX.
            val defaultId = CameraSelector.DEFAULT_BACK_CAMERA
                .filter(provider.availableCameraInfos)
                .firstOrNull()
                ?.let { Camera2CameraInfo.from(it).cameraId }
            if (_defaultBackCameraId.value == null && defaultId != null) {
                _defaultBackCameraId.value = defaultId
                _defaultBackCameraFocalMm.value =
                    backCameras().firstOrNull { it.id == defaultId }?.focalLengthMm
            }

            // Risolvi la lente: una fisica va legata tramite la sua logica
            // (parent) + setPhysicalCameraId.
            val lens = cameraId?.let { id -> backCameras().firstOrNull { it.id == id } }
            val physicalId = if (lens?.isPhysical == true) lens.id else null
            val logicalId = lens?.parentId ?: cameraId

            val previewBuilder = Preview.Builder()
            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        // 1280x960 (4:3, come il vecchio 640x480): stessi FOV su
                        // entrambi gli assi, foto di condivisione piu' dettagliata.
                        // FocalCalibrationStore.migrateIfNeeded riscala le calibrazioni
                        // salvate a 640x480 in base alla risoluzione effettiva.
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )

            // Lente fisica (API 28+): applicata a preview E analisi, altrimenti
            // fermo immagine e live verrebbero da sensori diversi.
            if (physicalId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalId)
                    Camera2Interop.Extender(imageAnalysisBuilder).setPhysicalCameraId(physicalId)
                }.onFailure {
                    Log.w(TAG, "setPhysicalCameraId non applicabile a $physicalId", it)
                }
            }

            // Anti-blur: stabilizzazione video + gamma fps con esposizione corta.
            // Valutate sulla lente effettiva (fisica se disponibile, altrimenti logica).
            val characteristicsLensId = physicalId ?: logicalId
            runCatching {
                val ext = Camera2Interop.Extender(previewBuilder)
                if (videoStabilizationSupported(characteristicsLensId)) {
                    ext.setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
                fastExposureRange(characteristicsLensId)?.let {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }

            val preview = previewBuilder
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = imageAnalysisBuilder.build()

            analyzer?.let { imageAnalysis.setAnalyzer(analysisExecutor, it) }
            onAnalyzerReady(imageAnalysis)

            // Selezione: se la logica richiesta non è selezionabile su questo
            // dispositivo, si ripiega sulla fotocamera posteriore predefinita.
            val requested = logicalId?.let { selectorFor(it) } ?: CameraSelector.DEFAULT_BACK_CAMERA
            val cameraSelector = if (logicalId != null && !provider.hasCamera(requested)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                requested
            }
            val usingDefault = logicalId != null && cameraSelector !== requested

            try {
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                camera = boundCamera
                val zoomState = boundCamera.cameraInfo.zoomState.value
                if (zoomState != null) {
                    _zoomRange.value = Pair(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                }
                _boundLensId.value = when {
                    usingDefault -> defaultId
                    cameraId != null -> cameraId
                    else -> defaultId
                }
                _isCameraReady.value = true
            } catch (e: Exception) {
                Log.w(TAG, "Bind fallito (lente=$cameraId, fisica=$physicalId)", e)
                _isCameraReady.value = false
                // Se la lente richiesta non si apre (es. sensore non usabile),
                // riprova un'ultima volta con la default (cameraId = null).
                if (cameraId != null) {
                    bindCamera(
                        context, lifecycleOwner, previewView,
                        onAnalyzerReady = onAnalyzerReady,
                        cameraId = null
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** CameraSelector per una specifica lente (fotocamera posteriore). */
    private fun selectorFor(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }
                        .getOrDefault(false)
                }
            }
            .build()

    fun setAnalyzer(analyzer: Analyzer) {
        this.analyzer = analyzer
    }

    /**
     * Zoom digitale (vale per preview e analisi: lo zoom moltiplica
     * la focale effettiva). Valori fuori range vengono ignorati.
     */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val (minZoom, maxZoom) = _zoomRange.value
        if (ratio in minZoom..maxZoom) {
            runCatching { cam.cameraControl.setZoomRatio(ratio) }
        }
    }

    fun resetZoom() {
        setZoomRatio(1f)
    }

    /** Gamma fps con esposizione più corta (fps massimo più alto). */
    private fun fastExposureRange(lensId: String?): Range<Int>? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = lensId ?: backCameraId(manager) ?: return@runCatching null
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxByOrNull { it.upper }
    }.getOrNull()

    private fun backCameraId(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    private fun videoStabilizationSupported(lensId: String?): Boolean = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = lensId ?: backCameraId(manager) ?: return@runCatching false
        val modes = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: return@runCatching false
        modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }.getOrElse { false }

    fun release() {
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraService"
    }
}