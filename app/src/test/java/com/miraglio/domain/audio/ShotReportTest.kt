package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La schermata mostra quello che questo oggetto calcola: se qui la distanza è
 * quella sbagliata, l'interfaccia non può accorgersene.
 */
class ShotReportTest {

    private val model = ArrowSpeedModel(muzzleSpeedMs = 64f, decayPerM = 0.002f)

    @Test
    fun ilReportUsaLaStessaFisicaDelModello() {
        val distance = 55f
        val time = BallisticCalculator.flightTime(distance, model, 22f)

        val report = ShotReportBuilder.build(time, model, 22f)

        assertNotNull(report)
        assertEquals(distance.toDouble(), report!!.distanceM.toDouble(), 0.1)
        assertEquals(time.toDouble(), report.totalTimeS.toDouble(), 1e-4)
        assertEquals(model.speedAt(distance).toDouble(), report.speedMs!!.toDouble(), 0.5)
    }

    /** Profilo non calibrato: nessuna distanza inventata. */
    @Test
    fun senzaModelloNonCEUnReport() {
        assertNull(ShotReportBuilder.build(1.2f, null, 20f))
    }

    /**
     * Verde, giallo, rosso: il colore non cambia il numero, dice su cosa si
     * regge la scelta dell'impatto.
     */
    @Test
    fun ilColoreSegueLaProvaDellImpatto() {
        val time = BallisticCalculator.flightTime(30f, model, 20f)

        val verde = ShotReportBuilder.build(
            time, model, 20f,
            evidence = ImpactEvidence.BROADBAND_IN_BAND
        )!!
        assertEquals(ShotConfidence.HIGH, verde.quality.confidence)
        assertEquals(ShotConfidenceReason.OK, verde.quality.reason)

        val giallo = ShotReportBuilder.build(
            time, model, 20f,
            evidence = ImpactEvidence.LOW_IN_BAND
        )!!
        assertEquals(ShotConfidence.MEDIUM, giallo.quality.confidence)
        assertEquals(ShotConfidenceReason.NO_BROADBAND_PROOF, giallo.quality.reason)

        val appenaOltre = ShotReportBuilder.build(
            time, model, 20f,
            evidence = ImpactEvidence.NEAR_BAND
        )!!
        assertEquals(ShotConfidence.MEDIUM, appenaOltre.quality.confidence)
        assertEquals(
            ShotConfidenceReason.IMPACT_JUST_OUTSIDE_FIELD,
            appenaOltre.quality.reason
        )

        val rosso = ShotReportBuilder.build(
            time, model, 20f,
            evidence = ImpactEvidence.OUT_OF_BAND
        )!!
        assertEquals(ShotConfidence.LOW, rosso.quality.confidence)
        assertEquals(ShotConfidenceReason.IMPACT_OUT_OF_BAND, rosso.quality.reason)
    }

    @Test
    fun senzaTempoNonCEUnReport() {
        assertNull(ShotReportBuilder.build(0f, model, 20f))
        assertNull(ShotReportBuilder.build(Float.NaN, model, 20f))
    }
}
