package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

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

        val report = ShotReportBuilder.build(time, model, 22f, pitchDeg = 0f)

        assertNotNull(report)
        assertEquals(distance.toDouble(), report!!.distanceM.toDouble(), 0.1)
        assertEquals(time.toDouble(), report.totalTimeS.toDouble(), 1e-4)
        assertEquals(model.speedAt(distance).toDouble(), report.speedMs!!.toDouble(), 0.5)
    }

    @Test
    fun lAngoloCambiaSoloLaDistanzaOrizzontale() {
        val time = BallisticCalculator.flightTime(60f, model, 15f)

        val flat = ShotReportBuilder.build(time, model, 15f, pitchDeg = 0f)!!
        val steep = ShotReportBuilder.build(time, model, 15f, pitchDeg = 25f)!!

        assertEquals(flat.distanceM.toDouble(), steep.distanceM.toDouble(), 1e-4)
        assertEquals(
            steep.distanceM * cos(Math.toRadians(25.0)),
            steep.horizontalDistanceM.toDouble(),
            0.05
        )
        assertTrue(steep.horizontalDistanceM < flat.horizontalDistanceM)
    }

    /** Profilo non calibrato: nessuna distanza inventata. */
    @Test
    fun senzaModelloNonCEUnReport() {
        assertNull(ShotReportBuilder.build(1.2f, null, 20f, 0f))
    }

    @Test
    fun senzaTempoNonCEUnReport() {
        assertNull(ShotReportBuilder.build(0f, model, 20f, 0f))
        assertNull(ShotReportBuilder.build(Float.NaN, model, 20f, 0f))
    }
}
