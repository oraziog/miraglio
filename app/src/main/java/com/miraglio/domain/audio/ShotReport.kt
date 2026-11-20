package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator

/**
 * Quello che si può dire di un tiro misurato: il tempo, la distanza, la
 * distanza orizzontale, la velocità della freccia a quel bersaglio.
 *
 * Sta fuori dalla ViewModel perché è la parte che sbaglia i conti: la
 * schermata non deve decidere niente di numerico, solo mostrare.
 */
data class ShotReport(
    val totalTimeS: Float,
    val distanceM: Float,
    val horizontalDistanceM: Float,
    val speedMs: Float?,
    val pitchDeg: Float
)

object ShotReportBuilder {

    /**
     * Il report del tiro, o `null` se il profilo non è ancora calibrato.
     *
     * [model] nullo significa meno di due distanze di calibrazione: in quel
     * caso la distanza non esiste e non si inventa (vedi
     * [BallisticCalculator.distance]).
     */
    fun build(
        totalTimeS: Float,
        model: ArrowSpeedModel?,
        airTempC: Float,
        pitchDeg: Float
    ): ShotReport? {
        if (!totalTimeS.isFinite() || totalTimeS <= 0f) return null
        val distance = BallisticCalculator.distance(totalTimeS, model, airTempC) ?: return null
        val speed = model?.speedAt(distance)?.takeIf { it.isFinite() && it > 0f }
        return ShotReport(
            totalTimeS = totalTimeS,
            distanceM = distance,
            horizontalDistanceM = BallisticCalculator.horizontalDistance(distance, pitchDeg),
            speedMs = speed,
            pitchDeg = pitchDeg
        )
    }
}
