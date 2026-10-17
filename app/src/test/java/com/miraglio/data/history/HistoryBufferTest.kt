package com.miraglio.data.history

import com.miraglio.data.places.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cronologia automatica e' un salvagente: il valore che protegge e' "nessuna
 * misura finisce fuori per un errore nostro". Qui si prova che le regole del
 * buffer facciano cio' che l'utente vede:
 *
 *  - la piu' recente e' sempre in cima (e' la prima riga che si guarda);
 *  - rifinire lo stesso scatto aggiorna la riga invece di riempire il buffer;
 *  - il buffer non cresce per sempre (capo fisso);
 *  - un file corrotto a meta' perde solo la riga rotta, non tutto.
 */
class HistoryBufferTest {

    private fun entry(
        id: String = "id-1",
        atMs: Long = 1_000L,
        kind: PlaceKind = PlaceKind.CAMERA,
        distanceM: Float = 30f,
        errorM: Float? = null
    ) = HistoryEntry(
        id = id,
        atMs = atMs,
        kind = kind,
        distanceM = distanceM,
        errorM = errorM
    )

    @Test
    fun laPiuRecenteVaInTesta() {
        val prima = entry(id = "a", atMs = 1_000L)
        val seconda = entry(id = "b", atMs = 200_000L)

        val buffer = HistoryBuffer.push(HistoryBuffer.push(emptyList(), prima), seconda)

        assertEquals(listOf("b", "a"), buffer.map { it.id })
    }

    /**
     * Un fermo immagine che si ricompone piu' volte entro la finestra e' la
     * stessa misura che si affina: la riga si aggiorna. Se ogni prova avesse
     * creato una riga, dieci scatti dello stesso bersaglio avrebbero buttato
     * fuori il buffer dieci misure vere.
     */
    @Test
    fun loScattoCheSiRifinisceSostituisceLaTesta() {
        val scatto = entry(id = "scatto", atMs = 10_000L, distanceM = 30f)
        val rifinito = entry(id = "rifinito", atMs = 10_000L + HistoryBuffer.REPLACE_WINDOW_MS, distanceM = 29.5f)

        val buffer = HistoryBuffer.push(HistoryBuffer.push(emptyList(), scatto), rifinito)

        assertEquals(listOf("rifinito"), buffer.map { it.id })
        assertEquals("il buffer non deve contenere due righe dello stesso scatto", 1, buffer.size)
    }

    /** Un tipo diverso e' un'altra misura, anche arrivata pochi secondi dopo. */
    @Test
    fun UnTipoDiversoNonSostituisceLaTesta() {
        val camera = entry(id = "foto", atMs = 10_000L, kind = PlaceKind.CAMERA)
        val gps = entry(id = "camminata", atMs = 10_500L, kind = PlaceKind.GPS, distanceM = 42f)

        val buffer = HistoryBuffer.push(HistoryBuffer.push(emptyList(), camera), gps)

        assertEquals(listOf("camminata", "foto"), buffer.map { it.id })
    }

    /** Oltre la finestra e' un'altra misura, dello stesso tipo che sia. */
    @Test
    fun DopoLaFinestraLaMisuraENuova() {
        val prima = entry(id = "prima", atMs = 10_000L)
        val dopo = entry(id = "dopo", atMs = 10_000L + HistoryBuffer.REPLACE_WINDOW_MS + 1)

        val buffer = HistoryBuffer.push(HistoryBuffer.push(emptyList(), prima), dopo)

        assertEquals(listOf("dopo", "prima"), buffer.map { it.id })
    }

    /** Capo fisso: le piu' vecchie escono per sempre (e' un salvagente). */
    @Test
    fun IlBufferSiTagliaAllaCapienza() {
        var buffer = emptyList<HistoryEntry>()
        for (i in 0 until HistoryBuffer.MAX_ENTRIES + 5) {
            // Un minuto fra un'entry e l'altra: ognuna e' nuova (fuori dalla
            // finestra di sostituzione), cosi' si riempie davvero.
            buffer = HistoryBuffer.push(buffer, entry(id = "m$i", atMs = 1_000L + i * 60_000L))
        }

        assertEquals(HistoryBuffer.MAX_ENTRIES, buffer.size)
        assertEquals("in testa resta la piu' recente", "m14", buffer.first().id)
        assertEquals("fuori la piu' vecchia", "m5", buffer.last().id)
    }

    /** Il giro completo: quello che si scrive e' quello che si rilegge. */
    @Test
    fun IlGiroComplettoJsonNonPerdeNiente() {
        val originale = listOf(
            entry(id = "a", atMs = 5_000L, kind = PlaceKind.GPS, distanceM = 42.5f, errorM = 4f),
            entry(id = "b", atMs = 4_000L, kind = PlaceKind.CAMERA, distanceM = 29.75f)
        )

        val riletto = HistoryBuffer.fromJson(HistoryBuffer.toJson(originale))

        assertEquals(originale, riletto)
    }

    /** Un file corrotto per strada perde la riga rotta, non le altre. */
    @Test
    fun UnFileConUnaRigaRottaConservaQuelleBuone() {
        val conRigaRotta = """
            [
              {"id":"a","at":1000,"kind":"GPS","dist":42.5,"err":4.0},
              "questa non e' una riga",
              {"id":"c","at":3000,"kind":"CAMERA","dist":0}
            ]
        """.trimIndent()

        val rilette = HistoryBuffer.fromJson(conRigaRotta)

        assertEquals(listOf("a"), rilette.map { it.id })
        assertEquals(42.5f, rilette.first().distanceM, 0f)
        assertEquals(4f, rilette.first().errorM!!, 0f)
    }

    /** Testo che non e' affatto un archivio (o file tagliato a meta'). */
    @Test
    fun UnFileEstraneoRestituisceUnBufferVuoto() {
        assertTrue(HistoryBuffer.fromJson("").isEmpty())
        assertTrue(HistoryBuffer.fromJson("{non e' un archivio").isEmpty())
        assertTrue(HistoryBuffer.fromJson("[{\"id\":\"a\"").isEmpty())
    }
}
