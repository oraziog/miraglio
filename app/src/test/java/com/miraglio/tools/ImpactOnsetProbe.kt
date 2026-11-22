package com.miraglio.tools

import com.miraglio.domain.audio.Biquad
import com.miraglio.domain.audio.ImpactPlausibility
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Strumento di verifica, non di produzione: misura **quando comincia** l'attacco
 * dell'impatto invece di quando l'inviluppo lo+mid tocca il suo massimo.
 *
 * Le due cose non coincidono: il massimo cade sempre qualche decina di
 * millisecondo dopo il fronte d'onda, ed è per questo che la banda del profilo
 * — che è un tempo di volo — ha bisogno della tolleranza di coda
 * `BAND_PEAK_LAG_S`. Da queste misure è nata la regola della macchina: un
 * attacco il cui fronte (metà salita) cade nella banda resta verde anche se il
 * massimo la sfora; la tolleranza resta per i picchi che non sono attacchi e
 * per i transienti a banda larga, dove il fronte non si conosce.
 *
 * Qui non si tocca la pipeline: si fa passare ogni registrazione per il motore
 * vero ([ShotAudioProbe]) per sapere scocco e impatto scelto, e poi si guarda
 * l'inviluppo grezzo attorno a quel picco per trovare il fronte di salita.
 * Tre definizioni di "inizio", perché la scelta non è neutra:
 *
 * - `min`: il minimo dell'inviluppo nei 60 ms prima del picco, cioè la stessa
 *   finestra con cui [com.miraglio.domain.audio.BroadbandPeakDetector] decide
 *   se un picco è un attacco;
 * - `metà`: l'ultimo campione sotto metà del picco (il punto di mezza salita);
 * - `10%`: il primo campione che supera il minimo più un decimo della salita.
 *
 * ```
 * ./gradlew :app:analyzeImpactOnset
 * ```
 */
object ImpactOnsetProbe {

    private const val ENVELOPE_MS = 5.0
    private const val RISE_MS = 60.0

    /** Un caso di prova: come lo misura `RealRecordingTest`, più il riferimento a mano. */
    private data class Caso(
        val nome: String,
        val file: String,
        val clicker: Boolean,
        /** Il Δ misurato a mano, che è la fine dell'impatto, non il suo inizio. */
        val atteso: Double,
        val banda: ClosedFloatingPointRange<Float>?,
        val nota: String = "",
        val modello: ArrowSpeedModel? = null
    )

    private fun banda(vf: Float, decay: Float, dmin: Float, dmax: Float) =
        ImpactPlausibility.band(ArrowSpeedModel(vf, decay), listOf(dmin, dmax), airTempC = 20f)

    private val CASI = listOf(
        Caso("12.32 #16", "tiri_1232_16.wav", false, 0.431, banda(65.6f, 0.0150f, 5f, 18f), "campo 5-18 m", ArrowSpeedModel(65.6f, 0.0150f)),
        Caso("12.32 #17", "tiri_1232_17.wav", false, 0.419, banda(65.6f, 0.0150f, 5f, 18f), "campo 5-18 m", ArrowSpeedModel(65.6f, 0.0150f)),
        Caso("70 m #3", "tiro_25_31.wav", true, 1.438, banda(65f, 0.002f, 65f, 75f), "voce sopra", ArrowSpeedModel(65f, 0.002f)),
        Caso("70 m #11", "tiro_141_65.wav", true, 1.442, banda(65f, 0.002f, 65f, 75f), "voce sopra", ArrowSpeedModel(65f, 0.002f)),
        Caso("70 m #18", "tiro_235_95.wav", true, 1.435, banda(65f, 0.002f, 65f, 75f), "voce sopra", ArrowSpeedModel(65f, 0.002f)),
        Caso("barebow 18 m", "barebow_18m_849.wav", false, 0.418, banda(51f, 0.002f, 16f, 30f), "trigger grave muto", ArrowSpeedModel(51f, 0.002f)),
        Caso("compound 51 m", "compound_51m_1540.wav", false, 0.787, banda(86f, 0.001f, 15f, 90f), "impatto sepolto", ArrowSpeedModel(86f, 0.001f)),
        Caso("18 m mic 1 m #1", "voleo_18m_51.wav", false, 0.293, null, "nessun modello"),
        Caso("18 m mic 1 m #10", "voleo_18m_368.wav", false, 0.338, null, "impatto debole"),
        Caso("3 m #1", "tiri_3m_76.wav", false, 0.353, null, "coda del rilascio"),
        Caso("3 m #3", "tiri_3m_141.wav", false, 0.361, null, "coda del rilascio")
    )

    private data class Riga(
        val caso: Caso,
        val scoccoS: Double,
        val deltaPicco: Double,
        val gapMinMs: Double,
        val gapMetaMs: Double,
        val gapDieciMs: Double,
        val salita: Double,
        val inBandaPicco: Boolean?,
        val inBandaMin: Boolean?,
        val inBandaMeta: Boolean?,
        val codaMs: Double?,
        /** Lo stesso conto fatto anche sullo scocco: quanto il SUO picco segue il fronte. */
        val gapScoccoMinMs: Double,
        val gapScoccoMetaMs: Double,
        /** Δ misurato da fronte a fronte invece che da picco a picco. */
        val deltaFronteFronteMin: Double,
        val deltaFronteFronteMeta: Double,
        /** Distanze dal modello: picco, minimo, metà salita, 10% (vuote senza modello). */
        val distanze: List<String>
    ) {
        val deltaMin: Double get() = deltaPicco - gapMinMs / 1000.0
        val deltaMeta: Double get() = deltaPicco - gapMetaMs / 1000.0
        val deltaDieci: Double get() = deltaPicco - gapDieciMs / 1000.0
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val primo = args.getOrNull(0)
        // Con un file si misura la registrazione intera: tutti i quaranta tiri
        // a 70 m, non le finestre ritagliate.
        if (primo != null && File(primo).isFile) {
            registrazioneCompleta(File(primo))
            return
        }
        val cartella = File(primo ?: "src/test/resources/audio")
        require(cartella.isDirectory) { "non esiste la cartella $cartella" }
        println("Inizio dell'attacco contro massimo dell'inviluppo, sulle registrazioni vere")
        println("inviluppo: RMS ${ENVELOPE_MS.toInt()} ms delle bande 120-900 e 1500-8000 Hz, come il rilevatore")
        println("inizio: minimo nei ${RISE_MS.toInt()} ms prima del picco / mezza salita / 10% della salita")
        println()

        val righe = CASI.map { caso ->
            val file = File(cartella, caso.file)
            require(file.isFile) { "manca ${file.absolutePath}" }
            analizza(caso, file)
        }

        println(
            "%-16s %8s %8s %8s %8s %8s %7s  %-18s %s".format(
                Locale.ROOT, "caso", "rif[s]", "picco", "min[ms]", "meta[ms]", "dieci[ms]", "salita",
                "banda[s]", "in banda picco/min/meta"
            )
        )
        righe.forEach { r ->
            println(
                "%-16s %8.3f %8.3f %8.0f %8.0f %8.0f %6.1fx  %-18s %s".format(
                    Locale.ROOT,
                    r.caso.nome,
                    r.caso.atteso,
                    r.deltaPicco,
                    r.gapMinMs,
                    r.gapMetaMs,
                    r.gapDieciMs,
                    r.salita,
                    r.caso.banda?.let { "%.3f..%.3f".format(Locale.ROOT, it.start, it.endInclusive) } ?: "-",
                    elenco(r)
                )
            )
        }

        println()
        println("Ritardo del picco sull'inizio dell'attacco (minimo nei 60 ms):")
        righe.forEach { r ->
            println(
                "  %-16s picco %+.0f ms dall'inizio  (%s%s)".format(
                    Locale.ROOT,
                    r.caso.nome,
                    r.gapMinMs,
                    if (r.codaMs != null) "picco oltre la banda di %.0f ms" .format(Locale.ROOT, r.codaMs) else "nessuna banda",
                    if (r.inBandaMin == true && r.inBandaPicco == false) ", l'inizio rientra" else ""
                )
            )
        }

        println()
        println("Anche lo scocco e' un attacco: quanto il suo picco segue il fronte, e il Δ da fronte a fronte")
        righe.forEach { r ->
            println(
                "  %-16s scocco ritardo %2.0f/%2.0f ms (min/meta)  delta picco %.3f s  delta fronte-fronte %.3f/%.3f s  scarto %+.0f/%+.0f ms"
                    .format(
                        Locale.ROOT,
                        r.caso.nome,
                        r.gapScoccoMinMs,
                        r.gapScoccoMetaMs,
                        r.deltaPicco,
                        r.deltaFronteFronteMin,
                        r.deltaFronteFronteMeta,
                        (r.deltaFronteFronteMin - r.deltaPicco) * 1000.0,
                        (r.deltaFronteFronteMeta - r.deltaPicco) * 1000.0
                    )
            )
        }

        println()
        println("Distanza implicita dal modello del profilo (m): picco / inizio-minimo / inizio-metà / inizio-10%")
        righe.forEach { r ->
            println(
                "  %-16s %s".format(
                    Locale.ROOT,
                    r.caso.nome,
                    if (r.distanze.isEmpty()) "-" else r.distanze.joinToString(" / ")
                )
            )
        }

        val conBanda = righe.filter { it.caso.banda != null }
        val piccoFuori = conBanda.count { it.inBandaPicco == false }
        val minFuori = conBanda.count { it.inBandaMin == false }
        val codaMax = conBanda.mapNotNull { it.codaMs }.maxOrNull() ?: 0.0
        println()
        println("Casi con banda: ${conBanda.size}")
        println("  picco fuori banda: $piccoFuori, inizio fuori banda: $minFuori")
        println("  coda massima del picco oltre la banda: %.0f ms".format(Locale.ROOT, codaMax))
    }

    private fun elenco(r: Riga): String {
        fun segno(v: Boolean?) = when (v) {
            null -> "-"
            true -> "si"
            false -> "NO"
        }
        return "${segno(r.inBandaPicco)}/${segno(r.inBandaMin)}/${segno(r.inBandaMeta)}"
    }

    private fun analizza(caso: Caso, file: File): Riga {
        val tiri = ShotAudioProbe.analizzaContinuo(
            file = file,
            useClicker = caso.clicker,
            offsetMs = 0f,
            banda = caso.banda
        )
        require(tiri.size == 1) { "${caso.file}: atteso un tiro, trovati ${tiri.size}" }
        val tiro = tiri.single()
        val delta = requireNotNull(tiro.deltaS) { "${caso.file}: nessun impatto" }.toDouble()

        val inv = inviluppo(file)
        val rate = inv.rate
        val piccoAtteso = inv.indice(tiro.releaseS + delta)
        val picco = massimoLocale(inv.bb, piccoAtteso, (rate * 0.005).toInt())
        val ampiezza = inv.bb[picco]

        val finestra = (rate * RISE_MS / 1000.0).toInt()
        val da = max(0, picco - finestra)
        val minimo = (da..picco).minByOrNull { inv.bb[it] } ?: picco
        val valoreMinimo = inv.bb[minimo]
        val salita = ampiezza / max(valoreMinimo, 1e-9)

        var meta = picco
        while (meta > da && inv.bb[meta] > ampiezza * 0.5) meta--
        val sogliaDieci = valoreMinimo + 0.10 * (ampiezza - valoreMinimo)
        var dieci = picco
        while (dieci > da && inv.bb[dieci] > sogliaDieci) dieci--

        // Lo stesso conto sullo scocco: anche la corda che parte e' un attacco,
        // e il suo picco arriva dopo il fronte. Se si sposta solo l'impatto, il
        // Δ si accorcia del ritardo del rilascio senza che nessuno l'abbia
        // voluto: è una questione di convenzione, non di fisica.
        val piccoRilascio = massimoLocale(inv.bb, inv.indice(tiro.releaseS), (rate * 0.005).toInt())
        val rilascioDa = max(0, piccoRilascio - finestra)
        val minimoRilascio = (rilascioDa..piccoRilascio).minByOrNull { inv.bb[it] } ?: piccoRilascio
        var metaRilascio = piccoRilascio
        while (metaRilascio > rilascioDa && inv.bb[metaRilascio] > inv.bb[piccoRilascio] * 0.5) {
            metaRilascio--
        }

        // Fronte-fronte = Δ da picco meno il ritardo dell'impatto più quello
        // dello scocco: si spostano entrambi i riferimenti all'inizio.
        val gapScoccoMeta = (piccoRilascio - metaRilascio) * 1000.0 / rate
        val gapImpattoMin = (picco - minimo) * 1000.0 / rate
        val gapImpattoMeta = (picco - meta) * 1000.0 / rate
        val gapScocco = (piccoRilascio - minimoRilascio) * 1000.0 / rate
        val b = caso.banda
        fun dentro(t: Double): Boolean? = b?.contains(t.toFloat())

        return Riga(
            caso = caso,
            scoccoS = tiro.releaseS,
            deltaPicco = delta,
            gapMinMs = (picco - minimo) * 1000.0 / rate,
            gapMetaMs = (picco - meta) * 1000.0 / rate,
            gapDieciMs = (picco - dieci) * 1000.0 / rate,
            salita = salita,
            inBandaPicco = dentro(delta),
            inBandaMin = dentro(delta - (picco - minimo) * 1000.0 / rate / 1000.0),
            inBandaMeta = dentro(delta - (picco - meta) * 1000.0 / rate / 1000.0),
            codaMs = b?.let { (delta - it.endInclusive) * 1000.0 }?.takeIf { it > 0 },
            gapScoccoMinMs = gapScocco,
            gapScoccoMetaMs = gapScoccoMeta,
            deltaFronteFronteMin = delta + (gapScocco - gapImpattoMin) / 1000.0,
            deltaFronteFronteMeta = delta + (gapScoccoMeta - gapImpattoMeta) / 1000.0,
            distanze = caso.modello?.let { modello ->
                listOf(
                    delta,
                    delta - (picco - minimo) * 1000.0 / rate / 1000.0,
                    delta - (picco - meta) * 1000.0 / rate / 1000.0,
                    delta - (picco - dieci) * 1000.0 / rate / 1000.0
                ).map { d ->
                    BallisticCalculator.distance(d.toFloat(), modello, airTempC = 20f)
                        ?.let { "%.1f".format(Locale.ROOT, it) } ?: "-"
                }
            } ?: emptyList()
        )
    }

    // -----------------------------------------------------------------------
    // Registrazione intera: i quaranta tiri a 70 m.
    // -----------------------------------------------------------------------

    /**
     * La stessa misura, ma sul file completo: tutte e quaranta le frecce.
     *
     * Due passate. La prima è il motore vero in modalità **continua** (il file
     * tutto d'un fiato, sessione nuova appena un tiro si chiude): è la prova
     * severa, e dice quanti tiri il motore aggancia davvero. La seconda è in
     * modalità **per tiro**, con la sessione aperta 1,2 s prima di ogni
     * riferimento: serve a misurare il ritardo del picco su tutti e quaranta,
     * anche su quelli che la passata continua perde per il parlato.
     *
     * Per ogni tiro l'inviluppo lo+mid si ricalcola solo sul tratto attorno
     * all'impatto (0,45 s prima del riferimento, 80 ms dopo il massimo): sul
     * file da 692 s un inviluppo intero non sta in memoria senza sprecare
     * centinaia di megabyte per una misura che ne guarda 2 s per volta.
     */
    private fun registrazioneCompleta(file: File) {
        val banda = requireNotNull(banda(65f, 0.002f, 65f, 75f)) { "il profilo 70 m deve dare una banda" }
        println("registrazione: ${file.name} (${file.length() / 1048576} MiB)")
        println(
            "banda 70 m: %.3f..%.3f s   tolleranza di coda %.0f ms".format(
                Locale.ROOT, banda.start, banda.endInclusive, 40.0
            )
        )
        println()

        val iniziato = System.currentTimeMillis()
        val continuo = ShotAudioProbe.analizzaContinuo(
            file = file,
            useClicker = true,
            offsetMs = 0f,
            banda = banda
        )
        val confronto = ShotAudioProbe.confronta(continuo)
        println(
            "passata continua: ${continuo.size} misure in %d s, scocchi agganciati %d/%d, con impatto %d".format(
                Locale.ROOT,
                (System.currentTimeMillis() - iniziato) / 1000,
                confronto.agganciati,
                confronto.righe.size,
                confronto.conDelta
            )
        )
        val mancati = confronto.righe.filter { !it.agganciato }.map { it.indice }
        if (mancati.isNotEmpty()) println("  tiri non agganciati: ${mancati.joinToString(", ")}")

        // Le misure sono quelle della passata continua: ogni riferimento
        // agganciato porta lo scocco e il Δ che il motore ha scelto. La modalità
        // per tiro perderebbe otto frecce, perché su questa registrazione la
        // corda di otto tiri sta sotto il pavimento di 0,05 (0,021–0,044): una
        // sessione aperta apposta la scarta, mentre la passata continua la
        // recupera ripartendo dal transiente più forte.
        val usati = mutableSetOf<Int>()
        val righe = confronto.righe.mapNotNull { riga ->
            val scocco = riga.motoreS ?: return@mapNotNull null
            val indice = continuo.indices
                .filter { it !in usati }
                .minByOrNull { abs(continuo[it].releaseS - scocco) }
                ?: return@mapNotNull null
            usati += indice
            val tiro = continuo[indice]
            val delta = tiro.deltaS?.toDouble() ?: return@mapNotNull null
            misuraTiro(
                file = file,
                numero = riga.indice,
                riferimentoS = riga.riferimentoS,
                scoccoS = tiro.releaseS,
                deltaS = delta,
                banda = banda,
                prova = tiro.evidence?.name ?: "-"
            )
        }

        println(
            "%3s %9s %9s %7s %-15s %6s %6s %6s %7s %6s".format(
                Locale.ROOT, "#", "rif[s]", "delta[s]", "coda", "prova", "min", "meta", "dieci",
                "salita", "2max"
            )
        )
        righe.forEach { r ->
            println(
                "%3d %9.2f %9.3f %+6.0f  %-15s %5.0f %6.0f %6.0f %6.1fx %5s".format(
                    Locale.ROOT,
                    r.numero,
                    r.riferimentoS,
                    r.deltaS,
                    r.codaMs,
                    r.prova,
                    r.gapMinMs,
                    r.gapMetaMs,
                    r.gapDieciMs,
                    r.salita,
                    r.doppioMassimoMs?.let { "%.0f".format(Locale.ROOT, it) } ?: "-"
                )
            )
        }
        println()

        val attacchi = righe.filter { it.salita >= 4.0 }
        val deboli = righe.filter { it.salita < 4.0 }
        println("Ritardo del massimo sul fronte, ${attacchi.size} picchi che sono attacchi (salita >= 4x):")
        println("  meta salita: ${sintesi(attacchi.map { it.gapMetaMs }.sorted())}")
        println("  10% salita:   ${sintesi(attacchi.map { it.gapDieciMs }.sorted())}")
        println("  minimo 60 ms: ${sintesi(attacchi.map { it.gapMinMs }.sorted())}")
        if (deboli.isNotEmpty()) {
            println(
                "  ${deboli.size} picchi con salita < 4x (${deboli.joinToString(", ") { "#${it.numero} ${String.format(Locale.ROOT, "%.1f", it.salita)}x" }}): " +
                    "non sono attacchi, il fronte non è databile e serve la tolleranza di coda"
            )
        }
        println()

        val fuoriBanda = righe.filter { it.deltaS > banda.endInclusive }
        val margine = righe.minOf { banda.endInclusive - it.deltaS } * 1000.0
        println("Impatto scelto e banda:")
        println("  tiri dentro la banda: ${righe.size - fuoriBanda.size}/${righe.size}")
        println("  margine minimo alla fine della banda: %+.0f ms (positivo = dentro)".format(Locale.ROOT, margine))
        val indistinti = righe.filter { it.prova != "PEAK_IN_BAND" }
        if (indistinti.isNotEmpty()) {
            println("  prove non verdi: ${indistinti.joinToString { "#${it.numero} ${it.prova}" }}")
        }
        println()

        val lenti = righe.filter { it.gapMetaMs >= 20.0 }
        println("Tiri con il massimo almeno 20 ms dopo il fronte:")
        if (lenti.isEmpty()) {
            println("  nessuno")
        } else {
            lenti.forEach { r ->
                val secondo = r.doppioMassimoMs
                println(
                    "  #%-3d meta %3.0f ms, min %3.0f ms%s".format(
                        Locale.ROOT,
                        r.numero,
                        r.gapMetaMs,
                        r.gapMinMs,
                        if (secondo != null) ", secondo massimo a %.0f ms".format(Locale.ROOT, secondo) else ""
                    )
                )
            }
        }

        val conDoppio = righe.filter { it.doppioMassimoMs != null }
        println()
        println("Tiri con due massimi ravvicinati (quello che ha prodotto il caso da +29 ms): ${conDoppio.size}")
        conDoppio.forEach { r ->
            println(
                "  #%-3d massimo scelto %+.0f ms dal fronte, secondo massimo a %.0f ms".format(
                    Locale.ROOT, r.numero, r.gapMetaMs, requireNotNull(r.doppioMassimoMs)
                )
            )
        }
    }

    private data class Riga70(
        val numero: Int,
        val riferimentoS: Double,
        val scoccoS: Double,
        val deltaS: Double,
        val prova: String,
        val gapMinMs: Double,
        val gapMetaMs: Double,
        val gapDieciMs: Double,
        val salita: Double,
        val doppioMassimoMs: Double?,
        val fineBanda: Float
    ) {
        val codaMs: Double get() = (deltaS - fineBanda) * 1000.0
    }

    private fun sintesi(ordinati: List<Double>): String {
        if (ordinati.isEmpty()) return "nessun dato"
        val mediana = if (ordinati.size % 2 == 1) {
            ordinati[ordinati.size / 2]
        } else {
            (ordinati[ordinati.size / 2 - 1] + ordinati[ordinati.size / 2]) / 2.0
        }
        return "min %.0f  mediana %.0f  max %.0f".format(
            Locale.ROOT, ordinati.first(), mediana, ordinati.last()
        )
    }

    /** Misura un tiro della registrazione intera: fronte, massimo, doppio massimo. */
    private fun misuraTiro(
        file: File,
        numero: Int,
        riferimentoS: Double,
        scoccoS: Double,
        deltaS: Double,
        banda: ClosedFloatingPointRange<Float>,
        prova: String
    ): Riga70 {
        val impattoS = scoccoS + deltaS
        val tratto = inviluppoTratto(file, riferimentoS - 0.45, impattoS + 0.08)
        val rate = tratto.rate
        val picco = massimoLocale(tratto.bb, tratto.indice(impattoS), (rate * 0.005).toInt())
        val ampiezza = tratto.bb[picco]

        val finestra = (rate * RISE_MS / 1000.0).toInt()
        val da = max(0, picco - finestra)
        val minimo = (da..picco).minByOrNull { tratto.bb[it] } ?: picco
        val valoreMinimo = tratto.bb[minimo]
        var meta = picco
        while (meta > da && tratto.bb[meta] > ampiezza * 0.5) meta--
        val sogliaDieci = valoreMinimo + 0.10 * (ampiezza - valoreMinimo)
        var dieci = picco
        while (dieci > da && tratto.bb[dieci] > sogliaDieci) dieci--

        return Riga70(
            numero = numero,
            riferimentoS = riferimentoS,
            scoccoS = scoccoS,
            deltaS = deltaS,
            prova = prova,
            gapMinMs = (picco - minimo) * 1000.0 / rate,
            gapMetaMs = (picco - meta) * 1000.0 / rate,
            gapDieciMs = (picco - dieci) * 1000.0 / rate,
            salita = ampiezza / max(valoreMinimo, 1e-9),
            // Il massimo precedente si cerca solo sui veri attacchi: su un picco
            // sepolto nel rumore il "fronte" è pura convenzione e il massimo di
            // 40 ms prima è un'altra gobba, non lo stesso urto.
            doppioMassimoMs = if (ampiezza / max(valoreMinimo, 1e-9) >= 4.0) {
                massimoPrecedente(tratto.bb, picco, (rate * 0.045).toInt(), ampiezza, rate)
            } else {
                null
            },
            fineBanda = banda.endInclusive
        )
    }

    /**
     * Quanto il massimo scelto sta **dopo** il primo massimo forte dello stesso
     * urto, entro [raggio] campioni; `null` se è lui il primo.
     *
     * È la firma del caso da +29 ms: due massimi a 20–30 ms di distanza, dove
     * il rilevatore tiene il più forte della finestra di fusione di 30 ms e può
     * quindi datare l'impatto sul secondo. Un secondo massimo a 3 ms è invece
     * solo l'onda dell'inviluppo a 5 ms, non una fusione.
     */
    private fun massimoPrecedente(
        bb: DoubleArray,
        picco: Int,
        raggio: Int,
        ampiezza: Double,
        rate: Int
    ): Double? {
        if (ampiezza <= 0.0) return null
        val distanzaMinima = (rate * 0.012).toInt()
        val raggioLocale = max(1, (rate * 0.0025).toInt())
        val da = max(0, picco - raggio)
        for (i in da until picco - distanzaMinima) {
            if (bb[i] < ampiezza * 0.75) continue
            val sinistra = bb[max(0, i - raggioLocale)]
            val destra = bb[minOf(bb.size - 1, i + raggioLocale)]
            if (bb[i] >= sinistra && bb[i] >= destra) {
                return (picco - i) * 1000.0 / rate
            }
        }
        return null
    }

    /** Una finestra di inviluppo lo+mid, con i tempi assoluti del file. */
    private class Tratto(val rate: Int, val daS: Double, val bb: DoubleArray) {
        fun indice(t: Double): Int = ((t - daS) * rate).roundToInt().coerceIn(0, bb.size - 1)
    }

    /** L'inviluppo lo+mid a 5 ms del solo tratto [daS]..[aS] del file. */
    private fun inviluppoTratto(file: File, daS: Double, aS: Double): Tratto {
        val wav = ShotAudioProbe.WavInput(file)
        val rate = wav.sampleRate
        wav.seekTo(daS * rate)
        val finestra = max(1, (rate * ENVELOPE_MS / 1000.0).toInt())
        val acuto = Biquad.bandPass(rate.toDouble(), 1500.0, 8000.0)
        val grave = Biquad.bandPass(rate.toDouble(), 120.0, 900.0)
        val quanti = ((aS - daS) * rate).toInt().coerceAtLeast(finestra)
        val quadraticiGrave = DoubleArray(quanti)
        val quadraticiAcuto = DoubleArray(quanti)
        var n = 0
        val blocco = FloatArray(rate / 50)
        while (n < quanti) {
            val letti = wav.readBlock(blocco)
            if (letti <= 0) break
            for (i in 0 until letti) {
                if (n >= quanti) break
                val campione = blocco[i].toDouble()
                val g = grave.process(campione)
                val a = acuto.process(campione)
                quadraticiGrave[n] = g * g
                quadraticiAcuto[n] = a * a
                n++
            }
        }
        wav.close()

        val bb = DoubleArray(n)
        var sommaGrave = 0.0
        var sommaAcuto = 0.0
        for (i in 0 until n) {
            sommaGrave += quadraticiGrave[i]
            sommaAcuto += quadraticiAcuto[i]
            if (i >= finestra) {
                sommaGrave -= quadraticiGrave[i - finestra]
                sommaAcuto -= quadraticiAcuto[i - finestra]
            }
            if (i >= finestra - 1) {
                val g = sommaGrave / finestra
                val a = sommaAcuto / finestra
                bb[i] = sqrt(max(0.0, g)) + sqrt(max(0.0, a))
            }
        }
        return Tratto(rate, daS, bb)
    }

    /** L'inviluppo lo+mid a 5 ms, con gli stessi filtri e la stessa finestra del rilevatore. */
    private class Inviluppo(val rate: Int, val bb: DoubleArray) {
        fun indice(t: Double): Int = (t * rate).roundToInt().coerceIn(0, bb.size - 1)
    }

    private fun inviluppo(file: File): Inviluppo {
        val wav = ShotAudioProbe.WavInput(file)
        val rate = wav.sampleRate
        val finestra = max(1, (rate * ENVELOPE_MS / 1000.0).toInt())
        val acuto = Biquad.bandPass(rate.toDouble(), 1500.0, 8000.0)
        val grave = Biquad.bandPass(rate.toDouble(), 120.0, 900.0)

        val quadraticiGrave = ArrayList<Double>()
        val quadraticiAcuto = ArrayList<Double>()
        val blocco = FloatArray(rate / 50)
        while (true) {
            val letti = wav.readBlock(blocco)
            if (letti <= 0) break
            for (i in 0 until letti) {
                val campione = blocco[i].toDouble()
                val g = grave.process(campione)
                val a = acuto.process(campione)
                quadraticiGrave += g * g
                quadraticiAcuto += a * a
            }
        }
        wav.close()

        val n = quadraticiGrave.size
        val sommaGrave = DoubleArray(n + 1)
        val sommaAcuto = DoubleArray(n + 1)
        for (i in 0 until n) {
            sommaGrave[i + 1] = sommaGrave[i] + quadraticiGrave[i]
            sommaAcuto[i + 1] = sommaAcuto[i] + quadraticiAcuto[i]
        }
        val bb = DoubleArray(n)
        for (i in finestra - 1 until n) {
            val g = (sommaGrave[i + 1] - sommaGrave[i + 1 - finestra]) / finestra
            val a = (sommaAcuto[i + 1] - sommaAcuto[i + 1 - finestra]) / finestra
            bb[i] = sqrt(max(0.0, g)) + sqrt(max(0.0, a))
        }
        return Inviluppo(rate, bb)
    }

    private fun massimoLocale(bb: DoubleArray, indice: Int, raggio: Int): Int {
        var migliore = indice.coerceIn(0, bb.size - 1)
        var i = max(0, migliore - raggio)
        val fine = minOf(bb.size - 1, migliore + raggio)
        while (i <= fine) {
            if (bb[i] > bb[migliore]) migliore = i
            i++
        }
        return migliore
    }
}
