package com.miraglio.domain.location

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Haversine {
    private const val EARTH_RADIUS_METERS = 6371000.0

    fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Punto di arrivo sul grande cerchio partendo da (lat, lon) con rotta
     * [bearingDeg] gradi dal nord vero per [distanceM] metri (formula di
     * Vincenty per il destination point).
     */
    fun destinationPoint(
        latDeg: Double,
        lonDeg: Double,
        bearingDeg: Double,
        distanceM: Double
    ): Pair<Double, Double> {
        if (distanceM <= 0.0) return Pair(latDeg, lonDeg)
        val delta = distanceM / EARTH_RADIUS_METERS
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(latDeg)
        val lon1 = Math.toRadians(lonDeg)
        val lat2 = asin(
            sin(lat1) * cos(delta) + cos(lat1) * sin(delta) * cos(brng)
        )
        val lon2 = lon1 + atan2(
            sin(brng) * sin(delta) * cos(lat1),
            cos(delta) - sin(lat1) * sin(lat2)
        )
        return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}