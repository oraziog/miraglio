package com.miraglio.domain.sensor

import kotlin.math.sqrt

/**
 * "Il telefono è fermo?" — il criterio che tiene il motore spento mentre lo si
 * appoggia.
 *
 * L'accelerazione lineare di un telefono appoggiato su un tavolo oscilla di
 * pochi centesimi di m/s²; appoggiandolo, il colpo sul tavolo produce un
 * transiente che il microfono sente benissimo — e che senza questo controllo
 * verrebbe preso per uno scocco. Il criterio della specifica è la **varianza
 * su finestra mobile**: se l'accelerazione non cambia in 500 ms, il telefono è
 * fermo.
 *
 * Si guarda il modulo dell'accelerazione e non le tre assi separatamente: il
 * vettore di gravità cambia componente a ogni rotazione del telefono, quindi
 * le assi da sole direbbero "in movimento" anche solo girandolo di poco. Il
 * modulo della gravità è costante (9,81 m/s²) qualunque sia l'inclinazione, e
 * con il sensore di accelerazione lineare la gravità è già tolta: in entrambi
 * i casi resta solo quello che si muove davvero.
 */
class StabilityDetector(
    private val windowMs: Double = 500.0,
    private val varianceThreshold: Double = 0.30,
    private val minSamples: Int = 20
) {

    private val times = ArrayDeque<Long>()
    private val magnitudes = ArrayDeque<Double>()
    private var sum = 0.0
    private var sumOfSquares = 0.0

    /**
     * Aggiunge un campione (in nanosecondi, come lo dà `SensorEvent.timestamp`)
     * e dice se la finestra è stabile.
     *
     * Falso finché la finestra non è piena: "fermo da 100 ms" non è fermo.
     */
    fun add(timestampNs: Long, ax: Float, ay: Float, az: Float): Boolean {
        val magnitude = sqrt(
            (ax.toDouble() * ax + ay.toDouble() * ay + az.toDouble() * az)
        )
        times.addLast(timestampNs)
        magnitudes.addLast(magnitude)
        sum += magnitude
        sumOfSquares += magnitude * magnitude

        val windowNs = (windowMs * 1_000_000.0).toLong()
        val cutoff = timestampNs - windowNs
        while (times.isNotEmpty() && times.first() < cutoff) {
            val removed = magnitudes.removeFirst()
            times.removeFirst()
            sum -= removed
            sumOfSquares -= removed * removed
        }

        val count = magnitudes.size
        if (count < minSamples) return false
        val span = timestampNs - times.first()
        if (span < windowNs * 0.8) return false

        val mean = sum / count
        val variance = (sumOfSquares / count) - mean * mean
        return variance <= varianceThreshold
    }

    fun reset() {
        times.clear()
        magnitudes.clear()
        sum = 0.0
        sumOfSquares = 0.0
    }
}
