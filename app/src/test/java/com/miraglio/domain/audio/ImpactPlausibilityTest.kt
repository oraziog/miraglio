package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La banda di plausibilità è quella del profilo, o non c'è. */
class ImpactPlausibilityTest {

    @Test
    fun laBandaSonoIDueTempiDiVoloDelProfilo() {
        val modello = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)
        val banda = requireNotNull(
            ImpactPlausibility.band(modello, listOf(65f, 75f), airTempC = 20f)
        )
        assertEquals(
            BallisticCalculator.flightTime(65f, modello, 20f),
            banda.start,
            1e-4f
        )
        assertEquals(
            BallisticCalculator.flightTime(75f, modello, 20f),
            banda.endInclusive,
            1e-4f
        )
        assertTrue("la banda deve contenere i tiri a 70 m", banda.contains(1.445f))
    }

    @Test
    fun ilBarebowIndoorNonStaNellaVecchiaCostante() {
        val banda = requireNotNull(
            ImpactPlausibility.band(
                ArrowSpeedModel(51f, 0.002f),
                listOf(16f, 30f),
                airTempC = 20f
            )
        )
        assertTrue("0,418 s deve stare nella banda", banda.contains(0.418f))
        assertTrue(
            "la costante 1,0–2,0 non descrive un tiro a 18 m",
            !banda.contains(1.0f)
        )
    }

    /**
     * Il tiro di campagna H&S: il bersaglio è a distanza sconosciuta fra 5 e
     * 50 m, e chi ha calibrato l'arco a 15–25 m deve poter misurare anche un
     * 45 m. La banda si allarga fino al campo dichiarato.
     */
    @Test
    fun ilCampoDiCacciaApreLaBandaFinoACinquantaMetri() {
        val modello = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)
        val banda = requireNotNull(
            ImpactPlausibility.band(
                modello,
                listOf(15f, 25f),
                airTempC = 20f,
                fieldRangeM = ImpactPlausibility.FIELD_RANGE_M
            )
        )
        assertEquals(
            BallisticCalculator.flightTime(5f, modello, 20f),
            banda.start,
            1e-4f
        )
        assertEquals(
            BallisticCalculator.flightTime(50f, modello, 20f),
            banda.endInclusive,
            1e-4f
        )
        assertTrue(
            "un 45 m deve stare nella finestra",
            banda.contains(BallisticCalculator.flightTime(45f, modello, 20f))
        )
    }

    /** Un profilo calibrato più lontano (65–75 m) allarga la banda, non la stringe. */
    @Test
    fun ilCampoDiCacciaNonStringeUnProfiloCalibratoPiuLontano() {
        val modello = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)
        val banda = requireNotNull(
            ImpactPlausibility.band(
                modello,
                listOf(65f, 75f),
                airTempC = 20f,
                fieldRangeM = ImpactPlausibility.FIELD_RANGE_M
            )
        )
        assertEquals(
            BallisticCalculator.flightTime(5f, modello, 20f),
            banda.start,
            1e-4f
        )
        assertEquals(
            BallisticCalculator.flightTime(75f, modello, 20f),
            banda.endInclusive,
            1e-4f
        )
        assertTrue("il tiro a 70 m resta dentro", banda.contains(1.445f))
    }

    @Test
    fun senzaModelloNonCEBanda() {
        assertNull(ImpactPlausibility.band(null, listOf(20f, 70f), 20f))
    }

    @Test
    fun conModelloNonValidoNonCEBanda() {
        assertNull(
            ImpactPlausibility.band(ArrowSpeedModel(0f, 0.002f), listOf(20f, 70f), 20f)
        )
        assertNull(
            ImpactPlausibility.band(ArrowSpeedModel(60f, -0.1f), listOf(20f, 70f), 20f)
        )
    }

    @Test
    fun unaSolaDistanzaNonBasta() {
        val modello = ArrowSpeedModel(60f, 0.001f)
        assertNull(ImpactPlausibility.band(modello, listOf(30f), 20f))
        assertNull(
            "due tiri alla stessa distanza non sono una calibrazione",
            ImpactPlausibility.band(modello, listOf(30f, 30.02f), 20f)
        )
    }

    @Test
    fun leDistanzeNonValideSiScartano() {
        val modello = ArrowSpeedModel(60f, 0.001f)
        assertNull(
            ImpactPlausibility.band(
                modello,
                listOf(20f, Float.NaN, -5f, 0f),
                airTempC = 20f
            )
        )
        assertTrue(
            ImpactPlausibility.band(modello, listOf(20f, Float.NaN, 60f), 20f) != null
        )
    }

    @Test
    fun unaTemperaturaNonFinitaNonProduceUnaBanda() {
        assertNull(
            ImpactPlausibility.band(
                ArrowSpeedModel(60f, 0.001f),
                listOf(20f, 60f),
                airTempC = Float.NaN
            )
        )
    }
}
