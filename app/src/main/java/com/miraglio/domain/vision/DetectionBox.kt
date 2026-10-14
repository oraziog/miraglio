package com.miraglio.domain.vision

data class DetectionBox(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun area(): Float = (right - left) * (bottom - top)
}