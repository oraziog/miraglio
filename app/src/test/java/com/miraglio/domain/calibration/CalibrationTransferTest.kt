package com.miraglio.domain.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * Il formato di scambio passa per un messaggio o per un QR, cioe' per due posti
 * dove un testo arriva **tagliato o letto male**: qui si prova che quei casi
 * vengano rifiutati invece di entrare nella calibrazione come numeri storti.
 * Una calibrazione sbagliata non fa cadere niente: sposta le frecce.
 */
class CalibrationTransferTest {

    private val esempio = CalibrationBundle(
        device = "Samsung SM-G991B",
        lensCount = 3,
        lenses = listOf(
            TransferLens(
                position = 1,
                points = listOf(
                    TransferPoint(30f, 250.5f, 1.22f, "H", 1f, 1280),
                    TransferPoint(15f, 505f, 1.22f, "H", 2f, 1280),
                    TransferPoint(30f, 240f, 1.22f, "V", 1f, 1280)
                )
            ),
            TransferLens(
                position = 3,
                points = listOf(TransferPoint(30f, 620f, 1.22f, "H", 1f, 1280))
            )
        )
    )

    /**
     * Un export **scritto a mano**, con la somma di controllo ricalcolata qui:
     * serve a provare il controllo sui valori, che la somma non copre.
     */
    private val conDistanzaZero: String = run {
        val json = """{"dev":"Samsung SM-G991B","n":1,"lenses":[{"p":1,"pts":[[0.0,250.0,1.22,"H",1.0,1280]]}]}"""
        "${CalibrationTransfer.PREFIX}:${checksum(json)}:$json"
    }

    /**
     * La somma di controllo come la scrive il formato (CRC32, esadecimale):
     * qui si ricalcola per conto nostro, cosi' il testo di prova e' davvero
     * "valido ma sbagliato" e non dipende da chi produce l'export.
     */
    private fun checksum(json: String): String {
        val crc = CRC32()
        crc.update(json.toByteArray(Charsets.UTF_8))
        return crc.value.toString(16)
    }

    /**
     * Una calibrazione **senza telefono d'origine** non si importa: chi la
     * riceve non saprebbe da quale modello arriva, ed e' l'informazione con cui
     * decide se ha senso sul suo.
     */
    @Test
    fun senzaMarcaEModelloNonEntra() {
        val json = """{"dev":"","n":1,"lenses":[{"p":1,"pts":[[30.0,250.0,1.22,"H",1.0,1280]]}]}"""
        assertNull(CalibrationTransfer.decode("${CalibrationTransfer.PREFIX}:${checksum(json)}:$json"))
    }

    @Test
    fun quelloCheEsceRientra() {
        val testo = CalibrationTransfer.encode(esempio)
        val letto = CalibrationTransfer.decode(testo)

        assertEquals(esempio, letto)
        assertEquals(4, letto?.totalPoints)
        assertEquals("Samsung SM-G991B", letto?.device)
    }

    /** Un carattere cambiato strada facendo: la somma di controllo non torna. */
    @Test
    fun unTestoManomessoNonEntra() {
        val testo = CalibrationTransfer.encode(esempio)
        // Un carattere dentro il JSON: e' quello che cambia un copia-incolla
        // passato per una chat che "aggiusta" il testo.
        val manomesso = testo.replace("Samsung", "Samsxng")

        assertTrue("il test deve cambiare davvero il testo", manomesso != testo)
        assertNull(CalibrationTransfer.decode(manomesso))
    }

    /** Tagliato a meta' (un messaggio troncato, un copia-incolla parziale). */
    @Test
    fun unTestoTagliatoNonEntra() {
        val testo = CalibrationTransfer.encode(esempio)

        assertNull(CalibrationTransfer.decode(testo.substring(0, testo.length / 2)))
        assertNull(CalibrationTransfer.decode(testo.dropLast(1)))
    }

    /** Roba che non e' nostra: un link, un JSON qualsiasi, una frase. */
    @Test
    fun unTestoEstraneoNonEntra() {
        assertNull(CalibrationTransfer.decode(""))
        assertNull(CalibrationTransfer.decode("https://github.com/oraziog/miraglio"))
        assertNull(CalibrationTransfer.decode("""{"dev":"Samsung","n":1,"lenses":[]}"""))
        assertNull(CalibrationTransfer.decode("MIRAGLIO-CAL2:abc:{}"))
    }

    /**
     * Anche con la somma giusta (chi la ricalcola sa farlo) i valori devono
     * poter essere misure: una distanza a zero o un asse inventato non sono una
     * calibrazione.
     */
    @Test
    fun valoriImpossibiliNonEntrano() {
        // Questi testi hanno la somma di controllo RICALCOLATA (li scrive
        // encode): cosi' si prova il controllo sui valori, non il CRC.
        val bundleStorto = esempio.copy(
            lenses = listOf(
                TransferLens(
                    position = 1,
                    points = listOf(TransferPoint(0f, 250f, 1.22f, "H", 1f, 1280))
                )
            )
        )
        assertNull(CalibrationTransfer.decode(conDistanzaZero))

        assertNull(CalibrationTransfer.decode(CalibrationTransfer.encode(bundleStorto)))

        val asseInventato = esempio.copy(
            lenses = listOf(
                TransferLens(
                    position = 1,
                    points = listOf(TransferPoint(30f, 250f, 1.22f, "X", 1f, 1280))
                )
            )
        )
        assertNull(CalibrationTransfer.decode(CalibrationTransfer.encode(asseInventato)))
    }

    /**
     * Un export reale deve entrare in un QR: se superasse la soglia, la
     * schermata manderebbe l'utente sul testo e il QR non servirebbe a niente.
     */
    @Test
    fun unExportTipicoEntraInUnQR() {
        val testo = CalibrationTransfer.encode(esempio)
        assertTrue(
            "export di ${testo.length} caratteri: troppo per un QR",
            testo.length < CalibrationTransfer.MAX_QR_CHARS
        )
    }

    /** Stessa numerazione di lenti: una per una. */
    @Test
    fun leLentiSiMappanoSullaStessaPosizione() {
        val mappa = CalibrationTransfer.mapLenses(esempio, localLensCount = 3)

        assertEquals(listOf(1, 3), mappa.map { it.fromPosition })
        assertEquals(listOf(0, 2), mappa.map { it.localIndex })
        assertEquals(false, mappa.any { it.skipped })
    }

    /**
     * Telefono con MENO lenti: la lente 3 del file qui non esiste. Si salta e
     * si dichiara — appoggiarla alla seconda lente sarebbe una calibrazione
     * giusta scritta sulla lente sbagliata.
     */
    @Test
    fun leLentiCheQuiNonEsistonoSiSaltano() {
        val mappa = CalibrationTransfer.mapLenses(esempio, localLensCount = 2)

        assertEquals(listOf(0, null), mappa.map { it.localIndex })
        assertEquals(true, mappa.last().skipped)
        assertEquals("i punti della lente saltata restano nel file", 1, mappa.last().points.size)
    }

    /** Telefono con PIU' lenti: quelle in piu' non si toccano. */
    @Test
    fun leLentiInPiuRestanoComeSono() {
        val mappa = CalibrationTransfer.mapLenses(esempio, localLensCount = 5)

        assertEquals(listOf(0, 2), mappa.map { it.localIndex })
        assertEquals(2, mappa.size)
    }

    /** Nessuna lente posteriore riconosciuta: si salta tutto, senza inventare. */
    @Test
    fun senzaLentiLocaliNonSiImportaNiente() {
        val mappa = CalibrationTransfer.mapLenses(esempio, localLensCount = 0)

        assertTrue(mappa.all { it.skipped })
    }

    /** Il testo esportato si legge a occhio: prefisso, somma e JSON. */
    @Test
    fun ilTestoHaIlPrefissoEIlJson() {
        val testo = CalibrationTransfer.encode(esempio)

        assertTrue(testo.startsWith("${CalibrationTransfer.PREFIX}:"))
        assertTrue(testo.contains("\"dev\":\"Samsung SM-G991B\""))
        // Spazi e a capo attorno al testo (un incolla li porta sempre) non
        // impediscono la lettura.
        assertNotNull(CalibrationTransfer.decode("\n  $testo  \n"))
    }
}
