package com.miraglio.domain.ballistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Il criterio della specifica: a 15, 50 e 70 m, a 5 °C e a 30 °C, con angoli di
 * ±15°, lo scarto deve restare sotto 0,1 m.
 *
 * Qui si prova la sola inversione (dato un tempo, ritrovare la distanza) con
 * un modello noto, perché è l'unica cosa che il telefono fa davvero: il tempo
 * arriva dall'audio, la distanza esce da questi conti.
 */
class BallisticCalculatorTest {

    /** Freccia da caccia/3D: 66 m/s di uscita, circa 55 m/s a 70 m. */
    private val model = ArrowSpeedModel(muzzleSpeedMs = 66f, decayPerM = 0.0025f)

    @Test
    fun laVelocitaDelSuonoSegueLaTemperatura() {
        assertEquals(331.3, SpeedOfSound.of(0f).toDouble(), 0.001)
        assertEquals(343.42, SpeedOfSound.of(20f).toDouble(), 0.01)
        assertEquals(349.48, SpeedOfSound.of(30f).toDouble(), 0.01)
    }

    @Test
    fun andataERitornoSottoIlDecimetro() {
        for (distance in listOf(15f, 50f, 70f)) {
            for (temperature in listOf(5f, 30f)) {
                val totalTime = BallisticCalculator.flightTime(distance, model, temperature)
                val recovered = BallisticCalculator.distance(totalTime, model, temperature)

                assertNotNull("d=$distance T=$temperature", recovered)
                assertEquals(
                    "d=$distance T=$temperature: ${recovered} m",
                    distance.toDouble(),
                    recovered!!.toDouble(),
                    0.1
                )
            }
        }
    }

    @Test
    fun laDistanzaOrizzontaleUsaIlCoseno() {
        for (angle in listOf(-15f, 0f, 15f)) {
            val horizontal = BallisticCalculator.horizontalDistance(70f, angle)
            assertEquals(
                "angolo $angle",
                70.0 * cos(angle * kotlin.math.PI / 180.0),
                horizontal.toDouble(),
                0.1
            )
        }
    }

    @Test
    fun senzaModelloNonSiInventaUnaDistanza() {
        assertNull(BallisticCalculator.distance(1.2f, null, 20f))
        assertNull(BallisticCalculator.distance(0f, model, 20f))
        assertNull(BallisticCalculator.distance(Float.NaN, model, 20f))
        assertNull(
            "un modello con velocità nulla non è un modello",
            BallisticCalculator.distance(1.2f, ArrowSpeedModel(0f, 0f), 20f)
        )
    }

    /** Il fit ritrova i coefficienti da cui i punti sono stati generati. */
    @Test
    fun ilFitRitrovaIlModello() {
        val points = listOf(20f, 40f, 60f).map { distance ->
            BallisticPoint(
                distanceM = distance,
                totalTimeS = BallisticCalculator.flightTime(distance, model, 20f),
                airTempC = 20f
            )
        }

        val fitted = BallisticCalculator.fit(points)
        assertNotNull(fitted)
        assertEquals(model.muzzleSpeedMs.toDouble(), fitted!!.muzzleSpeedMs.toDouble(), 1.0)
        assertEquals(model.decayPerM.toDouble(), fitted.decayPerM.toDouble(), 0.0005)
        for (distance in listOf(15f, 50f, 70f)) {
            assertEquals(
                "velocità a $distance m",
                model.speedAt(distance).toDouble(),
                fitted.speedAt(distance).toDouble(),
                0.5
            )
        }
    }

    /** Due tiri alla stessa distanza non sono due distanze di calibrazione. */
    @Test
    fun unaSolaDistanzaNonBasta() {
        val sameDistance = listOf(30f, 30f).map { distance ->
            BallisticPoint(distance, BallisticCalculator.flightTime(distance, model, 20f), 20f)
        }
        assertNull(BallisticCalculator.fit(sameDistance))
    }

    @Test
    fun iTiriSenzaTempoNonEntranoNelFit() {
        val points = listOf(
            BallisticPoint(20f, BallisticCalculator.flightTime(20f, model, 20f), 20f),
            BallisticPoint(40f, 0f, 20f)
        )
        assertNull(BallisticCalculator.fit(points))
    }

    /** Il tempo del suono più lungo del totale non è una misura fisica. */
    @Test
    fun unTempoImpossibileNonProduceUnModello() {
        val points = listOf(
            BallisticPoint(20f, 0.0001f, 20f),
            BallisticPoint(40f, 0.0001f, 20f)
        )
        assertNull(BallisticCalculator.fit(points))
    }

    /**
     * Lo scarto si misura in metri, non in secondi: un tiro a 50 m che arriva
     * 50 ms dopo il previsto è circa 2,4 m più lontano.
     */
    @Test
    fun loScartoEDistanzaENonTempo() {
        val expectedTime = BallisticCalculator.flightTime(50f, model, 20f)
        val deviation = BallisticCalculator.deviationM(model, 50f, expectedTime + 0.05f, 20f)

        assertNotNull(deviation)
        assertTrue("scarto $deviation m", deviation!! in 2.0f..2.8f)
        assertEquals(
            0.0,
            BallisticCalculator.deviationM(model, 50f, expectedTime, 20f)!!.toDouble(),
            0.01
        )
    }

    @Test
    fun laSogliaDecideSeUnTiroESospetto() {
        val expectedTime = BallisticCalculator.flightTime(50f, model, 20f)

        assertTrue(
            BallisticCalculator.isOutlier(model, 50f, expectedTime + 0.05f, 20f, thresholdM = 1f)
        )
        assertEquals(
            false,
            BallisticCalculator.isOutlier(model, 50f, expectedTime + 0.005f, 20f, thresholdM = 1f)
        )
    }

    /** La distanza decresce con il tempo: nessun modello "che accelera". */
    @Test
    fun ilModelloNonAccelera() {
        val monotone = listOf(15f, 30f, 50f, 70f).map { it to model.speedAt(it) }
        for (i in 1 until monotone.size) {
            assertTrue(
                "la freccia rallenta: ${monotone[i - 1]} -> ${monotone[i]}",
                monotone[i].second < monotone[i - 1].second
            )
        }
        assertTrue(abs(model.speedAt(70f) - 55.4f) < 1f)
    }
}
