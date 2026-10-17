package com.miraglio.domain.location

import kotlin.math.sqrt

/**
 * La distanza di calibrazione dal GPS: il satellite non misura la distanza a
 * un oggetto, misura due posizioni — qui sta il conto che le mette insieme.
 *
 * I due punti sono il fix di adesso (dove sei tu, con la camera davanti) e il
 * pin del bersaglio segnato stando davanti all'oggetto di dimensione nota
 * ("Segna bersaglio" nella schermata di calibrazione).
 */
object CalibrationDistance {

    /**
     * Distanza in metri fra il fix corrente e il pin del bersaglio.
     *
     * `null` quando non c'e' nulla da misurare: manca uno dei due punti, oppure
     * coincidono — zero non e' una distanza di calibrazione e il campo digitato
     * non accetta uno zero.
     */
    fun between(current: GpsFix?, target: GpsFix?): Float? {
        if (current == null || target == null) return null
        val meters = Haversine.distanceMeters(
            current.latitudeDeg,
            current.longitudeDeg,
            target.latitudeDeg,
            target.longitudeDeg
        ).toFloat()
        return meters.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Incertezza della distanza: i due fix hanno errori indipendenti, quindi si
     * sommano in quadratura (radice della somma dei quadrati). Sommarli
     * linearmente sarebbe prudente quanto pesarli al doppio.
     */
    fun accuracyMeters(current: GpsFix?, target: GpsFix?): Float? {
        if (current == null || target == null) return null
        val a = current.accuracyM.coerceAtLeast(0f).toDouble()
        val b = target.accuracyM.coerceAtLeast(0f).toDouble()
        return sqrt(a * a + b * b).toFloat()
    }
}
