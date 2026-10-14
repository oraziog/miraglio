package com.miraglio.domain.vision

import android.graphics.Bitmap

data class DetectionResult(
    val detections: List<DetectionBox>,
    val latencyMs: Long
)

interface ObjectDetector {
    val modelLabel: String

    fun detect(bitmap: Bitmap): DetectionResult

    fun close()
}