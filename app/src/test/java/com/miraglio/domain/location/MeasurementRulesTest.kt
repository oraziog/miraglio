package com.miraglio.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le regole che decidono se un fix entra nella misura e se un tratto e' un
 * passo. Sono il punto in cui la misura sbagliava: la partenza arrivava dal
 * fix vecchio del tracker (o da un fix di rete) e i passi sotto il metro
 * sparivano dal percorso a piedi. Qui si verificano senza telefono, con
 * valori scritti a mano.
 */
class MeasurementRulesTest {

    /** Un grado di latitudine vale circa 111 194 m. */
    private val metersPerDegreeLat = 111_194.0

    /** Fix a [metersNorth] dalla base, con eta' e precisione volute. */
    private fun fix(
        metersNorth: Double = 0.0,
        accuracyM: Float = 2f,
        elapsedMs: Long = 10_000L,
        speedMps: Float? = 1.4f
    ) = WalkFix(
        latDeg = 44.0 + metersNorth / metersPerDegreeLat,
        lonDeg = 8.0,
        accuracyM = accuracyM,
        elapsedMs = elapsedMs,
        speedMps = speedMps
    )

    @Test
    fun fixDiAdessoEAccuratoSiUsa() {
        val f = fix(elapsedMs = 10_000L)
        assertTrue(MeasurementRules.isUsable(f, nowElapsedMs = 11_000L, maxAccuracyM = 10f))
        // Al limite dell'eta' ammessa (5 s) vale ancora...
        assertTrue(MeasurementRules.isUsable(f, nowElapsedMs = 15_000L, maxAccuracyM = 10f))
        // ...un istante dopo no: e' il fix di prima, non quello di adesso.
        assertFalse(MeasurementRules.isUsable(f, nowElapsedMs = 15_001L, maxAccuracyM = 10f))
    }

    @Test
    fun fixScadenteNonSiUsa() {
        assertFalse(MeasurementRules.isUsable(fix(accuracyM = 30f), 11_000L, maxAccuracyM = 10f))
        // Senza accuratezza dichiarata il fix non dice quanto vale.
        assertFalse(MeasurementRules.isUsable(fix(accuracyM = 0f), 11_000L, maxAccuracyM = 10f))
    }

    @Test
    fun passoNormaleContato() {
        val step = MeasurementRules.stepMeters(fix(), fix(metersNorth = 1.4, elapsedMs = 11_000L))
        assertNotNull(step)
        assertEquals(1.4f, step!!, 0.05f)
    }

    @Test
    fun passoDaMezzoSecondoSottoIlMetroContato() {
        // Con il GPS a 2 Hz i passi valgono circa 0,7 m: la vecchia soglia di
        // 1 m li buttava via, e il percorso a piedi risultava piu' corto
        // della distanza in linea d'aria (impossibile).
        val step = MeasurementRules.stepMeters(fix(), fix(metersNorth = 0.7, elapsedMs = 10_500L))
        assertNotNull(step)
        assertEquals(0.7f, step!!, 0.05f)
    }

    @Test
    fun saltoDaFixRipartitoNonContato() {
        // Il caso dei "20 m dopo pochi passi": la partenza era un fix vecchio e
        // il primo fix vero stava 20 m piu' avanti. Non e' un passo percorso.
        val step = MeasurementRules.stepMeters(fix(), fix(metersNorth = 20.0, elapsedMs = 11_000L))
        assertNull(step)
    }

    @Test
    fun rumoreDaFermoNonContato() {
        // Da fermo il fix oscilla di qualche metro, ma dichiara velocita' zero:
        // quell'oscillazione non deve gonfiare il percorso.
        val step = MeasurementRules.stepMeters(
            fix(speedMps = 0f),
            fix(metersNorth = 1.2, elapsedMs = 11_000L, speedMps = 0.05f)
        )
        assertNull(step)
    }

    @Test
    fun spostamentoSottoIlRumoreNonContato() {
        val step = MeasurementRules.stepMeters(fix(), fix(metersNorth = 0.2, elapsedMs = 11_000L))
        assertNull(step)
    }

    @Test
    fun bucoNegliAggiornamentiNonContato() {
        // Dopo un buco negli aggiornamenti il tratto non e' camminato: il
        // percorso a piedi non deve inventarsi i metri mancanti.
        val step = MeasurementRules.stepMeters(fix(), fix(metersNorth = 2.0, elapsedMs = 40_000L))
        assertNull(step)
    }

    @Test
    fun trattoConFixScadenteNonContato() {
        val step = MeasurementRules.stepMeters(
            fix(accuracyM = 30f),
            fix(metersNorth = 1.4, elapsedMs = 11_000L)
        )
        assertNull(step)
    }

    @Test
    fun distanzaInLineaDAriaNota() {
        // Sanita' del calcolo (Haversine) su una distanza nota: 1000 m a nord.
        assertEquals(1000f, MeasurementRules.distanceM(fix(), fix(metersNorth = 1000.0)), 1f)
    }
}
