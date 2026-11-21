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
 * Ogni picco porta anche se è un **attacco** o un'onda della coda
 * (`isAttack`): è un attacco se sale almeno di [riseFactor] volte rispetto al
 * minimo degli ultimi [riseWindowMs]. Misurato il 21/11/2026 sulle due
 * sessioni a 18 m (microfono a 1 m e a 3 m dalla linea): i dieci impatti veri
 * salgono 5,9–27×, le onde della coda riverberante dello scocco 1,0–3,1×.
 * Senza questa informazione, con il microfono nella stanza il rilascio non si
 * spegne netto e il picco più forte dei primi 400 ms è un'onda della sua coda:
 * due tiri su dieci misuravano 0,157 e 0,180 s invece di 0,361 e 0,393.
 * L'ampiezza da sola non li separa (0,03 l'onda, 0,04 l'impatto). La scelta la
 * fa la macchina, che preferisce gli attacchi e ripiega sulle onde quando in
 * finestra non ce ne sono (a 60 m il compound ha un impatto sepolto nel
 * rumore, salita 1,9–3,4×: lì il ripiego è l'unico candidato).
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
    private val minBalance: Double = 0.15,
    riseWindowMs: Double = 60.0,
    private val riseFactor: Double = 4.0
) {

    private val window = (sampleRateHz * windowMs / 1000.0).toInt().coerceAtLeast(1)
    private val minGapSamples = (sampleRateHz * minGapMs / 1000.0).toLong()

    /** La storia dell'inviluppo per il confronto di salita (un attacco contro una coda). */
    private val riseWindow = (sampleRateHz * riseWindowMs / 1000.0).toInt().coerceAtLeast(1)
    private val riseHistory = DoubleArray(riseWindow)
    private var risePosition = 0
    private var riseFilled = 0

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
        riseHistory.fill(0.0)
        risePosition = 0
        riseFilled = 0
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

        riseHistory[risePosition] = broadband
        risePosition = (risePosition + 1) % riseWindow
        if (riseFilled < riseWindow) riseFilled++

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
            peakAmplitude = amplitude.toFloat(),
            isAttack = isNewAttack(amplitude)
        )
    }

    private fun isBroadband(low: Double, mid: Double): Boolean {
        val strongest = max(low, mid)
        return strongest > 0.0 && min(low, mid) >= minBalance * strongest
    }

    /**
     * Vero se il picco nasce da un attacco e non è un'onda della coda.
     *
     * In una coda riverberante l'inviluppo scende e risale di poco: ogni
     * massimo locale vale 1,1–2 volte il minimo dei 60 ms precedenti, che è il
     * massimo della stessa vibrazione un istante prima. Un urto no: parte dal
     * silenzio (o dalla coda già spenta) e sale di 6–27 volte. Il minimo è
     * limitato dal pavimento: sul silenzio digitale il rapporto sarebbe
     * infinito e non direbbe niente.
     */
    private fun isNewAttack(amplitude: Double): Boolean {
        var lowest = Double.MAX_VALUE
        val count = if (riseFilled < riseWindow) riseFilled else riseWindow
        for (i in 0 until count) {
            if (riseHistory[i] < lowest) lowest = riseHistory[i]
        }
        return amplitude >= riseFactor * max(lowest, floor)
    }
}
