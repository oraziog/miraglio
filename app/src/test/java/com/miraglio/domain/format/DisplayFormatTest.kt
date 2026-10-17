package com.miraglio.domain.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Numeri, date e lunghezze seguono la lingua **e** il sistema di unita' scelti,
 * non piu' `Locale.ITALY` fisso e i metri per forza.
 *
 * E' la differenza che si vede a schermo: "20,5 m" su un telefono italiano e
 * "20.5 m" su uno inglese, "24/10/2026" contro "Oct 24, 2026"; e, con le unita'
 * imperiali, lo stesso bersaglio a 30 metri diventa "33 yd" e alto 48 "in". Un
 * valore scritto con la virgola dentro un'interfaccia inglese, o in metri per
 * chi ha chiesto le iarde, e' lo stesso difetto di una parola non tradotta.
 *
 * Sono test JVM: qui non c'e' Android, solo `java.time` e `java.util.Locale`.
 */
class DisplayFormatTest {

    private val italiano = DisplayFormat(Locale.ITALIAN)
    private val inglese = DisplayFormat(Locale.ENGLISH)

    /** La lingua puo' arrivare completa (`it_IT`): conta solo la lingua. */
    private val italianoDellItalia = DisplayFormat(Locale.forLanguageTag("it-IT"))

    private val italianoImperiale = DisplayFormat(Locale.ITALIAN, UnitSystem.IMPERIAL)
    private val ingleseImperiale = DisplayFormat(Locale.ENGLISH, UnitSystem.IMPERIAL)

    @Test
    fun ilSeparatoreDecimaleSegueLaLingua() {
        assertEquals("20,50", italiano.number(20.5, 2))
        assertEquals("20.50", inglese.number(20.5, 2))

        assertEquals("20,5 m", italiano.distance(20.5))
        assertEquals("20.5 m", inglese.distance(20.5))
    }

    @Test
    fun leMigliaiaNonCambianoForma() {
        // Nessun separatore di migliaia: sono misure, non importi.
        assertEquals("1234,0 m", italiano.distance(1234f))
        assertEquals("1234.0 m", inglese.distance(1234f))
    }

    @Test
    fun ilSegnoSiVedeSoloSePositivo() {
        assertEquals("+1,2", italiano.signed(1.2f))
        assertEquals("-0,4", italiano.signed(-0.4f))
        assertEquals("+1.2", inglese.signed(1.2f))
        assertEquals("-0.4", inglese.signed(-0.4f))
    }

    @Test
    fun laDataCambiaOrdineConLaLingua() {
        assertEquals("24/10/2026", italiano.date(ISTANTE))
        assertEquals("Oct 24, 2026", inglese.date(ISTANTE))
        assertNotEquals(italiano.date(ISTANTE), inglese.date(ISTANTE))
    }

    @Test
    fun laDataConOraCambiaConLaLingua() {
        assertEquals("24/10/2026 14:03", italiano.dateTime(ISTANTE))
        assertEquals("Oct 24, 2026 14:03", inglese.dateTime(ISTANTE))
    }

    /**
     * Il fuso resta Roma: e' una scelta del progetto (le date sono etichette di
     * misure, un orario che cambia col fuso del telefono mentirebbe), non un
     * effetto collaterale della lingua. Un'ora invernale e una estiva, cosi' si
     * vede anche il cambio d'ora.
     */
    @Test
    fun lOraRestaQuellaDiRoma() {
        assertEquals("01/01/2026 01:00", italiano.dateTime(1_767_225_600_000L))
        assertEquals("01/07/2026 02:00", italiano.dateTime(1_782_864_000_000L))
    }

    /** `it_IT` e `it` scrivono allo stesso modo: nessuna differenza di regione. */
    @Test
    fun laRegioneNonCambiaIlFormato() {
        assertEquals(italiano.distance(20.5), italianoDellItalia.distance(20.5))
        assertEquals(italiano.date(ISTANTE), italianoDellItalia.date(ISTANTE))
    }

    /**
     * Una lingua senza una forma propria ricade sulla data di ripiego (mese in
     * lettere), ma il separatore decimale resta il **suo**: la parte numerica
     * non si inventa, quella la sa ogni lingua.
     */
    @Test
    fun leAltreLingueUsanoLaDataDiRipiegoETengonoIlLoroSeparatore() {
        val tedesco = DisplayFormat(Locale.GERMAN)

        assertEquals("20,5 m", tedesco.distance(20.5))

        val data = tedesco.date(ISTANTE)
        assertTrue("data inattesa: $data", data.contains("2026"))
        assertNotEquals(
            "la lingua tedesca usa la stessa data dell'italiano: $data",
            italiano.date(ISTANTE),
            data
        )
    }

    /**
     * Lo spagnolo scrive il giorno prima del mese, come l'italiano, ma non con
     * la barra: "24 oct 2026". E' la forma della lingua, non una variante
     * dell'inglese.
     */
    @Test
    fun loSpagnoloUsaLaSuaData() {
        val spagnolo = DisplayFormat(Locale.forLanguageTag("es"))

        val data = spagnolo.date(ISTANTE)
        assertTrue("data inattesa: $data", data.startsWith("24"))
        assertTrue("data inattesa: $data", data.contains("2026"))
        assertNotEquals("lo spagnolo usa la data dell'inglese", inglese.date(ISTANTE), data)

        assertEquals("20,5 m", spagnolo.distance(20.5))
    }

    /**
     * La cartella di risorse decide la lingua: `values` inglese, `values-it`
     * italiano, `values-es` spagnolo. Se questa mappatura mancasse, un test che
     * legge la traduzione spagnola verificherebbe i **numeri inglesi**: verde
     * per il motivo sbagliato.
     */
    @Test
    fun laCartellaDiRisorseSceglieLaLingua() {
        assertEquals("20,5 m", DisplayFormat.ofResourceFolder("values-it").distance(20.5))
        assertEquals("20,5 m", DisplayFormat.ofResourceFolder("values-es").distance(20.5))
        assertEquals("20.5 m", DisplayFormat.ofResourceFolder("values").distance(20.5))
    }

    // ------------------------------------------------------- sistema metrico

    /**
     * Il valore di partenza e' metrico, ed e' **esattamente** quello che l'app
     * scriveva prima che il sistema di unita' esistesse: chi aggiorna non deve
     * vedere numeri diversi da ieri.
     */
    @Test
    fun ilSistemaDiPartenzaEMetricoEScriveComePrima() {
        assertEquals(UnitSystem.METRIC, UnitSystem.DEFAULT)
        assertEquals(UnitSystem.METRIC, DisplayFormat(Locale.ITALIAN).unitSystem)

        assertEquals("m", italiano.distanceUnit)
        assertEquals("cm", italiano.sizeUnit)
        assertEquals("30,0 m", italiano.distance(30f))
        assertEquals("1,22 m", italiano.objectSize(1.22f))
        assertEquals("122,0 cm", italiano.objectSizeSmall(1.22f))
        assertEquals("4,5 m", italiano.accuracy(4.5f))
    }

    // ------------------------------------------------------ sistema imperiale

    /**
     * Le distanze passano alle **iarde**: 30 m sono 32,8 yd, 100 m sono 109,4 yd.
     * Il numero cambia, la misura no: e' sempre lo stesso bersaglio.
     */
    @Test
    fun leDistanzePassanoAlleIarde() {
        assertEquals("32,8 yd", italianoImperiale.distance(30f))
        assertEquals("32.8 yd", ingleseImperiale.distance(30f))
        assertEquals("yd", italianoImperiale.distanceUnit)

        // 100 m = 109,3613… yd: l'arrotondamento e' a un decimale, come in metri.
        assertEquals("109,4 yd", italianoImperiale.distance(100f))

        // 0,9144 m sono esattamente una iarda: qui la conversione si vede tonda.
        assertEquals("1.0 yd", ingleseImperiale.distance(0.9144f))
    }

    /**
     * Le dimensioni passano ai **pollici**: un bersaglio da 122 cm e' alto 48
     * pollici, uno da 48 cm e' alto 18,9 pollici. In imperiale l'unita' e' la
     * stessa sopra e sotto il metro, quindi le due forme coincidono.
     */
    @Test
    fun leDimensioniPassanoAiPollici() {
        assertEquals("48,03 in", italianoImperiale.objectSize(1.22f))
        assertEquals("18,9 in", italianoImperiale.objectSizeSmall(0.48f))
        assertEquals("in", italianoImperiale.sizeUnit)

        // Sotto il metro il metrico cambia forma (metri -> centimetri), la
        // conversione imperiale no: e' la stessa unita'.
        assertEquals("48,0 cm", italiano.objectSizeSmall(0.48f))
        assertEquals("0,48 m", italiano.objectSize(0.48f))
    }

    /**
     * L'incertezza passa ai **piedi**: un'accuratezza di 5 m e' ±16,4 ft. Un
     * errore da mezzo metro non si scrive "0,5 yd" — non lo direbbe nessuno.
     */
    @Test
    fun lIncertezzaPassaAiPiedi() {
        assertEquals("16,4 ft", italianoImperiale.accuracy(5f))
        assertEquals("0.5 ft", ingleseImperiale.accuracy(0.1524f))
        assertEquals("5,0 m", italiano.accuracy(5f))
    }

    /**
     * Lo scarto fra la stima e la misura GPS porta il segno **e** l'unita': e'
     * l'unico numero dove il verso conta, e non deve restare senza unita'.
     */
    @Test
    fun loScartoPortaSegnoEUnita() {
        assertEquals("+1,2 m", italiano.signedDistance(1.2f))
        assertEquals("-0,4 m", italiano.signedDistance(-0.4f))
        assertEquals("+2,0 yd", italianoImperiale.signedDistance(1.8288f))
    }

    // ------------------------------------------------------------ i campi

    /**
     * Il campo dice in che unita' vuole il numero, e il numero che ci si scrive
     * torna in metri. E' il giro completo: quello che l'utente digita e quello
     * che legge devono essere la stessa unita'.
     */
    @Test
    fun iValoriDigitatiTornanoInMetri() {
        // Metrico: centimetri nel campo dimensione, metri in quello distanza.
        assertEquals(1.22f, italiano.parseSize("122")!!, 0.0001f)
        assertEquals(1.22f, italiano.parseSize("122,0")!!, 0.0001f)
        assertEquals(1.22f, italiano.parseSize("122.0")!!, 0.0001f)
        assertEquals(30f, italiano.parseDistance("30")!!, 0.0001f)

        // Imperiale: pollici e iarde.
        assertEquals(1.2192f, ingleseImperiale.parseSize("48")!!, 0.0001f)
        assertEquals(30.1752f, ingleseImperiale.parseDistance("33")!!, 0.0001f)
    }

    /**
     * Testo che non e' un numero positivo: `null`. E' la stessa risposta per il
     * campo vuoto, per le lettere e per lo zero, e serve a decidere se il campo
     * e' compilato senza duplicare la regola in ogni schermata.
     */
    @Test
    fun unTestoNonNumericoNonEUnValore() {
        assertNull(italiano.parseSize(""))
        assertNull(italiano.parseSize("   "))
        assertNull(italiano.parseSize("alto"))
        assertNull(italiano.parseSize("0"))
        assertNull(italiano.parseSize("-48"))
        assertNull(italiano.parseDistance("Infinity"))
    }

    /**
     * Il numero da mettere in un campo, a partire da una misura in metri: 1,30 m
     * e' "130" in centimetri e "51" in pollici (51,18, arrotondato a zero
     * decimali come si precompila un campo).
     *
     * E' quello che serve per non lasciare "130" nel campo quando si passa alle
     * iarde: verrebbe letto come 130 pollici, cioe' un bersaglio 2,54 volte piu'
     * grande, in silenzio.
     */
    @Test
    fun ilValoreDelCampoSiScriveNellUnitaGiusta() {
        assertEquals("130", italiano.number(italiano.sizeValue(1.30f), 0))
        assertEquals("51", ingleseImperiale.number(ingleseImperiale.sizeValue(1.30f), 0))

        assertEquals("30", italiano.number(italiano.distanceValue(30f), 0))
        assertEquals("33", ingleseImperiale.number(ingleseImperiale.distanceValue(30f), 0))
    }

    /** Un valore scritto in un sistema si rilegge nell'altro senza perdere la misura. */
    @Test
    fun ilValoreScrittoInUnSistemaSiRileggeNellAltro() {
        // "130" cm letti in metrico sono 1,30 m, che in pollici sono 51,2.
        val metri = ingleseImperiale.parseSize("130", UnitSystem.METRIC)!!
        assertEquals("51.2", ingleseImperiale.number(ingleseImperiale.sizeValue(metri), 1))

        // E al contrario: "48" in sono 1,2192 m, cioe' 121,9 cm.
        val pollici = italiano.parseSize("48", UnitSystem.IMPERIAL)!!
        assertEquals("121,9", italiano.number(italiano.sizeValue(pollici), 1))
    }

    /**
     * Il campo gia' compilato, riscritto quando si cambia sistema di unita': e' la
     * funzione che le schermate chiamano al cambio, quindi qui si prova il
     * comportamento vero e non solo le due conversioni separate.
     */
    @Test
    fun ilCampoGiaCompilatoVieneRiscritto() {
        // 130 cm in pollici sono 51,2: lo stesso paglione, scritto alla maniera
        // di chi misura in iarde.
        assertEquals("51,2", italianoImperiale.rewriteSizeInput("130", UnitSystem.METRIC))
        // E il contrario: 48 in sono 121,9 cm.
        assertEquals("121,9", italiano.rewriteSizeInput("48", UnitSystem.IMPERIAL))
        // Le distanze allo stesso modo: 30 m sono 32,8 yd.
        assertEquals("32,8", italianoImperiale.rewriteDistanceInput("30", UnitSystem.METRIC))
        assertEquals("30,0", italiano.rewriteDistanceInput("32,8", UnitSystem.IMPERIAL))
    }

    /**
     * Un campo vuoto o con dentro qualcosa che non e' un numero resta com'e':
     * non c'e' niente da convertire, e riempirlo di zeri sarebbe peggio.
     */
    @Test
    fun unCampoNonNumericoNonVieneRiscritto() {
        assertEquals("", italianoImperiale.rewriteSizeInput("", UnitSystem.METRIC))
        assertEquals("alto", italianoImperiale.rewriteSizeInput("alto", UnitSystem.METRIC))
        assertEquals("", italianoImperiale.rewriteDistanceInput("", UnitSystem.METRIC))
    }

    /**
     * Un sistema di unita' salvato in un modo inatteso (preferenze vecchie, o
     * scritte a mano) non deve lasciare l'app senza unita': si torna al
     * metrico.
     */
    @Test
    fun unSistemaSconosciutoTornaAlMetrico() {
        assertEquals(UnitSystem.METRIC, UnitSystem.of(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.of(""))
        assertEquals(UnitSystem.METRIC, UnitSystem.of("yards"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.of("imperial"))
        assertEquals(UnitSystem.METRIC, UnitSystem.of(UnitSystem.METRIC.stored))
    }

    private companion object {
        /** 24 ottobre 2026, 12:03 UTC: a Roma sono le 14:03 (ora legale). */
        const val ISTANTE = 1_792_843_380_000L
    }
}
