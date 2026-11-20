package com.miraglio.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtro biquad del secondo ordine, forma diretta trasposta II.
 *
 * È il filtro dei due rami del telemetro: uno acuto per scocco e rilascio
 * (1500–8000 Hz), uno grave per il battifreccia (120–900 Hz). Il secondo
 * ordine basta perché i due eventi stanno in bande separate da più di
 * un'ottava: quello che serve non è una selettività estrema ma un filtro che
 * non introduca ritardo variabile (un IIR del secondo ordine ha un ritardo di
 * gruppo di pochi campioni, costante, e quindi compensabile dalla
 * calibrazione).
 *
 * I conti si fanno in `Double` anche a 48 kHz con segnali piccoli: in `Float`
 * un passa-banda stretto a 328 Hz perde precisione sui coefficienti, e un
 * filtro instabile produrrebbe eventi fantasma invece di un errore.
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {

    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(sample: Double): Double {
        val out = b0 * sample + z1
        z1 = b1 * sample - a1 * out + z2
        z2 = b2 * sample - a2 * out
        return out
    }

    companion object {

        /**
         * Passa-banda RBJ con guadagno unitario al picco.
         *
         * La frequenza centrale è la media geometrica degli estremi e la
         * larghezza di banda ne fissa il Q: con 1500–8000 Hz esce
         * f0 ≈ 3464 Hz e Q ≈ 0.53, con 120–900 Hz f0 ≈ 329 Hz e Q ≈ 0.42.
         * Gli estremi vengono riportati dentro il limite di Nyquist invece di
         * far esplodere i coefficienti: se un giorno il sample rate scendesse,
         * il filtro si restringe ma resta stabile.
         */
        fun bandPass(sampleRateHz: Double, lowHz: Double, highHz: Double): Biquad {
            val nyquist = sampleRateHz / 2.0
            val low = lowHz.coerceIn(1.0, nyquist * 0.95)
            val high = highHz.coerceIn(low + 1.0, nyquist * 0.98)
            val centre = sqrt(low * high)
            val quality = centre / (high - low)

            val w0 = 2.0 * PI * centre / sampleRateHz
            val alpha = sin(w0) / (2.0 * quality)
            val cosW0 = cos(w0)

            val a0 = 1.0 + alpha
            return Biquad(
                b0 = alpha / a0,
                b1 = 0.0,
                b2 = -alpha / a0,
                a1 = (-2.0 * cosW0) / a0,
                a2 = (1.0 - alpha) / a0
            )
        }
    }
}
