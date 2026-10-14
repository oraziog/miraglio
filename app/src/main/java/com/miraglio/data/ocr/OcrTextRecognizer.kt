package com.miraglio.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val normalizedTop: Float,
    val normalizedBottom: Float,
    val normalizedLeft: Float,
    val normalizedRight: Float,
    val confidence: Float = 1.0f
)

@Singleton
class OcrTextRecognizer @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _isReady = MutableStateFlow(true)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): List<OcrLine> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            val box = line.boundingBox
                            if (box != null) {
                                OcrLine(
                                    text = line.text,
                                    normalizedTop = box.top / bitmap.height.toFloat(),
                                    normalizedBottom = box.bottom / bitmap.height.toFloat(),
                                    normalizedLeft = box.left / bitmap.width.toFloat(),
                                    normalizedRight = box.right / bitmap.width.toFloat()
                                )
                            } else {
                                OcrLine(line.text, 0f, 0f, 0f, 0f)
                            }
                        }
                    }
                    continuation.resume(lines)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }

    fun close() {
        recognizer.close()
    }
}