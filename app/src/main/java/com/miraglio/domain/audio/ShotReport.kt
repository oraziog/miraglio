package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator

/**
 * Quello che si può dire di un tiro misurato: il tempo, la distanza, la
 * distanza orizzontale, la velocità della freccia a quel bersaglio, e quanto
 * il numero merita fiducia ([quality]).
 *
 * Sta fuori dalla ViewModel perché è la parte che sbaglia i conti: la
 * schermata non deve decidere niente di numerico, solo mostrare.
 */
data class ShotReport(
    val totalTimeS: Float,
    val distanceM: Float,
    val horizontalDistanceM: Float,
    val speedMs: Float?,
    val pitchDeg: Float,
    /** Verde/giallo/rosso: quanto è solida la scelta dell'impatto. */
    val quality: ShotQuality,
    /** In ascolto il motore ha sentito il pattern click→corda. */
    val clickerDetected: Boolean
)

object ShotReportBuilder {

    /**
     * Il report del tiro, o `null` se il profilo non è ancora calibrato.
     *
     * [model] nullo significa meno di due distanze di calibrazione: in quel
     * caso la distanza non esiste e non si inventa (vedi
     * [BallisticCalculator.distance]).
     *
     * [evidence] e [fieldRangeM] non cambiano il numero: servono a dire
     * quanta fiducia merita ([ShotQuality.evaluate]).
     */
    fun build(
        totalTimeS: Float,
        model: ArrowSpeedModel?,
        airTempC: Float,
        pitchDeg: Float,
        evidence: ImpactEvidence = ImpactEvidence.BROADBAND_IN_BAND,
        fieldRangeM: ClosedFloatingPointRange<Float>? = null,
        clickerDetected: Boolean = false
    ): ShotReport? {
        if (!totalTimeS.isFinite() || totalTimeS <= 0f) return null
        val distance = BallisticCalculator.distance(totalTimeS, model, airTempC) ?: return null
        val speed = model?.speedAt(distance)?.takeIf { it.isFinite() && it > 0f }
        return ShotReport(
            totalTimeS = totalTimeS,
            distanceM = distance,
            horizontalDistanceM = BallisticCalculator.horizontalDistance(distance, pitchDeg),
            speedMs = speed,
            pitchDeg = pitchDeg,
            quality = ShotQuality.evaluate(distance, evidence, fieldRangeM),
            clickerDetected = clickerDetected
        )
    }
}
