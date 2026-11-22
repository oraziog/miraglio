package com.miraglio.domain.audio

/**
 * I rami del filtro: acuto (scocco e rilascio), grave (battifreccia) e il
 * picco d'inviluppo lo+mid ([IMPACT_PEAK]).
 *
 * Stanno in un enum perché gli eventi dei rami finiscono nella stessa
 * sequenza, e chi la legge deve poterli distinguere senza guardare
 * l'ampiezza: a 70 m l'impatto è più debole del rilascio, ma non sempre, e
 * affidarsi all'ampiezza per capire che cosa si è sentito sarebbe un
 * indovinello.
 *
 * [IMPACT_PEAK] non è un transiente triggerato: è il massimo locale
 * dell'inviluppo delle due bande d'impatto, emesso anche quando il colpo è
 * troppo quieto rispetto al fondo per far scattare il rilevatore grave (a
 * 18 m il rapporto STA/LTA all'impatto vale 0,4). Vedi
 * [BroadbandPeakDetector].
 */
enum class ShotBranch { HIGH_BAND, LOW_BAND, IMPACT_PEAK }

/** Un transiente con l'etichetta del ramo che l'ha trovato. */
data class AcousticEvent(
    val branch: ShotBranch,
    val startIndex: Long,
    val peakIndex: Long,
    val endIndex: Long,
    val peakAmplitude: Float,
    /**
     * Per i soli picchi d'inviluppo: vero se il picco nasce da un attacco e non
     * da un'onda della coda riverberante (vedi [BroadbandPeakDetector]). Gli
     * eventi dei rami triggerati non hanno questa informazione: restano a
     * `true` e la macchina non la guarda.
     */
    val isAttack: Boolean = true,
    /**
     * Per i soli picchi d'inviluppo: il fronte dell'attacco, cioè l'ultimo
     * campione prima del massimo in cui l'inviluppo stava ancora sotto metà
     * della salita (vedi [BroadbandPeakDetector]).
     *
     * Non è un doppione di [startIndex]: quello è il campione in cui il
     * rilevatore ha cominciato a emettere l'evento, questo è il punto in cui
     * l'urto si è fatto sentire. Serve alla plausibilità: la banda del profilo
     * è un tempo di volo, quindi il fronte può stare dentro il campo mentre il
     * massimo dell'inviluppo — che lo segue sempre di qualche decina di
     * millisecondo — ne è già fuori.
     *
     * Vale [peakIndex] per le onde della coda (che non hanno un fronte) e per
     * gli eventi dei rami triggerati.
     */
    val onsetIndex: Long = peakIndex
)

/**
 * La pipeline DSP del telemetro: da campioni grezzi a eventi con indice.
 *
 * Non conosce il tempo dell'orologio e non decide niente sul tiro: riceve
 * campioni, restituisce eventi e mantiene un contatore cumulativo
 * ([sampleIndex]). Tutto ciò che riguarda *quando* è successo un evento è un
 * numero di campioni, mai `System.nanoTime()`: è la sola cosa che rende la
 * differenza fra due eventi indipendente dai ritardi dei buffer e dalla
 * latenza con cui `AudioRecord` consegna i dati.
 */
class AcousticShotAnalyzer(
    val sampleRateHz: Int = 48000,
    config: ShotTimingConfig = ShotTimingConfig(sampleRateHz = sampleRateHz)
) {

    private val highFilter = Biquad.bandPass(sampleRateHz.toDouble(), 1500.0, 8000.0)
    private val lowFilter = Biquad.bandPass(sampleRateHz.toDouble(), 120.0, 900.0)

    private val highDetector = TransientDetector(
        sampleRateHz = sampleRateHz,
        triggerRatio = config.triggerRatio.toDouble(),
        detriggerRatio = config.detriggerRatio.toDouble()
    )
    private val lowDetector = TransientDetector(
        sampleRateHz = sampleRateHz,
        triggerRatio = config.triggerRatio.toDouble(),
        detriggerRatio = config.detriggerRatio.toDouble()
    )
    private val peakDetector = BroadbandPeakDetector(sampleRateHz)

    /** Campioni consumati dall'ultimo [reset]: l'orologio del telemetro. */
    var sampleIndex: Long = 0L
        private set

    fun reset() {
        highFilter.reset()
        lowFilter.reset()
        highDetector.reset()
        lowDetector.reset()
        peakDetector.reset()
        sampleIndex = 0L
    }

    /**
     * Consuma un blocco di campioni e restituisce gli eventi chiusi dentro il
     * blocco, in ordine di fine. Vuoto quasi sempre: un blocco da 20 ms
     * contiene un evento solo nei momenti che contano.
     */
    fun processBlock(samples: FloatArray, length: Int = samples.size): List<AcousticEvent> {
        val highEvents = mutableListOf<TransientEvent>()
        val lowEvents = mutableListOf<TransientEvent>()
        val peakEvents = mutableListOf<AcousticEvent>()

        for (i in 0 until length) {
            val sample = samples[i].toDouble()
            val mid = highFilter.process(sample)
            val low = lowFilter.process(sample)
            highDetector.process(mid, sampleIndex, highEvents)
            lowDetector.process(low, sampleIndex, lowEvents)
            peakDetector.process(low, mid, sampleIndex, peakEvents)
            sampleIndex++
        }

        if (highEvents.isEmpty() && lowEvents.isEmpty() && peakEvents.isEmpty()) return emptyList()

        return buildList {
            highEvents.forEach { add(it.toAcoustic(ShotBranch.HIGH_BAND)) }
            lowEvents.forEach { add(it.toAcoustic(ShotBranch.LOW_BAND)) }
            peakEvents.forEach { add(it) }
        }.sortedBy { it.endIndex }
    }

    private fun TransientEvent.toAcoustic(branch: ShotBranch) = AcousticEvent(
        branch = branch,
        startIndex = startIndex,
        peakIndex = peakIndex,
        endIndex = endIndex,
        peakAmplitude = peakAmplitude.toFloat()
    )
}
