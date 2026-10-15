package com.miraglio.domain.vision

import kotlin.math.pow

data class ScoredTarget(
    val box: DetectionBox,
    val score: Float,
    val estimatedDistanceMeters: Float?
)

object TargetScorer {

    fun score(
        box: DetectionBox,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f
        val distanceFromCenter = kotlin.math.sqrt(
            (box.centerX.toDouble() - centerX).pow(2) +
                (box.centerY.toDouble() - centerY).pow(2)
        ).toFloat()

        val normalizedScreenSignificant = (box.area() / (imageWidth * imageHeight).toFloat()) * 10
        val centerPenalty = distanceFromCenter * 0.005f

        return (box.confidence + normalizedScreenSignificant.coerceIn(0f, 0.4f)) /
            (1.0f + centerPenalty)
    }

    fun bestTarget(
        detections: List<DetectionBox>,
        imageWidth: Int,
        imageHeight: Int
    ): ScoredTarget? =
        bestTarget(detections, imageWidth, imageHeight, preferredLabels = null)

    /**
     * Il box di area maggiore fra i rilevamenti: e' il criterio con cui le
     * linee si agganciano al fermo immagine (l'oggetto piu' grande inquadrato,
     * qualunque sia la classe riconosciuta).
     */
    fun largest(detections: List<DetectionBox>): DetectionBox? =
        detections.maxByOrNull { it.area() }

    /**
     * Sceglie il miglior box privilegiando in ordine le classi in [preferredLabels]
     * (es. paglione -> targa). Se nessuna classe preferita produce un box, ripiega
     * sul miglior box qualunque sia la classe.
     */
    fun bestTarget(
        detections: List<DetectionBox>,
        imageWidth: Int,
        imageHeight: Int,
        preferredLabels: List<String>?
    ): ScoredTarget? {
        if (!preferredLabels.isNullOrEmpty()) {
            for (label in preferredLabels) {
                val ranked = detections
                    .filter { it.label == label }
                    .map { ScoredTarget(it, score(it, imageWidth, imageHeight), null) }
                    .maxByOrNull { it.score }
                if (ranked != null) return ranked
            }
        }
        return detections
            .map { ScoredTarget(it, score(it, imageWidth, imageHeight), null) }
            .maxByOrNull { it.score }
    }
}