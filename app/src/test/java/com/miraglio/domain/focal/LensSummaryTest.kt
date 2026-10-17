package com.miraglio.domain.focal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Il riepilogo della calibrazione decide **cosa vede l'utente** nella card:
 * quali lenti compaiono, in che ordine, e con quale numero. Un ordine sbagliato
 * non rompe niente a schermo — e' proprio per questo che va provato: la prima
 * riga deve essere la lente che si sta calibrando, altrimenti il riepilogo
 * risponde a una domanda diversa da quella che l'utente ha fatto.
 */
class LensSummaryTest {

    private val treLenti = listOf("0", "1", "2")

    @Test
    fun laLenteAttivaStaInCimaQualunqueSiaIlNumeroDiPunti() {
        // Attiva con MENO punti di tutte: l'ordine per punti decrescenti la
        // metterebbe in fondo, quindi questo caso dice che il criterio della
        // lente attiva viene prima di quello dei punti.
        val conMenoPunti = lensSummaryWithPoints(
            pointsByLens = mapOf("0" to 5, "1" to 4, "2" to 1),
            activeLens = "2",
            lensIdsInOrder = treLenti
        )
        assertEquals(listOf("2", "0", "1"), conMenoPunti.map { it.lens })
        assertEquals("la lente attiva e' segnalata", true, conMenoPunti.first().isActive)
        assertEquals("le altre no", false, conMenoPunti.drop(1).any { it.isActive })

        // Attiva con un numero di punti IN MEZZO: e' il caso che smaschera sia
        // l'ordine per punti crescenti sia quello decrescente, perche' la riga
        // in cima non puo' essere arrivata li' ne' per l'uno ne' per l'altro.
        // Con l'attiva che ha il minimo o il massimo dei punti il difetto
        // passerebbe inosservato: l'ordine sbagliato le capiterebbe per caso
        // addosso a quella giusta (verificato: una mutazione dell'ordinamento
        // faceva fallire un solo test su otto, e non era questo).
        val inMezzo = lensSummaryWithPoints(
            pointsByLens = mapOf("0" to 5, "1" to 3, "2" to 2),
            activeLens = "1",
            lensIdsInOrder = treLenti
        )
        assertEquals(listOf("1", "0", "2"), inMezzo.map { it.lens })
        assertEquals(true, inMezzo.first().isActive)
    }

    @Test
    fun leLentiSenzaPuntiNonCompaiono() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("0" to 2, "1" to 0),
            activeLens = "0",
            lensIdsInOrder = treLenti
        )

        assertEquals(listOf("0"), righe.map { it.lens })
    }

    @Test
    fun aPariPuntiSiOrdinaNellOrdineDelleLentiDelDispositivo() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("2" to 3, "0" to 3, "1" to 3),
            activeLens = null,
            lensIdsInOrder = treLenti
        )

        assertEquals(listOf("0", "1", "2"), righe.map { it.lens })
        assertEquals(listOf(1, 2, 3), righe.map { it.position })
    }

    @Test
    fun piuPuntiVienePrimaDellaLenteConMeno() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("0" to 1, "1" to 4, "2" to 2),
            activeLens = null,
            lensIdsInOrder = treLenti
        )

        assertEquals(listOf("1", "2", "0"), righe.map { it.lens })
    }

    @Test
    fun laNumerazioneEQuellaDelleLentiPosteriori() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("2" to 1),
            activeLens = "2",
            lensIdsInOrder = treLenti
        )

        val riga = righe.single()
        assertEquals("terza lente su tre", 3, riga.position)
        assertEquals(3, riga.lensCount)
    }

    /**
     * Dati rimasti da un telefono precedente: la lente non esiste piu'. La riga
     * resta (i punti ci sono ancora, e vanno poter essere cancellati) ma senza
     * posizione: numerarla 0 o 1 sarebbe un numero falso.
     */
    @Test
    fun unaLenteCheNonEsistePiuHaPosizioneNulla() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("fisica-vecchia" to 2),
            activeLens = "fisica-vecchia",
            lensIdsInOrder = treLenti
        )

        val riga = righe.single()
        assertNull(riga.position)
        assertEquals(3, riga.lensCount)
        assertEquals("fisica-vecchia", riga.lens)
        assertEquals(true, riga.isActive)
    }

    @Test
    fun senzaPuntiNonCEPeroNessunaRiga() {
        assertEquals(
            emptyList<LensSummary>(),
            lensSummaryWithPoints(
                pointsByLens = emptyMap(),
                activeLens = "0",
                lensIdsInOrder = treLenti
            )
        )
    }

    /**
     * Nessuna lente conosciuta (dispositivo senza posteriori elencabili, o
     * lettura fallita): i punti si mostrano lo stesso, con l'id grezzo.
     */
    @Test
    fun senzaElencoDiLentiLeRigheRestano() {
        val righe = lensSummaryWithPoints(
            pointsByLens = mapOf("0" to 3, "1" to 1, "2" to 2),
            activeLens = "1",
            lensIdsInOrder = emptyList()
        )

        assertEquals(listOf("1", "0", "2"), righe.map { it.lens })
        assertEquals(listOf(null, null, null), righe.map { it.position })
        assertEquals(listOf(0, 0, 0), righe.map { it.lensCount })
    }
}
