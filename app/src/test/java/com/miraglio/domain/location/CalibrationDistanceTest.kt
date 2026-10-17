package com.miraglio.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La distanza GPS di calibrazione e' il numero che finisce nel campo distanza
 * al posto della mano dell'utente: se sbaglia (argomenti in fila diverso,
 * punti mancanti trattati come zeri), la focale calcolata con quella distanza
 * e' sbagliata nello stesso modo.
 */
class CalibrationDistanceTest {

    private val attuale = GpsFix(45.501000, 9.200000, 4f, 1_700_000_000_000L)
    private val bersaglio = GpsFix(45.502000, 9.200000, 3f, 1_700_000_100_000L)

    /**
     * 0,001° di latitudine sul meridiano: arco = R * delta_phi con il R
     * dell'formula (6371 km), cioe' circa 111,19 m. I due punti hanno la stessa
     * longitudine, quindi un errore di fila fra lat e lon qui si vede subito.
     */
    @Test
    fun distanzaSulMeridiano() {
        val distanza = CalibrationDistance.between(attuale, bersaglio)

        assertEquals(111.19, distanza!!.toDouble(), 0.05)
    }

    /**
     * Stessa latitudine, longitudine diversa: l'arco si accorcia del coseno
     * della latitudine (45,5° -> circa 77,94 m). Anche qui lat e lon scambiati
     * darebbero 111 m, quindi il test li tiene in ordine.
     */
    @Test
    fun distanzaSullaStessaLatitudine() {
        val est = GpsFix(45.501000, 9.201000, 4f, 1_700_000_000_000L)

        val distanza = CalibrationDistance.between(attuale, est)

        assertEquals(77.94, distanza!!.toDouble(), 0.05)
    }

    /** Senza uno dei due punti non si misura niente, non si inventa uno zero. */
    @Test
    fun senzaUnPuntoNonCERicalcolo() {
        assertNull(CalibrationDistance.between(null, bersaglio))
        assertNull(CalibrationDistance.between(attuale, null))
        assertNull(CalibrationDistance.accuracyMeters(null, bersaglio))
        assertNull(CalibrationDistance.accuracyMeters(attuale, null))
    }

    /** Segnare il bersaglio dove si e' appena arrivati da': distanza zero. */
    @Test
    fun stessaPosizioneNonEDistanza() {
        assertNull(CalibrationDistance.between(attuale, attuale.copy()))
    }

    /** Errori indipendenti: quadrati, somma, radice (3-4-5). */
    @Test
    fun accuratezzaInQuadratura() {
        val corrente = GpsFix(45.501, 9.2, 3f, 1_700_000_000_000L)
        val segnato = GpsFix(45.502, 9.2, 4f, 1_700_000_100_000L)

        val totale = CalibrationDistance.accuracyMeters(corrente, segnato)

        assertEquals(5.0, totale!!.toDouble(), 1e-6)
    }
}
