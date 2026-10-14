package com.miraglio.data.vision

import android.graphics.Bitmap
import com.miraglio.domain.vision.DetectionBox
import com.miraglio.domain.vision.DetectionResult
import com.miraglio.domain.vision.ObjectDetector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoOpObjectDetector @Inject constructor() : ObjectDetector {

    override val modelLabel: String = "no-op (modello ONNX non caricato)"

    override fun detect(bitmap: Bitmap): DetectionResult = DetectionResult(emptyList(), 0)

    override fun close() = Unit
}