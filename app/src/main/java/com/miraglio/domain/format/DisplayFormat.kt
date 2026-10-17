package com.miraglio.domain.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Come si scrivono numeri, date e **lunghezze** a schermo, nella lingua e nel
 * sistema di unita' scelti.
 *
 * La lingua e il sistema di unita' arrivano da fuori (l'activity, una ViewModel,
 * un oggetto di dominio che ha un `Texts`): qui non si legge
 * `Locale.getDefault()`, perche' da Android 13 la lingua la si puo' scegliere
 * per singola app e il valore giusto e' quello della configurazione delle
 * risorse, non quello del telefono (`values-it/` per un telefono italiano,
 * `values/` per tutti gli altri).
 *
 * Cosa cambia con la lingua:
 * - il **separatore decimale**: virgola in italiano e in spagnolo (20,5 m),
 *   punto in inglese (20.5 m);
 * - l'**ordine della data**: giorno/mese/anno in italiano (24/10/2026), mese in
 *   lettere in inglese (Oct 24, 2026) e in spagnolo (24 oct 2026).
 *
 * Cosa cambia con il sistema di unita' ([UnitSystem]):
 * - le **distanze** (bersaglio, percorso, stima) in metri o in **iarde**;
 * - le **dimensioni** di un oggetto in metri/centimetri o in **pollici**;
 * - l'**incertezza** (accuratezza GPS, errore stimato) in metri o in **piedi**.
 *
 * Un'unita' per grandezza, e non un'unita' sola: e' come si parla davvero in
 * sistema imperiale — "il bersaglio e' a 33 iarde, e' alto 48 pollici, il GPS
 * sbaglia di 16 piedi" — mentre un errore da mezzo metro scritto "0,5 iarde"
 * non lo direbbe nessuno.
 *
 * Le conversioni stanno tutte qui e in un verso solo: dentro l'app le lunghezze
 * restano in **metri**. Cambiare sistema di unita' non tocca i conti, le
 * calibrazioni salvate ne' i luoghi memorizzati.
 *
 * Cosa **non** cambia, apposta: il fuso orario delle date e' sempre
 * Europe/Rome, e l'ora e' sempre a 24 ore. Le date qui sono etichette di
 * misure ("misura del …"): un orario che cambia col fuso del telefono
 * mentirebbe, e "14:03" non si confonde con un AM/PM tradotto male.
 *
 * Restano col punto decimale e in formato americano i link alle mappe e le
 * coordinate: sono dati da incollare in altre app, non valori da leggere a
 * schermo (vedi ShareMessageComposer).
 */
class DisplayFormat(
    private val locale: Locale,
    val unitSystem: UnitSystem = UnitSystem.DEFAULT
) {

    /** Lingua normalizzata: `it_IT` e `it` sono la stessa lingua. */
    private val language: String = locale.language.lowercase(Locale.ROOT)

    /**
     * Forma della data nella lingua.
     *
     * Le lingue senza una forma propria ricadono su quella inglese (mese in
     * lettere davanti al giorno): e' una scelta, non una dimenticanza — meglio
     * una data leggibile in una lingua simile che un ordine inventato.
     */
    private val datePattern: String = when (language) {
        "it" -> "dd/MM/yyyy"
        "es" -> "d MMM yyyy"
        else -> "MMM d, yyyy"
    }

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("$datePattern HH:mm", locale)

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(datePattern, locale)

    // ---------------------------------------------------------------- numeri

    /** Numero con [decimals] decimali e il separatore della lingua: "20,50". */
    fun number(value: Number, decimals: Int): String =
        String.format(locale, "%.${decimals}f", value.toDouble())

    /** Numero con il segno anche quando e' positivo: "+1,2" / "-0,4". */
    fun signed(value: Number, decimals: Int = 1): String =
        if (value.toDouble() >= 0.0) "+${number(value, decimals)}" else number(value, decimals)

    // ------------------------------------------------------------- lunghezze

    /** Unita' che il campo "dimensione nota" si aspetta: "cm" o "in". */
    val sizeUnit: String
        get() = if (unitSystem == UnitSystem.METRIC) SIZE_UNIT_METRIC_SMALL else SIZE_UNIT_IMPERIAL

    /** Unita' che il campo "distanza nota" si aspetta: "m" o "yd". */
    val distanceUnit: String
        get() = if (unitSystem == UnitSystem.METRIC) DISTANCE_UNIT_METRIC else DISTANCE_UNIT_IMPERIAL

    /**
     * Distanza (bersaglio, percorso, scarto): "20,5 m" oppure "22,4 yd".
     *
     * Il valore in arrivo e' sempre in metri, il sistema di unita' decide solo
     * come scriverlo.
     */
    fun distance(meters: Number, decimals: Int = 1): String =
        "${number(distanceValue(meters), decimals)} $distanceUnit"

    /**
     * Distanza col segno anche quando e' positiva: "+1,2 m" / "-0,4 m" /
     * "+1,3 yd". E' lo scarto fra due distanze, dove il verso conta.
     */
    fun signedDistance(meters: Number, decimals: Int = 1): String =
        "${signed(distanceValue(meters), decimals)} $distanceUnit"

    /**
     * Dimensione di un oggetto nella forma "grande": "1,22 m" oppure "48,03 in".
     *
     * In metrico sono i **metri**, perche' e' quello che l'app scriveva gia'
     * prima di questa scelta: un bersaglio da 122 cm si legge "1,22 m". Qui non
     * si cambia niente. Sotto il metro c'e' [objectSizeSmall], che parla di
     * centimetri.
     */
    fun objectSize(meters: Number, decimals: Int = 2): String = when (unitSystem) {
        UnitSystem.METRIC -> "${number(meters, decimals)} $SIZE_UNIT_METRIC"
        UnitSystem.IMPERIAL -> "${number(sizeValue(meters), decimals)} $SIZE_UNIT_IMPERIAL"
    }

    /**
     * Dimensione di un oggetto piccola, cioe' sotto il metro: "48,0 cm" oppure
     * "18,9 in".
     *
     * In metrico sono centimetri — "0,48 m" nessuno lo direbbe — e in imperiale
     * pollici come [objectSize], perche' li' l'unita' giusta e' la stessa a
     * qualsiasi grandezza. E' anche l'unita' che il campo si aspetta, quindi
     * quello che si legge e quello che si digita coincidono.
     */
    fun objectSizeSmall(meters: Number, decimals: Int = 1): String =
        "${number(sizeValue(meters), decimals)} $sizeUnit"

    /**
     * Incertezza di una misura (accuratezza GPS, errore stimato): "4,5 m" o
     * "16,4 ft". Il segno `±` resta nella stringa tradotta.
     */
    fun accuracy(meters: Number, decimals: Int = 1): String =
        "${number(accuracyValue(meters), decimals)} ${accuracyUnit()}"

    /**
     * Il valore di [meters] **nell'unita' del campo delle dimensioni**, come
     * numero: 130 (cm) oppure 51,18 (in). Serve a precompilare il campo e a
     * riscriverlo quando si cambia sistema di unita'.
     */
    fun sizeValue(meters: Number): Double = meters.toDouble() / metersPerSizeUnit(unitSystem)

    /**
     * Il valore di [meters] **nell'unita' del campo delle distanze**, come
     * numero: 30 (m) oppure 32,8 (yd).
     */
    fun distanceValue(meters: Number): Double = meters.toDouble() / metersPerDistanceUnit(unitSystem)

    private fun accuracyValue(meters: Number): Double =
        meters.toDouble() / metersPerAccuracyUnit(unitSystem)

    private fun accuracyUnit(): String =
        if (unitSystem == UnitSystem.METRIC) ACCURACY_UNIT_METRIC else ACCURACY_UNIT_IMPERIAL

    // ----------------------------------------------------------------- input

    /**
     * Un valore **digitato** nel campo della dimensione, convertito in metri.
     *
     * [unit] e' il sistema in cui il testo e' stato scritto: normalmente quello
     * in vigore, ma si puo' indicarne un altro per rileggere un campo compilato
     * prima di un cambio di unita' (vedi `sizeValue`).
     *
     * Si accetta la virgola o il punto (`48,03` e `48.03` sono lo stesso
     * numero): sulla tastiera del telefono il separatore e' quello della
     * lingua, ma chi incolla un numero da un'altra app non deve essere
     * costretto a correggerlo. `null` quando non e' un numero positivo.
     */
    fun parseSize(text: String, unit: UnitSystem = unitSystem): Float? =
        parsePositive(text)?.let { it * metersPerSizeUnit(unit).toFloat() }

    /**
     * Un valore **digitato** nel campo della distanza, convertito in metri.
     * Stesse regole di [parseSize] per virgola, punto e sistema di unita'.
     */
    fun parseDistance(text: String, unit: UnitSystem = unitSystem): Float? =
        parsePositive(text)?.let { it * metersPerDistanceUnit(unit).toFloat() }

    /**
     * Lo stesso valore scritto in [from], riscritto nell'unita' in vigore:
     * "130" centimetri diventano "51,2" pollici.
     *
     * Serve quando si cambia sistema di unita' con un campo gia' compilato: il
     * testo nel campo e' la sola traccia di quello che l'utente ha scritto, e
     * lasciarlo com'e' cambierebbe la misura in silenzio ("130" letto in
     * pollici e' un oggetto 2,54 volte piu' grande).
     *
     * Un testo che non e' un numero (campo vuoto, o appena svuotato per
     * scriverne un altro) resta com'e': non c'e' niente da convertire, e
     * riempirlo di zeri sarebbe peggio che lasciarlo vuoto.
     */
    fun rewriteSizeInput(text: String, from: UnitSystem, decimals: Int = 1): String =
        parseSize(text, from)?.let { number(sizeValue(it), decimals) } ?: text

    /** Come [rewriteSizeInput], per il campo della distanza. */
    fun rewriteDistanceInput(text: String, from: UnitSystem, decimals: Int = 1): String =
        parseDistance(text, from)?.let { number(distanceValue(it), decimals) } ?: text

    /**
     * Numero positivo da un testo digitato, virgola o punto.
     *
     * `isFinite` esclude "Infinity" e i valori troppo grandi per stare in un
     * Float: senza, una misura impossibile entrerebbe nei conti come distanza.
     */
    private fun parsePositive(text: String): Float? =
        text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }

    // -------------------------------------------------------------- date e ora

    /** Data e ora di Roma, nella forma della lingua scelta. */
    fun dateTime(epochMs: Long): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ROME))

    /** Solo data, nella forma della lingua scelta. */
    fun date(epochMs: Long): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ROME))

    companion object {

        /** Fuso di riferimento: l'ora mostrata non cambia col telefono. */
        private val ROME: ZoneId = ZoneId.of("Europe/Rome")

        // Fattori di conversione esatti: 1 yd = 0,9144 m, 1 ft = 0,3048 m,
        // 1 in = 2,54 cm. Sono i metri che vale **una** unita' di quel sistema.
        private const val YARD_METERS = 0.9144
        private const val FOOT_METERS = 0.3048
        private const val INCH_METERS = 0.0254

        // Simboli delle unita': non si traducono (in italiano, inglese e
        // spagnolo si scrivono cosi' in tutti e tre), quindi non stanno nelle
        // risorse e non passano da StringsXmlTest.
        private const val DISTANCE_UNIT_METRIC = "m"
        private const val DISTANCE_UNIT_IMPERIAL = "yd"
        private const val ACCURACY_UNIT_METRIC = "m"
        private const val ACCURACY_UNIT_IMPERIAL = "ft"
        private const val SIZE_UNIT_METRIC = "m"
        private const val SIZE_UNIT_METRIC_SMALL = "cm"
        private const val SIZE_UNIT_IMPERIAL = "in"

        private fun metersPerDistanceUnit(unit: UnitSystem): Double =
            if (unit == UnitSystem.METRIC) 1.0 else YARD_METERS

        private fun metersPerSizeUnit(unit: UnitSystem): Double =
            if (unit == UnitSystem.METRIC) 0.01 else INCH_METERS

        private fun metersPerAccuracyUnit(unit: UnitSystem): Double =
            if (unit == UnitSystem.METRIC) 1.0 else FOOT_METERS

        /**
         * Formattatore per una cartella di risorse: `values` e' la lingua di
         * ripiego (inglese), `values-it` l'italiano, `values-es` lo spagnolo.
         * Serve ai test, che leggono i file di traduzione dal disco invece che
         * da Android.
         */
        fun ofResourceFolder(
            folder: String,
            unitSystem: UnitSystem = UnitSystem.DEFAULT
        ): DisplayFormat = DisplayFormat(
            when {
                folder.endsWith("-it") -> Locale.ITALIAN
                folder.endsWith("-es") -> Locale.forLanguageTag("es")
                else -> Locale.ENGLISH
            },
            unitSystem
        )
    }
}
