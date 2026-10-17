package com.miraglio.data.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cerca un QR nei fotogrammi della camera e ne legge il testo.
 *
 * zxing e' gia' nel progetto per **generare** i QR (installazione, luoghi): qui
 * si usa la stessa libreria al contrario, senza aggiungere dipendenze.
 *
 * Il QR serve al trasferimento della calibrazione da un telefono all'altro:
 * l'app che riceve inquadra lo schermo dell'altra, come si fa gia' per il link
 * di installazione.
 */
class QrScanAnalyzer(
    private val onDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * Solo QR (e' l'unico formato che l'app genera) e con TRY_HARDER: il codice
     * inquadrato da un altro schermo ha riflessi e moire', e si legge comunque
     * in una manciata di fotogrammi.
     */
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    private val reader = MultiFormatReader()

    private val found = AtomicBoolean(false)
    private var lastProcessedNs = 0L
    private val minIntervalNs = 150_000_000L // ~6 fps: leggere un QR non e' una corsa

    /** Da chiamare uscendo dalla scansione: i fotogrammi si scartano subito. */
    fun stop() {
        found.set(true)
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (found.get()) {
            imageProxy.close()
            return
        }
        val now = System.nanoTime()
        if (now - lastProcessedNs < minIntervalNs) {
            imageProxy.close()
            return
        }
        lastProcessedNs = now

        var bitmap: Bitmap? = null
        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rotation = imageProxy.imageInfo.rotationDegrees
                bitmap = runCatching { imageProxy.toBitmap() }.getOrElse {
                    runCatching { BitmapConverter.toBitmap(mediaImage, rotation) }.getOrNull()
                }
                val testo = bitmap?.let { decode(it) }
                if (testo != null && found.compareAndSet(false, true)) {
                    onDecoded(testo)
                }
            }
        } finally {
            // Il fotogramma si libera sempre: la copia in bitmap e' di questo
            // analyzer, e a 6 fps sono 5 MB l'una (la memoria va restituita).
            bitmap?.recycle()
            imageProxy.close()
        }
    }

    /**
     * Null quando nell'inquadratura non c'e' nessun QR: e' il caso **normale**
     * di quasi tutti i fotogrammi (zxing lo segnala con un'eccezione), quindi
     * non e' un errore da riportare.
     */
    private fun decode(bitmap: Bitmap): String? {
        val larghezza = bitmap.width
        val altezza = bitmap.height
        if (larghezza <= 0 || altezza <= 0) return null
        val pixel = IntArray(larghezza * altezza)
        bitmap.getPixels(pixel, 0, larghezza, 0, 0, larghezza, altezza)
        val sorgente = RGBLuminanceSource(larghezza, altezza, pixel)
        val binaria = BinaryBitmap(HybridBinarizer(sorgente))
        return runCatching { reader.decode(binaria, hints).text }.getOrNull()
    }
}
