package com.miraglio.data.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.Image.Plane
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

object BitmapConverter {

    // Allineato alla risoluzione dell'analisi (CameraService: 1280x960).
    const val MAX_BITMAP_SIDE = 1280

    fun toBitmap(image: Image, rotationDegrees: Int = 0): Bitmap {
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val rgb = yuv420ToRgb(
            width = image.width,
            height = image.height,
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uvBuffer = uvPlane.buffer,
            uvRowStride = uvPlane.rowStride,
            uvPixelStride = uvPlane.pixelStride
        )
        val source = Bitmap.createBitmap(rgb, image.width, image.height, Bitmap.Config.ARGB_8888)

        val scaled = scaleDown(source, MAX_BITMAP_SIDE)

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)

        if (rotated !== scaled) scaled.recycle()
        if (scaled !== source) source.recycle()
        return rotated
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val largest = max(width, height)
        if (largest <= maxSide) return bitmap

        val scale = maxSide.toFloat() / largest
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun yuv420ToRgb(
        width: Int,
        height: Int,
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uvBuffer: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int
    ): IntArray {
        val pixels = IntArray(width * height)

        for (j in 0 until height) {
            val yRowStart = j * yRowStride
            val uvRowStart = (j / 2) * uvRowStride
            for (i in 0 until width) {
                val yIndex = yRowStart + i * yPixelStride
                val y = (yBuffer.safeGet(yIndex).toInt() and 0xFF)

                val uIndex = uvRowStart + (i / 2) * uvPixelStride
                val vIndex = uvRowStart + (i / 2) * uvPixelStride + 1
                val u = (uvBuffer.safeGet(uIndex).toInt() and 0xFF) - 128
                val v = (uvBuffer.safeGet(vIndex).toInt() and 0xFF) - 128

                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                pixels[j * width + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun ByteBuffer.safeGet(index: Int): Byte {
        val limit = limit()
        return if (index in 0 until limit) this[index] else 0
    }
}