package com.miraglio.domain.location

/**
 * Stima la posizione del bersaglio fotografato a partire dal fix di scatto,
 * dalla direzione di mira (nord vero, dalla bussola) e dalla distanza
 * orizzontale stimata tra fotocamera e bersaglio.
 */
object TargetEstimator {

    /**
     * @return fix stimato del bersaglio, o null se i dati sono insufficienti.
     * L'accuratezza stimata somma l'incertezza del fix di scatto a quella di
     * direzione (prudente: 5 gradi di errata mira sulla distanza data).
     */
    fun estimateTarget(
        shot: GpsFix,
        bearingTrueDeg: Float,
        horizontalDistanceM: Float
    ): GpsFix? {
        if (horizontalDistanceM <= 0f) return null
        val (lat, lon) = Haversine.destinationPoint(
            shot.latitudeDeg,
            shot.longitudeDeg,
            bearingTrueDeg.toDouble(),
            horizontalDistanceM.toDouble()
        )
        val bearingErrorM = horizontalDistanceM * Math.toRadians(BEARING_ERROR_DEG).toFloat()
        val targetAccuracy = (shot.accuracyM + bearingErrorM).coerceAtMost(999f)
        return GpsFix(
            latitudeDeg = lat,
            longitudeDeg = lon,
            accuracyM = targetAccuracy,
            timeMs = shot.timeMs,
            bearingDeg = bearingTrueDeg
        )
    }

    /**
     * Stima la distanza orizzontale tra due coordinate GPS in metri.
     * Usa la formula Haversine per calcolare la distanza superficiale.
     */
    fun estimateTargetDistance(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float {
        return Haversine.distanceMeters(lat1.toDouble(), lon1.toDouble(), lat2.toDouble(), lon2.toDouble()).toFloat()
    }

    private const val BEARING_ERROR_DEG = 5.0
}
