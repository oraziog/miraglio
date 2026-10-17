package com.miraglio.domain.reticle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le tacche del mirino sono numeri che l'arciere usa per regolare la mira:
 * un errore di unita' (gradi invece di MOA, metri invece di pollici) non fa
 * cadere niente e non si vede a schermo, si vede sul bersaglio. Da qui i
 * controlli incrociati: la dimensione angolare si calcola per due strade
 * indipendenti e le due devono dire la stessa cosa.
 */
class ReticleScaleTest {

    /** Paglione FITA: 122 cm. A 30 m quanti MOA copre? */
    @Test
    fun ilPaglioneATrentaMetri() {
        val angolo = angularSize(targetSizeM = 1.22f, distanceM = 30f)
        assertNotNull(angolo)
        requireNotNull(angolo)

        assertEquals("MOA a 30 m", 139.8f, angolo.moa, 0.5f)
        assertEquals("pollici a 20 iarde", 29.3f, angolo.inchesAt20Yards, 0.2f)
    }

    /**
     * Controllo incrociato, la parte che conta davvero: la stessa grandezza
     * calcolata **senza** passare per i MOA. Un bersaglio di 122 cm a 30 m
     * (32,8 iarde) e' come un bersaglio di 48 pollici visto a 20 iarde,
     * riportato alla distanza vera: 48 × 20/32,8 = 29,3 pollici a 20 iarde.
     *
     * Le due strade differiscono solo per il termine di small-angle (x² ≈
     * 0,04%), ed e' per questo che la tolleranza non e' zero: zero
     * significherebbe confrontare due volte la stessa formula.
     */
    @Test
    fun leDueUnitaDiconoLaStessaCosa() {
        val casi = listOf(
            Triple(1.22f, 30f, 48.03f),
            Triple(0.80f, 12f, 31.50f),
            Triple(0.40f, 45f, 15.75f)
        )
        for ((dimensione, distanza, polliciTarget) in casi) {
            val angolo = requireNotNull(angularSize(dimensione, distanza))
            val iarde = distanza / 0.9144f
            val atteso = polliciTarget * 20f / iarde

            assertEquals(
                "bersaglio $dimensione m a $distanza m: le due strade devono coincidere",
                atteso,
                angolo.inchesAt20Yards,
                atteso * 0.005f
            )
        }
    }

    /** Il fattore di conversione fra le due unita' e' quello dei mirini. */
    @Test
    fun unPolliceAventiIardeSonQuasiCinqueMOA() {
        assertEquals(4.7746f, MOA_PER_INCH_AT_20_YARDS, 0.0001f)

        val unPollice = angularSize(targetSizeM = 0.0254f, distanceM = 18.288f)
        requireNotNull(unPollice)
        assertEquals(4.7746f, unPollice.moa, 0.01f)
        assertEquals(1f, unPollice.inchesAt20Yards, 0.001f)
    }

    /**
     * Il passo si allarga quando la focale non basta: a 1x (circa 1200 px di
     * focale) servono 100 MOA per stare sopra i 24 px, a 8x ne bastano 10.
     * Se questo test cadesse e il passo scendesse a 1 MOA, la scala
     * diventerebbe una fila di righe sovrapposte.
     */
    @Test
    fun ilPassoSiAdattaAllaFocale() {
        val pxPerMoa1x = requireNotNull(pixelsPerMoa(1200f))
        val pxPerMoa8x = requireNotNull(pixelsPerMoa(9600f))

        assertEquals("100 MOA per stare sopra i 24 px a 1x", 100, requireNotNull(reticleScale(pxPerMoa1x, 24f)).stepMoa)
        assertEquals("10 MOA a 8x", 10, requireNotNull(reticleScale(pxPerMoa8x, 24f)).stepMoa)

        // La spaziatura non scende mai sotto la soglia chiesta...
        assertTrue(requireNotNull(reticleScale(pxPerMoa1x, 24f)).stepPx >= 24f)
        assertTrue(requireNotNull(reticleScale(pxPerMoa8x, 24f)).stepPx >= 24f)
        // ...tranne quando nemmeno il passo massimo basta.
        val senzaZoomSufficiente = requireNotNull(reticleScale(0.01f, 24f))
        assertEquals(500, senzaZoomSufficiente.stepMoa)
        assertTrue(senzaZoomSufficiente.stepPx < 24f)
    }

    /** Il passo, letto nell'altra unita', e' il numero che va scritto accanto. */
    @Test
    fun ilPassoSiLeggeAncheInPollici() {
        val pxPerMoa = requireNotNull(pixelsPerMoa(1200f))
        val scala = requireNotNull(reticleScale(pxPerMoa, 24f))

        assertEquals(100, scala.stepMoa)
        assertEquals("100 MOA valgono 21 in a 20 iarde", 20.9f, scala.stepInchesAt20Yards, 0.1f)
    }

    /**
     * Con un tele zoommato (focale effettiva di decine di migliaia di pixel) il
     * passo scende fino a 5 MOA, e li' si ferma: perche' una tacca da 1 MOA
     * faccia 24 px servirebbero oltre 80.000 px di focale, che nessuna
     * fotocamera di telefono ha. E' il motivo per cui questa scala non promette
     * mai una finezza che non puo' mantenere.
     */
    @Test
    fun conUnTeleZoommatoIlPassoScendeACinqueMOA() {
        val scala = requireNotNull(reticleScale(requireNotNull(pixelsPerMoa(30_000f)), 24f))
        assertEquals(5, scala.stepMoa)

        // Sotto i 5 MOA non si scende: la soglia di 24 px taglierebbe fuori
        // anche il passo 1, che a questa focale farebbe 8,7 px.
        assertTrue(requireNotNull(pixelsPerMoa(30_000f)) * 2f < 24f)
        assertTrue(requireNotNull(pixelsPerMoa(90_000f)) * 1f >= 24f)
        assertEquals(1, requireNotNull(reticleScale(requireNotNull(pixelsPerMoa(90_000f)), 24f)).stepMoa)
    }

    /** Dati non utilizzabili: niente tacche, invece di tacche inventate. */
    @Test
    fun senzaNumeriNonCiSonoTacche() {
        assertNull(angularSize(0f, 30f))
        assertNull(angularSize(1.22f, 0f))
        assertNull(angularSize(-1.22f, 30f))
        assertNull(angularSize(1.22f, -30f))
        assertNull(angularSize(Float.NaN, 30f))
        assertNull(angularSize(1.22f, Float.POSITIVE_INFINITY))

        assertNull(pixelsPerMoa(0f))
        assertNull(pixelsPerMoa(-10f))
        assertNull(pixelsPerMoa(Float.NaN))

        assertNull(reticleScale(0f, 24f))
        assertNull(reticleScale(-1f, 24f))
        assertNull(reticleScale(Float.NaN, 24f))
        assertNull(reticleScale(1f, 0f))
    }
}
