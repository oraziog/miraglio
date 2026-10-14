package com.miraglio.domain.camera

import kotlin.math.sqrt
import kotlin.math.tan

data class CameraIntrinsics(
    val fxPixels: Float,
    val fyPixels: Float,
    val cxPixels: Float,
    val cyPixels: Float
) {
    companion object {
        fun fromDiagonalFov(
            fovDegrees: Float,
            imageWidthPx: Int,
            imageHeightPx: Int
        ): CameraIntrinsics {
            val diagonalPx = sqrt(
                (imageWidthPx * imageWidthPx + imageHeightPx * imageHeightPx).toDouble()
            )
            val focal = (diagonalPx / 2.0) / tan(Math.toRadians(fovDegrees.toDouble()) / 2.0)
            return CameraIntrinsics(
                fxPixels = focal.toFloat(),
                fyPixels = focal.toFloat(),
                cxPixels = imageWidthPx / 2f,
                cyPixels = imageHeightPx / 2f
            )
        }
    }
}

class PinholeDistanceEstimator(
    private val intrinsics: CameraIntrinsics
) {
    fun estimateDistanceMeters(
        knownObjectWidthMeters: Float,
        objectPixelWidthPx: Float,
        maxDistanceMeters: Float = 80f
    ): Float? {
        if (objectPixelWidthPx <= 0f) return null
        val distance = (knownObjectWidthMeters * intrinsics.fxPixels) / objectPixelWidthPx
        return if (distance <= 0f || distance > maxDistanceMeters) null else distance
    }

    fun estimateDistanceByHeightMeters(
        knownObjectHeightMeters: Float,
        objectPixelHeightPx: Float,
        maxDistanceMeters: Float = 80f
    ): Float? {
        if (objectPixelHeightPx <= 0f) return null
        val distance = (knownObjectHeightMeters * intrinsics.fyPixels) / objectPixelHeightPx
        return if (distance <= 0f || distance > maxDistanceMeters) null else distance
    }
}