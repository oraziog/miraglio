package com.miraglio.domain.audio

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
        releaseOffsetMs: Float = 0f
    ): ShotTimingState {
        val analyzer = AcousticShotAnalyzer(sampleRate)
        val machine = ShotTimingMachine(ShotTimingConfig(sampleRateHz = sampleRate))

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
     * L'offset del clicker si sottrae al rilascio: con 30 ms dichiarati, lo
     * stesso segnale misura 30 ms in più.
     */
    @Test
    fun lOffsetDelClickerAllungaIlTempoMisurato() {
        val signal = SyntheticShot.signal(
            sampleRateHz = sampleRate,
            durationS = 3.6,
            bursts = listOf(
                SyntheticShot.Burst(atS = 0.700, freqHz = 4000.0, amplitude = 0.85f, lengthMs = 4.0),
                SyntheticShot.Burst(atS = 0.900, freqHz = 4000.0, amplitude = 0.90f, lengthMs = 6.0),
                SyntheticShot.Burst(atS = 1.900, freqHz = 300.0, amplitude = 0.45f, lengthMs = 25.0)
            )
        )

        val senzaOffset = run(throughWav(signal), useClicker = true, releaseOffsetMs = 0f)
        val conOffset = run(throughWav(signal), useClicker = true, releaseOffsetMs = 30f)

        assertEquals(1.000, senzaOffset.measurement!!.totalTimeS.toDouble(), 0.005)
        assertEquals(1.030, conOffset.measurement!!.totalTimeS.toDouble(), 0.005)
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
