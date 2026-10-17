package com.miraglio.ui.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le tacche dell'asse delle distanze si vedono solo in grafico, ma sbagliarle
 * si nota subito: etichette come "9,33" o tacche fuori dall'area dei dati
 * fanno sembrare la calibrazione un conto della serva. Qui si controlla il
 * contratto: valori tondi, dentro gli estremi dei dati, mai piu' del numero
 * chiesto, e nessun intervallo inventato quando la distanza e' una sola.
 */
class AxisTicksTest {

    /** Il caso reale: misure a 5, 10 e 18 m. */
    @Test
    fun DistanzeToncheInMetri() {
        assertEquals(listOf(5f, 10f, 15f), axisTicks(5f, 18f))
    }

    /** Un intervallo stretto usa un passo fine, non una sola tacca. */
    @Test
    fun IntervalloStrettoUsaUnPassoFine() {
        assertEquals(listOf(0.5f, 1.0f, 1.5f), axisTicks(0.5f, 1.5f))
    }

    /** Tutte le tacche stanno dentro gli estremi, e il numero richiesto e' rispettato. */
    @Test
    fun LeTaccheRestanoDentroIDati() {
        val ticks = axisTicks(3f, 47f, maxTicks = 5)

        assertTrue("almeno due tacche per leggere una scala", ticks.size >= 2)
        assertTrue("mai piu' di quelle chieste", ticks.size <= 5)
        assertTrue("in ordine crescente", ticks.zipWithNext().all { it.first < it.second })
        assertTrue("dentro l'intervallo", ticks.all { it in 3f..47f })
    }

    /**
     * Il caso che ha motivato il conteggio reale delle tacche: la focale
     * 700-1400 px. Con il vecchio criterio ("spazio / passo <= 3") il passo
     * accettato era 500 e l'asse verticale mostrava una tacca sola, a 1000:
     * numeri che non aiutano a leggere dove sta un punto.
     */
    @Test
    fun FocaleNonRidottaAUnaTaccaSola() {
        assertEquals(listOf(800f, 1000f, 1200f, 1400f), axisTicks(700f, 1400f))
        assertEquals(listOf(800f, 1000f, 1200f, 1400f), axisTicks(695f, 1400f))
    }

    /** Una distanza sola: una tacca sola, il valore misurato. */
    @Test
    fun DistanzaUnicaNonInventaUnIntervallo() {
        assertEquals(listOf(12f), axisTicks(12f, 12f))
    }

    /** Estremi invertiti o valori non finiti: nessuna tacca, non una tacca sbagliata. */
    @Test
    fun ValoriImpossibiliNonProduconoTacche() {
        assertEquals(emptyList<Float>(), axisTicks(20f, 5f))
        assertEquals(emptyList<Float>(), axisTicks(Float.NaN, 5f))
        assertEquals(emptyList<Float>(), axisTicks(1f, Float.POSITIVE_INFINITY))
        assertEquals(emptyList<Float>(), axisTicks(1f, 5f, maxTicks = 1))
    }

    /** Un passo da 5 m si legge intero; uno da mezzo metro vuole un decimale. */
    @Test
    fun IDecimaliSeguonoIlPasso() {
        assertEquals(0, tickDecimals(5f))
        assertEquals(0, tickDecimals(1f))
        assertEquals(1, tickDecimals(0.5f))
        assertEquals(2, tickDecimals(0.05f))
        assertEquals(0, tickDecimals(0f))
    }
}
