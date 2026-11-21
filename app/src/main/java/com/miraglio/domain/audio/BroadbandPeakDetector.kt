package com.miraglio.domain.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Il picco locale dell'inviluppo **lo+mid**: il candidato d'impatto che il
 * rilevatore STA/LTA non può vedere.
 *
 * Perché serve. A 18 m l'impatto arriva 0,42 s dopo lo scocco, quando il
 * rilascio (0,35 di picco nel grave) ha appena alzato il fondo a lungo termine
 * del rilevatore grave: il rapporto STA/LTA all'impatto vale 0,37–0,47, cioè
 * il colpo non è un transiente *rispetto al fondo* e non supera mai il trigger.
 * Non è un problema di soglia: abbassarla farebbe entrare il parlato. La
 * specifica DSP lo aveva già visto e per l'impatto usa il massimo dell'energia
 * nella finestra, non un evento triggerato.
 *
 * Cosa fa. Inviluppo RMS a 5 ms delle due bande d'impatto (grave 120–900 Hz e
 * media 1,5–8 kHz, le stesse del motore), somma `lo+mid`, e un picco locale
 * ogni volta che l'inviluppo smette di salire. Si emette solo se:
 *
 * 1. supera un pavimento minimo. Non è una soglia di evento: l'ampiezza è
 *    nella scala dei filtri (dove anche il fondo vale qualche decimo), quindi
 *    il pavimento scarta solo il silenzio digitale. Il confronto che conta è
 *    il massimo dentro la banda di plausibilità, fatto dalla macchina;
 * 2. le due bande sono entrambe presenti (rapporto ≥ [minBalance]): una
 *    vocale è grave senza media, una sibilante è media senza grave, un urto no.
 *    È questa la "verifica di banda larga" per i picchi: al 18 m il ramo alto
 *    non produce nessun evento triggerato, quindi non si può chiedere la
 *    coincidenza con un evento acuto come per i gravi.
 * 3. è il **più forte** della sua finestra di [minGapMs]: due massimi vicini
 *    sono la stessa vibrazione, e tenere il primo (spesso il più debole)
 *    faceva perdere il colpo vero che arrivava 20–30 ms dopo.
 *
 * Restituisce eventi [ShotBranch.IMPACT_PEAK] con `peakIndex` = campione del
 * massimo: la macchina li tratta come candidati, la finestra di plausibilità
 * li filtra.
 */
internal class BroadbandPeakDetector(
    sampleRateHz: Int,
    windowMs: Double = 5.0,
    minGapMs: Double = 30.0,
    private val floor: Double = 0.005,
    private val minBalance: Double = 0.15
) {

    private val window = (sampleRateHz * windowMs / 1000.0).toInt().coerceAtLeast(1)
    private val minGapSamples = (sampleRateHz * minGapMs / 1000.0).toLong()

    private val lowSquares = DoubleArray(window)
    private val midSquares = DoubleArray(window)
    private var position = 0
    private var filled = 0
    private var sumLow = 0.0
    private var sumMid = 0.0

    private var previousBroadband = 0.0
    private var previousLow = 0.0
    private var previousMid = 0.0
    private var previousIndex = 0L
    private var rising = false
    private var pending: AcousticEvent? = null

    fun reset() {
        lowSquares.fill(0.0)
        midSquares.fill(0.0)
        position = 0
        filled = 0
        sumLow = 0.0
        sumMid = 0.0
        previousBroadband = 0.0
        previousLow = 0.0
        previousMid = 0.0
        previousIndex = 0L
        rising = false
        pending = null
    }

    /** Consuma un campione dei due rami già filtrati; aggiunge al massimo un picco. */
    fun process(low: Double, mid: Double, index: Long, out: MutableList<AcousticEvent>) {
        val lowSquare = low * low
        val midSquare = mid * mid
        sumLow += lowSquare - lowSquares[position]
        sumMid += midSquare - midSquares[position]
        lowSquares[position] = lowSquare
        midSquares[position] = midSquare
        position = (position + 1) % window

        // I primi 5 ms non sono un inviluppo completo: un "picco" qui sarebbe
        // solo il bordo della finestra, non un urto.
        if (filled < window) {
            filled++
            return
        }

        val lowEnvelope = sqrt(sumLow / window)
        val midEnvelope = sqrt(sumMid / window)
        val broadband = lowEnvelope + midEnvelope

        if (broadband > previousBroadband) {
            rising = true
        } else if (rising) {
            // Il massimo era il campione precedente: l'inviluppo ha smesso di
            // salire. Non si emette subito: si tiene il più forte della
            // finestra di [minGapMs], altrimenti due massimi a 20 ms di
            // distanza farebbero scartare quello vero.
            val candidate = peakAt(previousIndex, previousBroadband)
            val waiting = pending
            when {
                candidate == null -> Unit
                waiting == null -> pending = candidate
                candidate.peakAmplitude > waiting.peakAmplitude -> pending = candidate
            }
            rising = false
        }

        // Il picco in attesa esce appena la sua finestra si chiude: così il
        // ritardo è di [minGapMs] al massimo e il motore lo vede comunque
        // dentro la finestra dell'impatto.
        pending?.let { p ->
            if (index - p.peakIndex >= minGapSamples) {
                out += p
                pending = null
            }
        }

        previousBroadband = broadband
        previousLow = lowEnvelope
        previousMid = midEnvelope
        previousIndex = index
    }

    private fun peakAt(index: Long, amplitude: Double): AcousticEvent? {
        if (amplitude < floor || !isBroadband(previousLow, previousMid)) return null
        return AcousticEvent(
            branch = ShotBranch.IMPACT_PEAK,
            startIndex = index,
            peakIndex = index,
            endIndex = index,
            peakAmplitude = amplitude.toFloat()
        )
    }

    private fun isBroadband(low: Double, mid: Double): Boolean {
        val strongest = max(low, mid)
        return strongest > 0.0 && min(low, mid) >= minBalance * strongest
    }
}
