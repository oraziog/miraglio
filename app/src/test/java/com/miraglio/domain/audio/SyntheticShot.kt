package com.miraglio.domain.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Costruisce il segnale di un tiro sintetico: rumore di fondo più una serie di
 * brevi sinusoidi con **attacco netto e coda esponenziale**, una per ogni
 * evento.
 *
 * L'inviluppo conta quanto la frequenza. Un clicker, la corda che schiocca e un
 * battifreccia sono transienti: salgono in un istante e poi si spengono. Il
 * tempo che interessa al telemetro è l'istante della salita, quindi un
 * inviluppo che cresce (una finestra di Hann, per esempio) sposterebbe il
 * picco al centro della raffica — e due raffiche di durata diversa darebbero un
 * Δt sbagliato di millisecondi anche con un DSP perfetto.
 *
 * La frequenza sceglie il ramo (4000 Hz per scocco e rilascio, 300 Hz per
 * l'impatto), l'ampiezza sceglie chi è più forte.
 */
internal object SyntheticShot {

    data class Burst(
        val atS: Double,
        val freqHz: Double,
        val amplitude: Float,
        val lengthMs: Double
    )

    fun signal(
        sampleRateHz: Int,
        durationS: Double,
        bursts: List<Burst>,
        noise: Float = 0.002f,
        seed: Long = 7L
    ): FloatArray {
        val count = (durationS * sampleRateHz).toInt()
        val samples = FloatArray(count)
        val random = Random(seed)
        for (i in 0 until count) {
            samples[i] = ((random.nextFloat() * 2f) - 1f) * noise
        }

        bursts.forEach { burst ->
            val start = (burst.atS * sampleRateHz).toInt()
            val length = ((burst.lengthMs / 1000.0) * sampleRateHz).toInt().coerceAtLeast(2)
            // Coda che si spegne in circa un terzo della durata: al termine
            // della raffica resta il 5% dell'ampiezza.
            val tau = (burst.lengthMs / 3.0) / 1000.0
            for (k in 0 until length) {
                val index = start + k
                if (index !in 0 until count) continue
                val seconds = k.toDouble() / sampleRateHz
                val envelope = exp(-seconds / tau)
                val tone = sin(2.0 * PI * burst.freqHz * k / sampleRateHz)
                samples[index] += (tone * envelope * burst.amplitude).toFloat()
            }
        }
        return samples
    }
}
