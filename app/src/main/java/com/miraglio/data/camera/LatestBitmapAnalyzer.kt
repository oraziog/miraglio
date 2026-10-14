package com.miraglio.data.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Analyzer minimale: conserva solo l'ultimo frame ruotato correttamente,
 * senza detection ne' OCR. Usato dalla schermata di calibrazione focale.
 */
class LatestBitmapAnalyzer : ImageAnalysis.Analyzer {

    @Volatile
    private var latestBitmap: Bitmap? = null

    private var lastProcessedNs = 0L
    private val minIntervalNs = 100_000_000L // ~10 fps

    fun getLatestBitmap(): Bitmap? = latestBitmap

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.nanoTime()
        if (now - lastProcessedNs < minIntervalNs) {
            imageProxy.close()
            return
        }
        lastProcessedNs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = runCatching {
                imageProxy.toBitmap()
            }.getOrElse {
                runCatching {
                    BitmapConverter.toBitmap(mediaImage, rotation)
                }.getOrNull()
            }

            if (bitmap != null) {
                latestBitmap = if (rotation != 0) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
            }
        } catch (_: Throwable) {
        } finally {
            imageProxy.close()
        }
    }
}
