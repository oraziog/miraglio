package com.miraglio.domain.audio

import kotlin.math.abs

/**
 * Un transiente trovato dal rilevatore.
 *
 * [peakIndex] è l'indice del campione con l'ampiezza massima: è **quello** il
 * tempo dell'evento, non l'inizio né la fine. Un colpo secco ha un fronte di
 * salita di pochi campioni, mentre la coda del filtro può durare decine di
 * millisecondi; prendere l'inizio o la fine sposterebbe la misura di tutta la
 * durata dell'evento.
 */
data class TransientEvent(
    val startIndex: Long,
    val peakIndex: Long,
    val endIndex: Long,
    val peakAmplitude: Double
) {
    val durationSamples: Long get() = endIndex - startIndex
}

/**
 * Rilevatore STA/LTA a energia, campione per campione.
 *
 * Il rapporto fra energia a breve termine (STA, 5 ms) e a lungo termine
 * (LTA, 500 ms) vale circa 1 sul rumore di fondo e schizza quando arriva un
 * transiente: è un rilevatore che si adatta da solo al vento, al pubblico e al
 * fruscio del microfono, senza una soglia assoluta da tarare sul campo.
 *
 * Tre accorgimenti che non sono nella formula ma servono a farla funzionare:
 *
 * 1. **Energia a blocchi da 1 ms.** L'LTA sono 24.000 campioni: calcolarne la
 *    media sommando la finestra a ogni campione costerebbe 24.000 somme per
 *    campione. Si somma invece l'energia di blocchi da 1 ms e si tiene la
 *    somma corrente di 500 blocchi — costo costante, e la risoluzione di 1 ms
 *    è venti volte più fine della tolleranza richiesta.
 * 2. **Fase di riscaldamento.** Nei primi 500 ms l'LTA è parziale o vicino a
 *    zero e il rapporto è enorme: qualunque fruscio farebbe partire un evento.
 *    Prima che l'LTA sia completo non si rileva niente.
 * 3. **Refrattarietà e durata massima.** La coda di riverbero può far
 *    ripartire il rilevatore appena scende sotto la soglia di rilascio, e un
 *    rumore continuo terrebbe un evento aperto per sempre: dopo ogni evento
 *    c'è un silenzio obbligatorio, e un evento che non si chiude viene chiuso
 *    d'ufficio.
 */
class TransientDetector(
    private val sampleRateHz: Int = 48000,
    staMs: Double = 5.0,
    ltaMs: Double = 500.0,
    private val triggerRatio: Double = 3.0,
    private val detriggerRatio: Double = 1.5,
    maxEventMs: Double = 250.0,
    minGapMs: Double = 20.0,
    private val minPeakAmplitude: Double = 0.0
) {

    private val hop = (sampleRateHz / 1000).coerceAtLeast(1)
    private val staBlocks = ((staMs * sampleRateHz / 1000.0) / hop).toInt().coerceAtLeast(1)
    private val ltaBlocks = ((ltaMs * sampleRateHz / 1000.0) / hop).toInt().coerceAtLeast(staBlocks + 1)
    private val staSamples = (staBlocks * hop).toDouble()
    private val ltaSamples = (ltaBlocks * hop).toDouble()
    private val maxEventSamples = (maxEventMs * sampleRateHz / 1000.0).toLong()
    private val minGapSamples = (minGapMs * sampleRateHz / 1000.0).toLong()

    private val blockEnergies = DoubleArray(ltaBlocks)
    private var blockPosition = 0
    private var blocksSeen = 0L
    private var staSum = 0.0
    private var ltaSum = 0.0

    private var blockAccumulator = 0.0
    private var inBlock = 0

    private var active = false
    private var eventStartIndex = 0L
    private var peakIndex = 0L
    private var peakAmplitude = 0.0
    private var gapUntilIndex = Long.MIN_VALUE

    fun reset() {
        blockEnergies.fill(0.0)
        blockPosition = 0
        blocksSeen = 0L
        staSum = 0.0
        ltaSum = 0.0
        blockAccumulator = 0.0
        inBlock = 0
        active = false
        eventStartIndex = 0L
        peakIndex = 0L
        peakAmplitude = 0.0
        gapUntilIndex = Long.MIN_VALUE
    }

    /**
     * Consuma un campione già filtrato. Gli eventi che si chiudono qui dentro
     * vengono aggiunti a [out]: di solito zero, al massimo uno.
     */
    fun process(sample: Double, index: Long, out: MutableList<TransientEvent>) {
        if (active) {
            val magnitude = abs(sample)
            if (magnitude > peakAmplitude) {
                peakAmplitude = magnitude
                peakIndex = index
            }
        }

        blockAccumulator += sample * sample
        inBlock++
        if (inBlock >= hop) {
            pushBlock(blockAccumulator)
            blockAccumulator = 0.0
            inBlock = 0
            evaluate(index, out)
        }

        if (active && index - eventStartIndex >= maxEventSamples) {
            closeEvent(index, out)
        }
    }

    private fun pushBlock(energy: Double) {
        val old = blockEnergies[blockPosition]
        val staAgo = (blockPosition - staBlocks + ltaBlocks) % ltaBlocks
        if (blocksSeen >= staBlocks) {
            staSum += energy - blockEnergies[staAgo]
        } else {
            staSum += energy
        }
        ltaSum += energy - old
        blockEnergies[blockPosition] = energy
        blockPosition = (blockPosition + 1) % ltaBlocks
        blocksSeen++
    }

    private fun evaluate(index: Long, out: MutableList<TransientEvent>) {
        if (blocksSeen < ltaBlocks) return
        val ratio = (staSum / staSamples) / (ltaSum / ltaSamples + EPSILON)
        if (!active) {
            if (ratio >= triggerRatio && index >= gapUntilIndex) {
                active = true
                eventStartIndex = index
                peakIndex = index
                peakAmplitude = 0.0
            }
        } else if (ratio < detriggerRatio) {
            closeEvent(index, out)
        }
    }

    private fun closeEvent(index: Long, out: MutableList<TransientEvent>) {
        if (peakAmplitude > minPeakAmplitude) {
            out += TransientEvent(
                startIndex = eventStartIndex,
                peakIndex = peakIndex,
                endIndex = index,
                peakAmplitude = peakAmplitude
            )
        }
        active = false
        gapUntilIndex = index + minGapSamples
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}
