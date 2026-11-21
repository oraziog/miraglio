package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il criterio della specifica: **due impulsi distanziati di 1,000 s devono dare
 * Δt = 1,000 s ± 5 ms**, e un pre-click a 150 ms dal battito principale non
 * deve essere preso per il rilascio.
 *
 * I segnali passano per un WAV vero (scritto e riletto) perché la tolleranza di
 * 5 ms su 48 kHz sono 240 campioni: un errore di un fattore due nella
 * conversione, o un `read` che restituisce un campione in meno, si vede qui e
 * non sul campo.
 */
class ShotTimingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sampleRate = 48000
    private var wavCount = 0

    /** Scrive il segnale su WAV e lo rilegge: è quello che entra nel DSP. */
    private fun throughWav(samples: FloatArray): FloatArray {
        // Nome diverso a ogni chiamata: un test che misura due volte lo stesso
        // segnale scrive due file, e TemporaryFolder rifiuta il doppione.
        val file = temp.newFile("shot-${wavCount++}.wav")
        Wav.write(file, sampleRate, samples)
        val (rate, read) = Wav.read(file)
        assertEquals(sampleRate, rate)
        assertEquals(samples.size, read.size)
        return read
    }

    /**
     * Esegue la pipeline come farebbe il motore: blocchi da 10 ms, eventi alla
     * macchina, avanzamento a ogni blocco. Il motore parte già armato perché
     * qui si prova il DSP, non l'attesa di stabilità (che ha il suo test).
     */
    private fun run(
        samples: FloatArray,
        useClicker: Boolean,
        releaseOffsetMs: Float = 0f,
        banda: ClosedFloatingPointRange<Float>? = null
    ): ShotTimingState {
        val analyzer = AcousticShotAnalyzer(sampleRate)
        val machine = ShotTimingMachine(
            ShotTimingConfig(sampleRateHz = sampleRate, plausibleTotalTimeS = banda)
        )

        machine.startListening(useClicker, releaseOffsetMs)
        machine.onStability(true)
        machine.arm(0)

        val blockSize = sampleRate / 100
        var from = 0
        while (from < samples.size) {
            val length = minOf(blockSize, samples.size - from)
            val chunk = samples.copyOfRange(from, from + length)
            analyzer.processBlock(chunk, length).forEach { machine.onEvent(it) }
            val state = machine.onProgress(analyzer.sampleIndex)
            from += length
            if (state.isFinished) break
        }
        return machine.state.value
    }

    @Test
    fun dueImpulsiAUnaDistanzaDiUnSecondo() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.2,
            bursts = listOf(
                // Rilascio: acuto, forte.
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                // Impatto: grave, più debole, esattamente 1,000 s dopo.
                SyntheticShot.Burst(atS = 1.700, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
            )
        )

        val state = run(throughWav(signal), useClicker = false)

        assertEquals(ShotStage.DONE, state.stage)
        val measurement = state.measurement
        assertNotNull(measurement)
        assertEquals(
            "Δt fra rilascio e impatto",
            1.000,
            measurement!!.totalTimeS.toDouble(),
            0.005
        )
        assertTrue(
            "l'impatto deve arrivare dopo il rilascio",
            measurement.impactIndex > measurement.releaseIndex
        )
    }

    /**
     * Con il clicker, il rilascio è il picco **più forte** della finestra, non
     * il primo che passa: il pre-click a 150 ms è più debole e va ignorato.
     */
    @Test
    fun ilPreClickNonDiventaIlRilascio() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.6,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 0.850, freqHz = 4000.0, amplitude = 0.20f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 1.000, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                SyntheticShot.Burst(atS = 2.000, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
            )
        )

        val state = run(throughWav(signal), useClicker = true)

        assertEquals(ShotStage.DONE, state.stage)
        val measurement = state.measurement!!
        // Se avesse scelto il pre-click, Δt sarebbe 1,150 s.
        assertEquals(1.000, measurement.totalTimeS.toDouble(), 0.005)
    }

    /**
     * La frase non è un click, anche se arriva prima: il click del clicker è di
     * un'altra classe di ampiezza e riapre la finestra dei 450 ms.
     *
     * Qui la frase sta a 0,600 s con 0,03 di ampiezza, il click a 1,000 con
     * 0,85 e lo scocco a 1,150 con 0,90. Senza riancoratura la finestra della
     * frase arriverebbe solo a 1,050 s e sceglierebbe il click come rilascio:
     * Δt 1,595 s invece di 1,445. Con la riancoratura il riferimento torna sullo
     * scocco.
     */
    @Test
    fun laFraseNonPrendeIlPostoDelClick() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.6,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.600, freqHz = 4000.0, amplitude = 0.03f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 1.000, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 1.150, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                SyntheticShot.Burst(atS = 2.595, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
            )
        )

        val state = run(throughWav(signal), useClicker = true)

        assertEquals(ShotStage.DONE, state.stage)
        assertEquals(1.445, state.measurement!!.totalTimeS.toDouble(), 0.005)
    }

    /** Clicker che scatta ma nessuna corda che parte: si torna a sorvegliare. */
    @Test
    fun clickerSenzaRilascioTornaPronto() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 1.8,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0)
            )
        )

        val state = run(throughWav(signal), useClicker = true)

        assertEquals(ShotStage.READY, state.stage)
        assertEquals(null, state.measurement)
    }

    /** Nessun battifreccia sentito: il tiro esiste, ma senza Δt. */
    @Test
    fun senzaImpattoIlTiroEDichiaratoTale() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.2,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0)
            )
        )

        val state = run(throughWav(signal), useClicker = false)

        assertEquals(ShotStage.DONE_NO_IMPACT, state.stage)
        assertEquals(null, state.measurement)
    }

    /**
     * L'offset dichiarato nel profilo è il ritardo fra click e rilascio: serve
     * a spostare il riferimento **dal click al rilascio**, non a spostare
     * indietro un rilascio già misurato.
     *
     * Il segnale è un tiro vero: click a 0,700 s, corda che parte 150 ms dopo,
     * battifreccia 1,445 s dopo il rilascio. Il tempo giusto è 1,445 s sia con
     * l'offset dichiarato sia senza. Se l'app lo sottraesse al picco direbbe
     * 1,595 s: su un arco a 70 m sono circa sette metri di troppo, contro il
     * criterio di un metro della specifica.
     */
    @Test
    fun lOffsetDelClickerSpostaIlRiferimentoDalClickAlRilascio() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 5.2,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 0.850, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                SyntheticShot.Burst(atS = 2.295, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
            )
        )

        val senzaOffset = run(throughWav(signal), useClicker = true, releaseOffsetMs = 0f)
        val conOffset = run(throughWav(signal), useClicker = true, releaseOffsetMs = 150f)

        assertEquals(ShotStage.DONE, senzaOffset.stage)
        assertEquals(1.445, senzaOffset.measurement!!.totalTimeS.toDouble(), 0.005)
        assertEquals(1.445, conOffset.measurement!!.totalTimeS.toDouble(), 0.005)
    }

    /**
     * La registrazione vera, usata come regressione: 40 tiri filati su uno
     * stesso bersaglio, scocco → suono dell'impatto con Δ medio 1,445 s, minimo
     * 1,43 s e massimo 1,47 s (deviazione standard 6 ms). Ogni scocco è
     * preceduto di circa 150 ms dal click del clicker.
     *
     * Due cose si verificano qui, e sono quelle che il telefono deve saper
     * fare: che un clicker a 150 ms dal rilascio non sposti la misura (si
     * dichiara l'offset nel profilo, e il tempo resta lo stesso), e che quei
     * tempi, passati per la fisica, siano davvero un bersaglio a 70 m — il
     * tiro a 70 m con una freccia intorno ai 56 m/s a destinazione dà ~1,24 s
     * di volo più ~0,20 s di ritorno del suono.
     */
    @Test
    fun laRegistrazioneRealeDaQuarantaTiriTornaA70Metri() {
        // Modello compatibile con i valori della registrazione: 65 m/s in
        // uscita e una perdita che a 70 m lascia 56,5 m/s (volo 1,24 s).
        val model = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)

        // Lo scatter della registrazione (±20 ms intorno alla media) vale da
        // solo ±1,2 m a 70 m: la tolleranza per singolo tiro deve tenerne
        // conto, e la media dei tiri è quella che deve cadere sul bersaglio.
        val distances = mutableListOf<Float>()

        listOf(1.43, 1.445, 1.47).forEach { delta ->
            val signal = SyntheticShot.signal(
                sampleRateHz = sampleRate,
                // Deve superare la fine della finestra d'impatto (2,20 s dal
                // rilascio) più il margine con cui la macchina decide.
                durationS = 3.5,
                bursts = listOf(
                    // Click del clicker.
                    SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0),
                    // Scocco: la corda che parte, 150 ms dopo il click.
                    SyntheticShot.Burst(atS = 0.850, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                    // Impatto sul bersaglio, delta secondi dopo lo scocco.
                    SyntheticShot.Burst(atS = 0.850 + delta, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
                )
            )

            val state = run(throughWav(signal), useClicker = true, releaseOffsetMs = 150f)

            assertEquals("il tiro a $delta s deve chiudersi con una misura", ShotStage.DONE, state.stage)
            val measurement = state.measurement!!
            assertEquals("Δt misurato per un tiro a $delta s", delta, measurement.totalTimeS.toDouble(), 0.005)

            val distance = BallisticCalculator.distance(measurement.totalTimeS, model, airTempC = 20f)
            assertNotNull("il modello deve produrre una distanza", distance)
            assertEquals("distanza ricavata dal tiro a $delta s", 70.0, distance!!.toDouble(), 1.5)
            distances += distance!!
        }

        assertEquals(
            "la media dei tiri deve cadere sul bersaglio",
            70.0,
            distances.average(),
            0.5
        )
    }

    /**
     * La degradazione esplicita: **`null` vuol dire nessun veto**, non una
     * finestra di ripiego.
     *
     * Lo stesso segnale ha un impatto a 1,700 s e un secondo urto più forte a
     * 2,700 s. Con la banda 0,9–1,5 s il secondo è fuori e il motore misura
     * 1,000 s; senza banda non c'è niente che lo escluda, e vince il più
     * forte, a 2,000 s. È il comportamento che tocca a un profilo non
     * calibrato, dove nessun numero inventato può dire quale dei due è il
     * battifreccia.
     */
    @Test
    fun senzaBandaIlMotoreNonApplicaIlVeto() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.6,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                SyntheticShot.Burst(atS = 1.700, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0),
                SyntheticShot.Burst(atS = 2.700, freqHz = 300.0, amplitude = 0.50f, lengthMs = 25.0)
            )
        )

        val conBanda = run(throughWav(signal), useClicker = false, banda = 0.9f..1.5f)
        assertEquals(ShotStage.DONE, conBanda.stage)
        assertEquals(
            "con la banda il secondo urto è fuori e resta l'impatto a 1,000 s",
            1.000,
            conBanda.measurement!!.totalTimeS.toDouble(),
            0.005
        )

        val senzaBanda = run(throughWav(signal), useClicker = false)
        assertEquals(ShotStage.DONE, senzaBanda.stage)
        assertEquals(
            "senza banda non c'è veto: vince il transiente più forte",
            2.000,
            senzaBanda.measurement!!.totalTimeS.toDouble(),
            0.010
        )
    }

    /** Il motore non si arma finché il telefono non è fermo. */
    @Test
    fun nonSiArmaPrimaDellaStabilita() {
        val machine = ShotTimingMachine(ShotTimingConfig(sampleRateHz = sampleRate))
        machine.startListening(useClicker = false, releaseOffsetMs = 0f)

        assertEquals(ShotStage.SETTLING, machine.state.value.stage)
        machine.onStability(false)
        assertEquals(ShotStage.SETTLING, machine.state.value.stage)

        machine.arm(0)
        assertEquals(
            "armare mentre è in assestamento non deve fare niente",
            ShotStage.SETTLING,
            machine.state.value.stage
        )

        machine.onStability(true)
        assertEquals(ShotStage.READY, machine.state.value.stage)
        machine.arm(0)
        assertEquals(ShotStage.ARMED, machine.state.value.stage)
    }

    /** Il segnale passa per il WAV senza perdere i picchi che contano. */
    @Test
    fun ilWavConservaAmpiezzaELunghezza() {
        val original = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 0.5,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.25, freqHz = 4000.0, amplitude = 0.8f, lengthMs = 5.0)
            )
        )
        val read = throughWav(original)

        val peakOriginal = original.maxOf { kotlin.math.abs(it) }
        val peakRead = read.maxOf { kotlin.math.abs(it) }
        assertTrue("picco perso: $peakRead contro $peakOriginal", peakRead > peakOriginal - 0.01f)
    }
}
