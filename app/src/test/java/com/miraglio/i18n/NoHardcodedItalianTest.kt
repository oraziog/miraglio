package com.miraglio.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il ripiego di Android e' silenzioso, ma il confronto fra `strings.xml` (vedi
 * [StringsXmlTest]) copre solo i file di traduzione: un testo scritto **dentro
 * il codice** non passa da li' e resta identico in tutte le lingue. Un telefono
 * inglese mostrerebbe "Annulla" in mezzo a un'interfaccia inglese, e nessuna
 * build se ne accorgerebbe.
 *
 * Questo test legge i sorgenti di `src/main/java` come testo e cerca parole
 * italiane nei letterali di stringa. I log sono esclusi (vedi
 * [ItalianSourceScan]): sono per chi sviluppa e in questo progetto sono in
 * italiano apposta.
 */
class NoHardcodedItalianTest {

    /**
     * Testi italiani che restano apposta, perche' non arrivano a schermo:
     * l'etichetta di diagnostica del modello ONNX, che l'interfaccia non
     * disegna (l'unico posto dove compare e' nei log e nella schermata di
     * diagnostica futura). Tutto il resto va nelle risorse.
     */
    private val SOLO_DIAGNOSTICA = mapOf(
        "data/vision/NoOpObjectDetector.kt" to setOf("no-op (modello ONNX non caricato)"),
        "data/vision/YoloObjectDetector.kt" to setOf("no-op (modello ONNX non caricato)")
    )

    @Test
    fun nessunTestoItalianoNelCodice() {
        val trovati = mutableListOf<String>()
        var letterali = 0
        var file = 0

        for (sorgente in ItalianSourceScan.mainSources()) {
            val percorso = sorgente.path.replace('\\', '/').substringAfterLast("/com/miraglio/")
            val ammessi = SOLO_DIAGNOSTICA[percorso].orEmpty()
            file++
            for (letterale in ItalianSourceScan.literals(sorgente.readText())) {
                letterali++
                if (letterale.text in ammessi) continue
                val parole = ItalianSourceScan.italianWords(letterale.text)
                if (parole.isNotEmpty()) {
                    trovati += "$percorso:${letterale.line} $parole\n    \"${letterale.text}\""
                }
            }
        }

        // Se la scansione non trova abbastanza file o letterali, il test sta
        // passando perche' non guarda niente: meglio un fallimento esplicito.
        assertTrue("sorgenti letti: $file", file >= 50)
        assertTrue("letterali esaminati: $letterali", letterali >= 150)

        assertEquals(
            "testi italiani dentro il codice (vanno nelle risorse, vedi Texts):\n" +
                trovati.joinToString("\n"),
            0,
            trovati.size
        )
    }

    /**
     * Lo scanner deve riconoscere l'italiano quando c'e': altrimenti il test
     * sopra sarebbe verde per il motivo sbagliato.
     */
    @Test
    fun loScannerRiconosceItalianoEInglese() {
        assertTrue(
            ItalianSourceScan.italianWords("Annulla").isNotEmpty()
        )
        assertTrue(
            ItalianSourceScan.italianWords("Attendi il fix di partenza").isNotEmpty()
        )
        assertTrue(
            ItalianSourceScan.italianWords("Il file scaricato non c'e' più").isNotEmpty()
        )

        assertEquals(
            emptyList<String>(),
            ItalianSourceScan.italianWords("Enter a valid distance and place the lines")
        )
        assertEquals(
            emptyList<String>(),
            ItalianSourceScan.italianWords("Place saved with Miraglio")
        )
    }

    /**
     * Dentro i commenti e i log l'italiano e' di casa: lo scanner non deve
     * segnalarlo, ne' deve farsi ingannare dagli apostrofi sfuggiti o dalle
     * stringhe concatenate su piu' righe.
     */
    @Test
    fun commentiELogNonContano() {
        val sorgente = """
            // Qui si annulla la misura, perche' serve la partenza
            /* e la distanza? si misura dopo */
            Log.w("TAG", "Errore non bloccante nell'analisi frame")
            Log.i("TAG", "Lenti posteriori: " + elenco.joinToString { l ->
                if (l.isPhysical) "${'$'}{l.id} (fisica di ${'$'}{l.parentId})" else l.id
            })
            val testo = "Cancel"
        """.trimIndent()

        val letterali = ItalianSourceScan.literals(sorgente)

        // Solo "Cancel": le stringhe dentro i due log non si guardano.
        assertEquals("letterali trovati: $letterali", listOf("Cancel"), letterali.map { it.text })
        assertEquals(emptyList<String>(), ItalianSourceScan.italianWords(letterali.single().text))
    }

    /** Ogni letterale porta la riga giusta, altrimenti il messaggio d'errore mente. */
    @Test
    fun ilNumeroDiRigaEQuelloGiusto() {
        val sorgente = """
            val primo = "Cancel"
            val secondo = "Attendi la partenza"
        """.trimIndent()

        assertEquals(
            listOf(1 to "Cancel", 2 to "Attendi la partenza"),
            ItalianSourceScan.literals(sorgente).map { it.line to it.text }
        )
    }
}
