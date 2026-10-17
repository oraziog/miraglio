package com.miraglio.domain.share

import com.miraglio.R
import com.miraglio.domain.location.GpsFix
import com.miraglio.i18n.StringCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il messaggio di condivisione esce dall'app: lo legge chi riceve la foto o il
 * link, e lo legge nella lingua con cui e' stata scritta quella stringa. Qui si
 * controlla che il testo segua la lingua (etichette tradotte, niente righe
 * italiane nel messaggio inglese) e che i dati restino incollabili: le
 * coordinate tengono il punto decimale, il link di Google Maps pure.
 *
 * Le stringhe sono quelle vere dei file di traduzione ([StringCatalog]): un
 * segnaposto sbagliato o una traduzione mancante fa fallire il test, non
 * l'app.
 */
class ShareMessageComposerTest {

    private val italiano = StringCatalog.texts(StringCatalog.ITALIAN)
    private val inglese = StringCatalog.texts(StringCatalog.FALLBACK)

    /** Scatto finto: quota e longitudine tonde, accuratezza di 4,5 m. */
    private val scatto = GpsFix(
        latitudeDeg = 45.5,
        longitudeDeg = 9.2,
        accuracyM = 4.5f,
        timeMs = 1_700_000_000_000L
    )

    @Test
    fun ilMessaggioDellaFotoSegueLaLingua() {
        val messaggioItaliano = foto(italiano)
        val messaggioInglese = foto(inglese)

        assertTrue(
            "manca il titolo italiano: $messaggioItaliano",
            messaggioItaliano.contains("Luogo salvato con Miraglio")
        )
        assertTrue(
            "manca il titolo inglese: $messaggioInglese",
            messaggioInglese.contains("Place saved with Miraglio")
        )

        // I numeri seguono la lingua come le parole: virgola in italiano, punto
        // in inglese (vedi DisplayFormat).
        assertTrue(
            "distanza senza virgola italiana: $messaggioItaliano",
            messaggioItaliano.contains("Distanza: 20,5 m")
        )
        assertTrue(
            "distanza senza punto inglese: $messaggioInglese",
            messaggioInglese.contains("Distance: 20.5 m")
        )

        // Guardia contro la traduzione a meta': le etichette devono cambiare
        // tutte insieme, non una per volta.
        for (parola in PAROLE_ITALIANE) {
            assertFalse(
                "parola italiana \"$parola\" nel messaggio inglese: $messaggioInglese",
                messaggioInglese.contains(parola)
            )
        }
    }

    /**
     * Coordinate e link restano con il punto decimale anche in italiano: sono
     * dati da incollare in un'altra app, non numeri da leggere.
     */
    @Test
    fun leCoordinateRestanoIncollabili() {
        for (testi in listOf(italiano, inglese)) {
            val messaggio = foto(testi)
            assertTrue(
                "coordinate con la virgola: $messaggio",
                messaggio.contains("45.500000, 9.200000")
            )
            assertTrue(
                "manca il link alla mappa: $messaggio",
                messaggio.contains("https://maps.google.com/?q=45.500000,9.200000")
            )
        }

        // L'accuratezza dentro le coordinate segue invece la lingua: e' un
        // valore da leggere, non un indirizzo da incollare.
        assertTrue(
            "accuratezza con la virgola in italiano: ${foto(italiano)}",
            foto(italiano).contains("(±4,5 m)")
        )
        assertTrue(
            "accuratezza col punto in inglese: ${foto(inglese)}",
            foto(inglese).contains("(±4.5 m)")
        )
    }

    /** Senza fix GPS la misura si condivide lo stesso, con l'avviso. */
    @Test
    fun senzaFixIlMessaggioLoDice() {
        val senzaFix = ShareMessageComposer.cameraPlace(
            texts = inglese,
            fix = null,
            bearingDeg = 90f,
            distanceM = 20.5f,
            horizontalDistanceM = 20.5f,
            pitchDeg = 0f
        )

        assertTrue(
            "manca l'avviso di GPS assente: $senzaFix",
            senzaFix.contains("GPS position not available")
        )
        assertFalse(
            "link alla mappa senza fix GPS: $senzaFix",
            senzaFix.contains("maps.google.com")
        )
    }

    /** Bersaglio stimato: compare solo quando c'e' (scatto + bussola + distanza). */
    @Test
    fun ilBersaglioStimatoCompareSoloSeCE() {
        val conBersaglio = ShareMessageComposer.cameraPlace(
            texts = italiano,
            fix = scatto,
            bearingDeg = 0f,
            distanceM = 30f,
            horizontalDistanceM = 30f,
            pitchDeg = 0f,
            target = GpsFix(45.501, 9.2, 5f, 1_700_000_000_000L)
        )

        assertTrue(
            "manca il bersaglio stimato: $conBersaglio",
            conBersaglio.contains("Bersaglio (stimato)")
        )
        assertFalse(
            "bersaglio stimato nel messaggio senza bersaglio: ${foto(italiano)}",
            foto(italiano).contains("Bersaglio (stimato)")
        )
    }

    /**
     * Misura GPS: l'errore stimato sta sempre accanto alla distanza, e sopra la
     * soglia di tolleranza il messaggio lo dichiara anche a chi lo riceve.
     */
    @Test
    fun laMisuraGpsDichiaraLErrore() {
        val precisa = ShareMessageComposer.gpsPlace(italiano, 40f, scatto, scatto, 1f)
        val incerta = ShareMessageComposer.gpsPlace(italiano, 40f, scatto, scatto, 12f)

        assertTrue(
            "manca l'errore stimato: $precisa",
            precisa.contains("errore stimato")
        )
        assertFalse(
            "avviso di precisione limitata su una misura precisa: $precisa",
            precisa.contains("Precisione limitata")
        )
        assertTrue(
            "manca l'avviso di precisione limitata: $incerta",
            incerta.contains("Precisione limitata")
        )
    }

    /**
     * Rosa dei venti a 8 settori: sono gli stessi gradi a cambiare nome con la
     * lingua — ovest e sud-ovest in italiano sono `O` e `SO`, in inglese `W` e
     * `SW`.
     */
    @Test
    fun laBussolaCambiaNomeConLaLingua() {
        assertEquals("O", ShareMessageComposer.compassLabel(italiano, 270f))
        assertEquals("SO", ShareMessageComposer.compassLabel(italiano, 225f))
        assertEquals("NO", ShareMessageComposer.compassLabel(italiano, 315f))

        assertEquals("W", ShareMessageComposer.compassLabel(inglese, 270f))
        assertEquals("SW", ShareMessageComposer.compassLabel(inglese, 225f))
        assertEquals("NW", ShareMessageComposer.compassLabel(inglese, 315f))
    }

    /** Il settore e' quello dei 45° piu' vicini, anche a cavallo dello zero. */
    @Test
    fun ilSettoreSegueIlRilevamento() {
        assertEquals(R.string.compass_n, ShareMessageComposer.compassLabelRes(0f))
        assertEquals(R.string.compass_ne, ShareMessageComposer.compassLabelRes(45f))
        assertEquals(R.string.compass_e, ShareMessageComposer.compassLabelRes(90f))
        assertEquals(R.string.compass_se, ShareMessageComposer.compassLabelRes(135f))
        assertEquals(R.string.compass_s, ShareMessageComposer.compassLabelRes(180f))
        assertEquals(R.string.compass_sw, ShareMessageComposer.compassLabelRes(225f))
        assertEquals(R.string.compass_w, ShareMessageComposer.compassLabelRes(270f))
        assertEquals(R.string.compass_nw, ShareMessageComposer.compassLabelRes(315f))

        // Rilevamenti fuori giro: la bussola li riporta dentro, non esplode.
        assertEquals(R.string.compass_n, ShareMessageComposer.compassLabelRes(359f))
        assertEquals(R.string.compass_n, ShareMessageComposer.compassLabelRes(360f))
        assertEquals(R.string.compass_nw, ShareMessageComposer.compassLabelRes(-45f))
    }

    /** Da 10 m in su l'accuratezza si scrive senza decimali. */
    @Test
    fun lAccuratezzaPerdeIDecimaliQuandoEGrossolana() {
        assertEquals(1, ShareMessageComposer.accuracyDecimals(4.5f))
        assertEquals(1, ShareMessageComposer.accuracyDecimals(9.9f))
        assertEquals(0, ShareMessageComposer.accuracyDecimals(10f))
        assertEquals(0, ShareMessageComposer.accuracyDecimals(35f))
    }

    /** Messaggio di una foto: bersaglio a 20,5 m, mira a 300°. */
    private fun foto(testi: com.miraglio.i18n.Texts): String =
        ShareMessageComposer.cameraPlace(
            texts = testi,
            fix = scatto,
            bearingDeg = 300f,
            distanceM = 20.5f,
            horizontalDistanceM = 20.5f,
            pitchDeg = 2f
        )

    private companion object {
        /** Parole che possono stare solo nel messaggio italiano. */
        val PAROLE_ITALIANE = listOf("Distanza", "Direzione", "Inclinazione", "Da qui", "salvato")
    }
}
