package com.miraglio.domain.location

import android.location.Location

/**
 * Fix GPS estratto e congelato in un istante: posizione, accuratezza, orario
 * e (se disponibile) la rotta del movimento. Usato per i record "Luoghi" e
 * per la condivisione, indipendente dal ciclo di vita del tracker.
 */
data class GpsFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val bearingDeg: Float? = null
) {
    companion object {
        fun fromLocation(location: Location): GpsFix = GpsFix(
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            accuracyM = location.accuracy,
            timeMs = location.time,
            bearingDeg = if (location.hasBearing()) location.bearing else null
        )
    }
}
