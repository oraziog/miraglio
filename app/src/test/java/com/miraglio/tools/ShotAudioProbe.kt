package com.miraglio.tools

import com.miraglio.domain.audio.AcousticShotAnalyzer
import com.miraglio.domain.audio.ImpactPlausibility
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingConfig
import com.miraglio.domain.audio.ShotTimingMachine
import com.miraglio.domain.audio.ShotTimingState
import com.miraglio.domain.ballistics.ArrowSpeedModel
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Strumento offline: fa passare una registrazione vera per **la pipeline del
 * telemetro** e confronta gli scocchi trovati con quelli misurati a mano.
 *
 * Non reimplementa niente: usa [AcousticShotAnalyzer] e [ShotTimingMachine]
 * esattamente come fa `AcousticRangefinder`, con lo stesso pilotaggio (blocchi
 * da 20 ms, armamento quando lo stato torna `READY`). Cambia solo la sorgente:
 * un file invece del microfono.
 *
 * Due modi, perché la domanda ha due risposte diverse:
 *
 * - **per tiro** (`modo=tiro`): per ogni riferimento si apre una sessione
 *   nuova poco prima dello scocco, come fa l'arciere che preme il pulsante e
 *   poi tira. È il comportamento del prodotto.
 * - **continuo** (`modo=continuo`): il file passa tutto d'un fiato, con la
 *   sessione che riparte appena un tiro si chiude. È la prova più severa: in
 *   un video c'è il parlato, e il telefono non è progettato per ascoltare
 *   dodici minuti di chiacchiere.
 *
 * Rilanciarlo:
 *
 * ```
 * # mp3 -> wav mono 16 bit (ffmpeg serve a questo, non al progetto)
 * ffmpeg -i registrazione.mp3 -ac 1 -ar 48000 -sample_fmt s16 app/build/audio/tiri.wav
 * ./gradlew :app:analyzeShotAudio -Pwav=$PWD/app/build/audio/tiri.wav
 * ```
 *
 * Facoltativi: `-Pmodo=continuo`, `-PuseClicker=false`, `-PoffsetMs=150`,
 * `-Pprofilo=70m|recurve|barebow|compound` (con `-Pdmin`/`-Pdmax` per
 * sovrascrivere il campo di tiro). Senza profilo non c'è banda di
 * plausibilità: il motore non applica il veto, come in produzione senza
 * calibrazione.
 */
object ShotAudioProbe {

    /**
     * I quaranta scocchi misurati a mano sulla registrazione, in secondi.
     *
     * Sono il rilascio della corda — non il click del clicker, che precede di
     * circa 150 ms. Il tracciamento è stato fatto esternamente, guardando il
     * video e l'audio.
     */
    val RIFERIMENTI = doubleArrayOf(
        7.46, 15.82, 25.31, 34.69, 65.01, 75.43, 86.47, 105.54, 114.46, 122.62,
        141.65, 150.81, 160.68, 182.81, 190.87, 201.92, 227.20, 235.95, 322.64, 357.05,
        367.69, 386.88, 426.55, 436.18, 446.63, 466.26, 475.26, 497.68, 508.41, 518.30,
        544.93, 554.21, 562.06, 586.68, 595.64, 604.53, 624.00, 662.82, 679.45, 689.52
    )

    /**
     * Modello con cui si traduce il Δ in distanza: 65 m/s in uscita e una
     * perdita che a 70 m lascia 56,5 m/s. È il modello compatibile con i tempi
     * della registrazione (volo ~1,24 s + ritorno del suono ~0,20 s).
     */
    val MODELLO = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)

    /**
     * Tolleranza con cui un rilevamento è considerato lo stesso scocco del
     * riferimento: 120 ms.
     *
     * Non è larga a caso: i tempi di riferimento sono letti a mano sul video
     * (errore umano di qualche decina di ms), il decoder MP3 aggiunge una
     * latenza di ~26 ms e il rilevatore STA/LTA decide sull'inviluppo, non sul
     * primo campione. In cambio resta stretta rispetto agli eventi vicini: due
     * tiri consecutivi distano almeno cinque secondi.
     */
    const val TOLLERANZA_S = 0.120

    /**
     * Il pavimento di ampiezza con cui si decide se un acuto è la corda: lo
     * stesso valore predefinito di produzione.
     */
    val DEFAULT_RELEASE_FLOOR: Float = ShotTimingConfig().minReleaseAmplitude

    /** Quanto prima del riferimento si apre la sessione, in modalità per tiro. */
    const val PRE_ROLL_S = 1.2

    /** Quanto si aspetta dopo il riferimento prima di dichiarare la sessione chiusa. */
    const val POST_ROLL_S = 2.6

    enum class Modo { TIRO, CONTINUO }

    data class Tiro(
        val releaseS: Double,
        val deltaS: Float?
    )

    /**
     * Un tipo di arco: velocità di uscita e campo di tiro da cui deriva la
     * banda di Δ in cui un impatto è fisicamente possibile. È il preset della
     * specifica, usato per misurare sessioni diverse dai quaranta tiri a 70 m.
     */
        data class Profilo(
        val nome: String,
        val vf: Float,
        val decay: Float,
        val dminM: Float,
        val dmaxM: Float
    ) {
        val banda: ClosedFloatingPointRange<Float>?
            get() = ImpactPlausibility.band(
                model = ArrowSpeedModel(muzzleSpeedMs = vf, decayPerM = decay),
                distancesM = listOf(dminM, dmaxM),
                airTempC = 20f
            )
    }

    val PROFILI = mapOf(
        "barebow" to Profilo("barebow", 51f, 0.002f, 16f, 30f),
        "recurve" to Profilo("recurve", 60f, 0.001f, 15f, 70f),
        "compound" to Profilo("compound", 86f, 0.001f, 15f, 90f),
        // Il profilo dei quaranta tiri a 70 m: modello calibrato e campo 65–75.
        "70m" to Profilo("70m", 65f, 0.002f, 65f, 75f)
    )

    private fun config(
        rate: Int,
        banda: ClosedFloatingPointRange<Float>?,
        autoClicker: Boolean = false,
        releaseFloor: Float = DEFAULT_RELEASE_FLOOR
    ) = ShotTimingConfig(
        sampleRateHz = rate,
        plausibleTotalTimeS = banda,
        autoClicker = autoClicker,
        minReleaseAmplitude = releaseFloor
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            error(
                "uso: ShotAudioProbe <wav> [tiro|continuo] [useClicker=true] [offsetMs=0] " +
                    "[riferimenti.csv] [profilo] [dmin] [dmax] [autoClicker=false] [releaseFloor]"
            )
        }
        val file = File(args[0])
        require(file.isFile) { "non esiste: ${file.absolutePath}" }
        val modo = when (args.getOrNull(1)?.lowercase()) {
            null, "", "tiro" -> Modo.TIRO
            "continuo" -> Modo.CONTINUO
            else -> error("modo sconosciuto: ${args[1]}")
        }
        val useClicker = args.getOrNull(2)?.toBooleanStrictOrNull() ?: true
        val offsetMs = args.getOrNull(3)?.toFloatOrNull() ?: 0f
        val riferimenti = args.getOrNull(4)?.takeIf { it.isNotBlank() }
            ?.let { leggiRiferimenti(File(it)) } ?: RIFERIMENTI
        val profilo = args.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { nome ->
            PROFILI[nome.lowercase()] ?: error("profilo sconosciuto: $nome (${PROFILI.keys})")
        }
        val dmin = args.getOrNull(6)?.toFloatOrNull() ?: profilo?.dminM
        val dmax = args.getOrNull(7)?.toFloatOrNull() ?: profilo?.dmaxM
        val autoClicker = args.getOrNull(8)?.toBooleanStrictOrNull() ?: false
        val releaseFloor = args.getOrNull(9)?.toFloatOrNull() ?: DEFAULT_RELEASE_FLOOR
        // Senza profilo non c'è una banda: il veto non si applica, invece di
        // ricadere sulla vecchia costante 1,0–2,0 s che descrive solo i 70 m.
        val banda = if (profilo != null && dmin != null && dmax != null) {
            profilo.copy(dminM = dmin, dmaxM = dmax).banda
        } else {
            profilo?.banda
        }

        println("file: ${file.name} (${file.length() / 1024} KiB)")
        println(
            "modo: ${modo.name.lowercase()}  clicker=$useClicker  offset=${offsetMs}ms  " +
                "auto=$autoClicker  tolleranza=${(TOLLERANZA_S * 1000).toInt()}ms  " +
                "pavimento rilascio=%.3f".format(releaseFloor)
        )
        if (banda == null) {
            println("plausibilita': nessuna (manca un profilo calibrato: nessun veto)")
        } else {
            println(
                "plausibilita': %.3f..%.3f s".format(banda.start, banda.endInclusive) +
                    (profilo?.let { " (${it.nome}: vf=${it.vf} m/s, $dmin..$dmax m)" } ?: "")
            )
        }

        val iniziato = System.currentTimeMillis()
        val tiri = when (modo) {
            Modo.TIRO ->
                analizzaPerTiro(file, useClicker, offsetMs, riferimenti, banda, autoClicker, releaseFloor)
            Modo.CONTINUO -> analizzaContinuo(file, useClicker, offsetMs, banda, autoClicker, releaseFloor)
        }
        println("riferimenti: ${riferimenti.size}")
        println("analisi: ${tiri.size} rilevamenti in ${(System.currentTimeMillis() - iniziato) / 1000} s")
        println()

        stampa(confronta(tiri, riferimenti))
    }

    /**
     * Gli scocchi di riferimento da una tabella CSV, nella colonna
     * `inizio_scocco_s` (o la seconda, se manca l'intestazione). Serve per
     * misurare sessioni diverse da quella dei quaranta tiri a 70 m.
     */
    fun leggiRiferimenti(file: File): DoubleArray {
        val righe = file.readLines()
            .map { it.removePrefix("\uFEFF") }
            .filter { it.isNotBlank() }
        val colonna = righe.firstOrNull()
            ?.split(',')
            ?.map { it.trim() }
            ?.indexOf("inizio_scocco_s")
            ?.takeIf { it >= 0 } ?: 1
        val dati = if (righe.firstOrNull()?.toDoubleOrNull() == null) righe.drop(1) else righe
        return dati.mapNotNull { it.split(',').getOrNull(colonna)?.trim()?.toDoubleOrNull() }
            .toDoubleArray()
    }

    /**
     * Modalità prodotto: una sessione per tiro, aperta [PRE_ROLL_S] prima del
     *         riferimento e chiusa quando il motore ha finito o dopo [POST_ROLL_S].
     */
    fun analizzaPerTiro(
        file: File,
        useClicker: Boolean,
        offsetMs: Float,
        riferimenti: DoubleArray = RIFERIMENTI,
        banda: ClosedFloatingPointRange<Float>?,
        autoClicker: Boolean = false,
        releaseFloor: Float = DEFAULT_RELEASE_FLOOR
    ): List<Tiro> {
        val wav = WavInput(file)
        val rate = wav.sampleRate
        val tiri = mutableListOf<Tiro>()
        val block = FloatArray(rate / 50)
        riferimenti.forEach { riferimento ->
            val analyzer = AcousticShotAnalyzer(rate)
            val machine = ShotTimingMachine(config(rate, banda, autoClicker, releaseFloor))
            machine.startListening(useClicker, offsetMs)
            machine.onStability(true)
            machine.arm(0)

            val inizioS = riferimento - PRE_ROLL_S
            wav.seekTo(inizioS * rate)
            val fine = ((riferimento + POST_ROLL_S) * rate).toLong()
            while (analyzer.sampleIndex < fine) {
                val count = wav.readBlock(block)
                if (count <= 0) break
                analyzer.processBlock(block, count).forEach { machine.onEvent(it) }
                machine.onProgress(analyzer.sampleIndex)
                if (machine.state.value.stage == ShotStage.READY) machine.arm(analyzer.sampleIndex)
                val state = machine.state.value
                if (state.isFinished) {
                    state.releaseIndex?.let { tiri += tiroDi(state, it, rate, inizioS) }
                    break
                }
            }
        }
        wav.close()
        return tiri
    }

    /** Modalità severa: il file tutto d'un fiato, sessione nuova a ogni tiro. */
    fun analizzaContinuo(
        file: File,
        useClicker: Boolean,
        offsetMs: Float,
        banda: ClosedFloatingPointRange<Float>?,
        autoClicker: Boolean = false,
        releaseFloor: Float = DEFAULT_RELEASE_FLOOR
    ): List<Tiro> {
        val wav = WavInput(file)
        val rate = wav.sampleRate
        val analyzer = AcousticShotAnalyzer(rate)
        val machine = ShotTimingMachine(config(rate, banda, autoClicker, releaseFloor))

        fun nuovaSessione() {
            machine.startListening(useClicker, offsetMs)
            machine.onStability(true)
            machine.arm(analyzer.sampleIndex)
        }
        nuovaSessione()

        val block = FloatArray(rate / 50)
        val tiri = mutableListOf<Tiro>()
        while (true) {
            val count = wav.readBlock(block)
            if (count <= 0) break
            analyzer.processBlock(block, count).forEach { machine.onEvent(it) }
            machine.onProgress(analyzer.sampleIndex)
            if (machine.state.value.stage == ShotStage.READY) machine.arm(analyzer.sampleIndex)
            val state = machine.state.value
            if (state.isFinished) {
                state.releaseIndex?.let { tiri += tiroDi(state, it, rate, 0.0) }
                nuovaSessione()
            }
        }
        wav.close()
        return tiri
    }

    private fun tiroDi(
        state: ShotTimingState,
        releaseIndex: Long,
        rate: Int,
        inizioS: Double
    ): Tiro = Tiro(
        releaseS = inizioS + releaseIndex.toDouble() / rate,
        deltaS = state.measurement?.totalTimeS
    )

    data class Riga(
        val indice: Int,
        val riferimentoS: Double,
        val motoreS: Double?,
        val erroreMs: Double?,
        val deltaS: Float?,
        val agganciato: Boolean
    )

    data class Report(
        val righe: List<Riga>,
        /** Sessioni concluse dal motore, con o senza impatto. */
        val rilevamentiTotali: Int,
        /** Sessioni che hanno prodotto una misura (scocco e impatto). */
        val misuratiTotali: Int,
        /** Misure che non corrispondono a nessun riferimento. */
        val misureExtra: Int,
        /** Le stesse misure, per vederle: scocco e Δ. */
        val extra: List<Tiro> = emptyList(),
        val offsetMedianoMs: Double,
        val scartoMs: Double,
        val deltaMedioS: Double,
        val deltaMedianoS: Double
    ) {
        val agganciati: Int get() = righe.count { it.agganciato }
        val conDelta: Int get() = righe.count { it.deltaS != null }
        val senzaImpatto: Int get() = rilevamentiTotali - misuratiTotali
    }

    /** Confronta i rilevamenti con i riferimenti e riassume. */
    fun confronta(tiri: List<Tiro>, riferimenti: DoubleArray = RIFERIMENTI): Report {
        val usati = mutableSetOf<Int>()
        val righe = riferimenti.mapIndexed { indice, riferimento ->
            var migliore = -1
            var miglioreErrore = Double.MAX_VALUE
            tiri.forEachIndexed { i, tiro ->
                val errore = abs(tiro.releaseS - riferimento)
                if (errore < miglioreErrore) {
                    miglioreErrore = errore
                    migliore = i
                }
            }
            val tiro = if (migliore >= 0 && miglioreErrore <= TOLLERANZA_S) tiri[migliore].also { usati += migliore } else null
            Riga(
                indice = indice + 1,
                riferimentoS = riferimento,
                motoreS = tiro?.releaseS,
                erroreMs = tiro?.let { (it.releaseS - riferimento) * 1000.0 },
                deltaS = tiro?.deltaS,
                agganciato = tiro != null
            )
        }

        val errori = righe.mapNotNull { it.erroreMs }
        val delta = righe.mapNotNull { it.deltaS }
        val misurati = tiri.count { it.deltaS != null }
        val extra = tiri.filterIndexed { i, tiro -> i !in usati && tiro.deltaS != null }
        val misureExtra = misurati - tiri.indices.count { it in usati && tiri[it].deltaS != null }
        val mediana = errori.sorted().let {
            if (it.isEmpty()) 0.0
            else if (it.size % 2 == 1) it[it.size / 2]
            else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
        val scarto =
            if (errori.isEmpty()) 0.0
            else sqrt(errori.sumOf { (it - mediana) * (it - mediana) } / errori.size)
        val deltaOrdinati = delta.sorted()
        val deltaMediano = when {
            deltaOrdinati.isEmpty() -> 0.0
            deltaOrdinati.size % 2 == 1 -> deltaOrdinati[deltaOrdinati.size / 2].toDouble()
            else -> (deltaOrdinati[deltaOrdinati.size / 2 - 1] + deltaOrdinati[deltaOrdinati.size / 2]) / 2.0
        }
        return Report(
            righe = righe,
            rilevamentiTotali = tiri.size,
            misuratiTotali = misurati,
            misureExtra = misureExtra.coerceAtLeast(0),
            extra = extra,
            offsetMedianoMs = mediana,
            scartoMs = scarto,
            deltaMedioS = if (delta.isEmpty()) 0.0 else delta.sumOf { it.toDouble() } / delta.size,
            deltaMedianoS = deltaMediano
        )
    }

    private fun stampa(report: Report) {
        println("tiro  riferimento   motore    errore_ms   delta_s  esito")
        report.righe.forEach { riga ->
            println(
                "%4d  %11.2f  %9s  %9s  %7s  %s".format(
                    riga.indice,
                    riga.riferimentoS,
                    riga.motoreS?.let { "%.2f".format(it) } ?: "-",
                    riga.erroreMs?.let { "%+.0f".format(it) } ?: "-",
                    riga.deltaS?.let { "%.3f".format(it) } ?: "-",
                    if (riga.agganciato) "ok" else "MANCATO"
                )
            )
        }
        println()
        println("scocchi agganciati: ${report.agganciati}/${report.righe.size}")
        println("con impatto misurato: ${report.conDelta}/${report.righe.size}")
        println(
            "misure del motore: ${report.misuratiTotali} " +
                "(non collegate a un riferimento: ${report.misureExtra})"
        )
        report.extra.take(12).forEach { tiro ->
            println(
                "  misura fuori tabella: scocco %.3f s, delta %.3f s".format(
                    tiro.releaseS,
                    tiro.deltaS ?: 0f
                )
            )
        }
        println("sessioni chiuse senza impatto: ${report.senzaImpatto}")
        println(
            "errore mediano rispetto al riferimento: %+.0f ms (scarto tipo %.0f ms)"
                .format(report.offsetMedianoMs, report.scartoMs)
        )
        println(
            "delta dei tiri agganciati: medio %.3f s, mediana %.3f s"
                .format(report.deltaMedioS, report.deltaMedianoS)
        )
        val mancati = report.righe.filter { !it.agganciato }.map { it.indice }
        if (mancati.isNotEmpty()) println("tiri non agganciati: ${mancati.joinToString(", ")}")
    }

    /**
     * Il minimo per leggere un WAV PCM 16 bit mono in streaming.
     *
     * Non si usa `javax.sound.sampled`: il codice gira sulle classi di test di
     * un modulo Android, dove quel pacchetto non esiste (android.jar non lo
     * espone). Il file lo produce ffmpeg, quindi il formato è noto: RIFF, PCM,
     * 16 bit, un canale. Tutto il resto è un errore da segnalare.
     */
    class WavInput(file: File) : Closeable {

        private val input = java.io.RandomAccessFile(file, "r")
        val sampleRate: Int
        private val dataStart: Long
        private var remaining: Long

        init {
            require(readAscii(4) == "RIFF") { "non e' un RIFF: $file" }
            readLe32()
            require(readAscii(4) == "WAVE") { "non e' un WAVE: $file" }

            var rate = 0
            var channels = 0
            var bits = 0
            var dataBytes = -1L

            while (dataBytes < 0) {
                val id = readAscii(4)
                val size = readLe32()
                when (id) {
                    "fmt " -> {
                        val chunk = readBytes(size.toInt())
                        require(chunk.size >= 16) { "chunk fmt troppo corto" }
                        channels = le16(chunk, 2)
                        rate = le32(chunk, 4)
                        bits = le16(chunk, 14)
                        var extra = size.toInt() - chunk.size
                        while (extra-- > 0) input.read()
                    }

                    "data" -> dataBytes = size
                    else -> input.seek(input.filePointer + size)
                }
                if (size % 2 == 1L && id != "data") input.seek(input.filePointer + 1)
            }

            require(channels == 1) { "atteso mono (ffmpeg: -ac 1), trovati $channels canali" }
            require(bits == 16) { "attesi 16 bit (ffmpeg: -sample_fmt s16), trovati $bits" }
            sampleRate = rate
            remaining = dataBytes
            dataStart = input.filePointer
        }

        /** Riporta la lettura al campione [sample] del file. */
        fun seekTo(sample: Double) {
            val offset = (sample * 2).toLong().coerceAtLeast(0L)
            input.seek(dataStart + offset)
            remaining = ((input.length() - dataStart - offset) / 2).coerceAtLeast(0L)
        }

        /** Campioni nel buffer; 0 a fine file. */
        fun readBlock(target: FloatArray): Int {
            val wanted = minOf(remaining, target.size.toLong() * 2).toInt() and 0xFFFFFFFE.toInt()
            if (wanted <= 0) return 0
            val bytes = readBytes(wanted)
            val count = bytes.size / 2
            for (i in 0 until count) {
                target[i] = le16(bytes, i * 2).toShort() / 32768f
            }
            remaining -= bytes.size
            return count
        }

        override fun close() = input.close()

        private fun readAscii(length: Int): String {
            val bytes = readBytes(length)
            require(bytes.size == length) { "file troncato" }
            return String(bytes, Charsets.US_ASCII)
        }

        private fun readBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) bytes else bytes.copyOf(read)
        }

        private fun readLe16(): Int = readBytes(2).let { le16(it, 0) }

        private fun readLe32(): Long = readBytes(4).let { le32(it, 0).toLong() and 0xFFFFFFFFL }

        private fun le16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        private fun le32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
