package com.miraglio.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il ripiego di Android e' silenzioso: se una chiave manca in `values-it/` o in
 * `values-es/`, un telefono in quella lingua non se ne accorge e mostra la
 * stringa inglese in mezzo a quelle tradotte. Con 200 e piu' stringhe succede,
 * prima o poi.
 *
 * Questo test tiene ogni traduzione allineata al ripiego senza emulatore: legge
 * gli XML dal disco (vedi [StringCatalog]) e confronta chiavi e segnaposto.
 * Gira su JVM, quindi si vede subito a ogni `./gradlew check`.
 *
 * L'elenco delle traduzioni **non** e' scritto qui: le cartelle si scoprono dal
 * disco ([StringCatalog.translationFolders]). Aggiungere una lingua e' quindi
 * gia' coperto da questo test, che e' esattamente il momento in cui serve.
 *
 * Accoppiato a questo c'e' lint: con `warningsAsErrors` attivo, la regola
 * `MissingTranslation` ferma la build se una stringa del file di ripiego non
 * ha la traduzione.
 */
class StringsXmlTest {

    private val ripiego = StringCatalog.strings(StringCatalog.FALLBACK)
    private val traduzioni = StringCatalog.translationFolders()
    private val perLingua: Map<String, Map<String, StringCatalog.Entry>> =
        traduzioni.associateWith { StringCatalog.strings(it) }

    @Test
    fun ogniTraduzioneHaLeStesseChiaviDelRipiego() {
        for ((cartella, voci) in perLingua) {
            val mancantiNellaTraduzione = ripiego.keys - voci.keys
            val inPiuNellaTraduzione = voci.keys - ripiego.keys

            assertTrue(
                "$cartella: mancano ${mancantiNellaTraduzione.size} chiavi di values/: " +
                    mancantiNellaTraduzione.sorted().joinToString(", "),
                mancantiNellaTraduzione.isEmpty()
            )
            assertTrue(
                "$cartella: ${inPiuNellaTraduzione.size} chiavi che non esistono in values/: " +
                    inPiuNellaTraduzione.sorted().joinToString(", "),
                inPiuNellaTraduzione.isEmpty()
            )
        }
    }

    /**
     * Un segnaposto perso in traduzione non da' errore da nessuna parte: esce
     * cosi' com'e' a schermo ("Distancia: %1$s m" -> "Distancia:  m").
     */
    @Test
    fun ogniTraduzioneHaGliStessiSegnaposto() {
        for ((cartella, voci) in perLingua) {
            for ((nome, inglese) in ripiego) {
                val traduzione = voci[nome] ?: continue
                assertEquals(
                    "segnaposto diversi per <string name=\"$nome\">: " +
                        "values=${inglese.placeholders} $cartella=${traduzione.placeholders}",
                    inglese.placeholders,
                    traduzione.placeholders
                )
            }
        }
    }

    @Test
    fun nessunaStringaVuota() {
        val cartelle = perLingua + (StringCatalog.FALLBACK to ripiego)

        for ((cartella, voci) in cartelle) {
            for ((nome, voce) in voci) {
                assertFalse("$cartella: <string name=\"$nome\"> e' vuota", voce.value.isBlank())
            }
        }
    }

    /**
     * La lingua di ripiego deve essere **inglese davvero**: potrebbe essere
     * corretta (chiavi e segnaposto a posto) e comunque italiana nel testo, e
     * un telefono francese mostrerebbe italiano senza che nessuno se ne
     * accorga. Il controllo e' lo stesso che cerca l'italiano dentro il codice
     * (vedi [ItalianSourceScan]).
     */
    @Test
    fun ilFileDiRipiegoENonContieneItaliano() {
        val italiane = ripiego.filter { (_, voce) ->
            ItalianSourceScan.italianWords(voce.value).isNotEmpty()
        }

        assertEquals(
            "stringhe italiane nel file di ripiego (values/):\n" +
                italiane.map { (nome, voce) -> "  $nome: \"${voce.value}\"" }.joinToString("\n"),
            0,
            italiane.size
        )

        // La traduzione italiana invece parole italiane ne ha: se no il
        // controllo sopra passerebbe anche con il rilevatore rotto.
        val vociItaliane = perLingua.getValue(StringCatalog.ITALIAN)
        assertTrue(
            "il rilevatore non trova italiano in values-it/",
            vociItaliane.values.count { ItalianSourceScan.italianWords(it.value).isNotEmpty() } > 50
        )
    }

    /**
     * Guardia contro il copia-incolla: se qualcuno "traduce" copiando il file
     * inglese in una traduzione (o copiando l'italiano nello spagnolo), le due
     * lingue tornano identiche e una di esse sparisce senza che niente si
     * rompa.
     *
     * Le stringhe uguali fra due lingue sono legittime — "Miraglio", "QR", un
     * simbolo — ma non devono essere la maggioranza.
     */
    @Test
    fun nessunaTraduzioneEUnaCopiaDelRipiegoODiUnAltraLingua() {
        val soglia = ripiego.size / 2

        for ((cartella, voci) in perLingua) {
            val comuniColRipiego = ripiego.count { (nome, voce) -> voci[nome]?.value == voce.value }
            assertTrue(
                "$cartella: solo ${ripiego.size - comuniColRipiego} stringhe su ${ripiego.size} " +
                    "differiscono da values/: sembra una copia dell'inglese",
                comuniColRipiego < soglia
            )

            for ((altra, vociAltre) in perLingua) {
                if (altra == cartella) continue
                val comuniConLAltra = voci.count { (nome, voce) -> vociAltre[nome]?.value == voce.value }
                assertTrue(
                    "$cartella e $altra: solo ${voci.size - comuniConLAltra} stringhe su " +
                        "${voci.size} differiscono: una delle due sembra una copia",
                    comuniConLAltra < soglia
                )
            }
        }
    }
}
