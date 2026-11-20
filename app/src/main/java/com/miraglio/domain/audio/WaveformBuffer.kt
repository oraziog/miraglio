package com.miraglio.domain.audio

/**
 * Gli ultimi livelli del microfono, in un buffer circolare.
 *
 * Serve al grafico della forma d'onda: un blocco ogni 20 ms, quindi 50 valori
 * al secondo. Il buffer tiene molto più di quello che si vede (due minuti),
 * così lo zoom può allargare la finestra senza che il grafico resti vuoto
 * all'inizio della sessione.
 *
 * Il livello è il picco del blocco, non la media: sul grafico conta vedere
 * *quanto* è arrivato, e una media su 20 ms di un click da 5 ms sarebbe un
 * decimo del vero.
 */
class WaveformBuffer(private val capacity: Int = 6000) {

    private val values = FloatArray(capacity)
    private var count = 0
    private var next = 0

    val size: Int get() = count

    fun push(level: Float) {
        values[next] = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        count = 0
        next = 0
    }

    /**
     * Gli ultimi [wanted] campioni, dal più vecchio al più recente. Se non ce
     * ne sono abbastanza restituisce tutti quelli che ci sono: il grafico si
     * disegna da solo a mano a mano che la sessione riempie il buffer.
     */
    fun window(wanted: Int): FloatArray {
        val take = wanted.coerceIn(0, count)
        val out = FloatArray(take)
        var index = (next - take + capacity) % capacity
        for (i in 0 until take) {
            out[i] = values[index]
            index = (index + 1) % capacity
        }
        return out
    }

    companion object {

        /** Blocchi visibili a zoom 1× (30 s). */
        const val BASE_VISIBLE = 1500

        /** Estremi dello zoom del grafico. */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f

        /**
         * Quanti blocchi entrano nella finestra a un certo zoom. Zoom più alto
         * = meno tempo a schermo = più dettaglio: 8× mostra meno di 4 secondi.
         */
        fun visibleCount(zoom: Float, base: Int = BASE_VISIBLE): Int {
            val clamped = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            return (base / clamped).toInt().coerceAtLeast(16)
        }
    }
}
