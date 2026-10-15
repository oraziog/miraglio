package com.miraglio.data.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.miraglio.data.ocr.OcrTextRecognizer
import com.miraglio.domain.vision.DetectionBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AnalyzerFrameState(
    val detectionBoxes: List<DetectionBox> = emptyList(),
    val ocrLines: List<com.miraglio.data.ocr.OcrLine> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

/**
 * Analizzatore dei fotogrammi di anteprima.
 *
 * NON esegue inferenza: tiene solo l'ultimo fotogramma utile
 * ([getLatestBitmap]) e le sue dimensioni. Il riconoscimento dei bersagli
 * avviene una volta sola, sul fermo immagine, quando l'utente scatta
 * (vedi CaptureViewModel.analyzeFrozenLines): cosi' il preview non paga
 * inferenza continua e nulla compare prima dello scatto.
 */
class MiraglioAnalyzer(
    private val ocrRecognizer: OcrTextRecognizer,
    private val ocrEveryNthFrame: Int = OCR_EVERY_NTH_FRAME
) : ImageAnalysis.Analyzer {

    private val _frameState = MutableStateFlow(AnalyzerFrameState())
    val frameState: StateFlow<AnalyzerFrameState> = _frameState.asStateFlow()

    private var lastProcessedNs = 0L
    private val minIntervalNs = 100_000_000L  // ~10 fps

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var latestRotationDegrees: Int = 0

    fun getLatestBitmap(): Bitmap? = latestBitmap

    fun getLatestRotationDegrees(): Int = latestRotationDegrees

    companion object {
        const val OCR_EVERY_NTH_FRAME = 5
        private const val TAG = "MiraglioAnalyzer"
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.nanoTime()
        if (now - lastProcessedNs < minIntervalNs) {
            imageProxy.close()
            return
        }
        lastProcessedNs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = runCatching {
                imageProxy.toBitmap()
            }.getOrElse { conversionError ->
                Log.w(TAG, "ImageProxy.toBitmap fallito, ripiego su conversione manuale", conversionError)
                runCatching {
                    BitmapConverter.toBitmap(mediaImage, rotation)
                }.getOrNull()
            }

            if (bitmap != null) {
                val rotatedBitmap = if (rotation != 0) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap
                latestBitmap = rotatedBitmap
                latestRotationDegrees = rotation
                // Nessun rilevamento qui: solo fotogramma e dimensioni reali,
                // che servono al fermo immagine e alla focale teorica.
                _frameState.value = AnalyzerFrameState(
                    detectionBoxes = emptyList(),
                    ocrLines = emptyList(),
                    frameWidth = rotatedBitmap.width,
                    frameHeight = rotatedBitmap.height
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Errore non bloccante nell'analisi frame", t)
        } finally {
            imageProxy.close()
        }
    }
}
