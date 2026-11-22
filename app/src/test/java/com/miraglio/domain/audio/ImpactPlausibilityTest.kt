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

    /**
     * In calibrazione non c'è ancora un modello, ma la distanza la scrive
     * l'arciere: la banda esce da quella e dalle velocità possibili.
     *
     * A 5 m il battifreccia torna in ~0,107 s e deve stare dentro; l'onda della
     * coda dello scocco a 0,040 s no (vorrebbe dire 125 m/s). A 18 m il tiro
     * del 21/11/2026 vale 0,385 s e deve stare dentro, la coda a 0,055 s no.
     */
    @Test
    fun laBandaDellaDistanzaNotaPrendeIlTiroEEscludeLaCoda() {
        val cinque = requireNotNull(ImpactPlausibility.bandAtKnownDistance(5f, 20f))
        assertTrue("0,107 s a 5 m", cinque.contains(0.107f))
        assertTrue("la coda a 0,040 s non è un impatto", !cinque.contains(0.040f))
        assertTrue("il rimbombo a 0,175 s resta possibile", cinque.contains(0.175f))

        val diciotto = requireNotNull(ImpactPlausibility.bandAtKnownDistance(18f, 20f))
        assertTrue("0,385 s a 18 m", diciotto.contains(0.385f))
        assertTrue("la coda a 0,055 s non è un impatto", !diciotto.contains(0.055f))
    }

    @Test
    fun laBandaDellaDistanzaNotaNonEsisteSenzaDistanza() {
        assertNull(ImpactPlausibility.bandAtKnownDistance(0f, 20f))
        assertNull(ImpactPlausibility.bandAtKnownDistance(-3f, 20f))
        assertNull(ImpactPlausibility.bandAtKnownDistance(5f, Float.NaN))
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
