package com.miraglio.domain.focal

import kotlin.math.abs

/**
 * Confronto fra la focale importata da un altro telefono e quella misurata qui
 * su un bersaglio di dimensione e distanza note.
 *
 * Un'importazione porta numeri di un'altra fotocamera: se il modello e' lo
 * stesso devono tornare, se e' diverso la calibrazione e' quella sbagliata (o
 * il punto e' stato misurato male). La tolleranza e' larga apposta: le linee
 * sono tracciate a mano, e un errore di pochi pixel si traduce in qualche
 * punto percentuale di focale.
 */
object FocalVerification {

    /** Scarto relativo oltre il quale le due focali non sono la stessa lente. */
    const val TOLERANCE = 0.15f

    /** Scarto relativo fra le due focali, o null se una delle due non e' valida. */
    fun relativeError(expectedPx: Float, measuredPx: Float): Float? {
        if (expectedPx <= 0f || measuredPx <= 0f) return null
        if (!expectedPx.isFinite() || !measuredPx.isFinite()) return null
        return abs(measuredPx - expectedPx) / expectedPx
    }

    /**
     * Vero se la focale misurata e' in linea con quella importata, falso se non
     * lo e'. Null quando non c'e' niente da confrontare.
     */
    fun matches(expectedPx: Float, measuredPx: Float): Boolean? =
        relativeError(expectedPx, measuredPx)?.let { it <= TOLERANCE }
}
