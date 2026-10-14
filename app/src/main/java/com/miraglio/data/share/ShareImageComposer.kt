package com.miraglio.data.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.miraglio.domain.location.GpsFix
import java.io.File
import kotlin.math.abs

/**
 * Prepara la foto da condividere: banda di testo in basso (misura, direzione,
 * coordinate) e GPS scritto nei metadati EXIF del JPEG.
 */
object ShareImageComposer {

    /**
     * Copia la foto con una banda bianca semitrasparente in basso contenente
     * le [lines] (max [MAX_LINES]); null se non c'e' niente da scrivere.
     */
    fun compose(source: Bitmap, lines: List<String>): Bitmap? {
        val text = lines.filter { it.isNotBlank() }.take(MAX_LINES)
        if (text.isEmpty()) return null

        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(out)
        val textSize = out.width / 26f
        val padding = textSize * 0.5f
        val lineHeight = textSize * 1.35f
        val bandHeight = lineHeight * text.size + padding * 2f

        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, out.height - bandHeight, out.width.toFloat(), out.height.toFloat(), band)

        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this@apply.textSize = textSize
            isFakeBoldText = true
        }
        text.forEachIndexed { i, line ->
            val y = out.height - bandHeight + padding + lineHeight * (i + 0.85f)
            canvas.drawText(line, padding, y, pen)
        }
        return out
    }

    /**
     * Scrive lat/lon (piu' accuratezza) nei metadati EXIF del file JPEG gia'
     * salvato, cosi' la foto resta georeferenziata anche fuori dall'app
     * (gallerie, cartografici, ...).
     */
    fun writeExifGps(jpgFile: File, fix: GpsFix) {
        runCatching {
            val exif = ExifInterface(jpgFile)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(fix.latitudeDeg))
            exif.setAttribute(
                ExifInterface.TAG_GPS_LATITUDE_REF,
                if (fix.latitudeDeg >= 0) "N" else "S"
            )
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(fix.longitudeDeg))
            exif.setAttribute(
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                if (fix.longitudeDeg >= 0) "E" else "W"
            )
            // Errore di posizionamento orizzontale (rational, in metri).
            // Tag EXIF GPSHPositioningError: la costante non e' esposta in
            // android.media.ExifInterface, si usa il nome del tag direttamente.
            exif.setAttribute(
                "GPSHPositioningError",
                "${abs(fix.accuracyM).toInt().coerceAtLeast(1)}/1"
            )
            exif.saveAttributes()
        }.onFailure { e ->
            Log.w(TAG, "Scrittura EXIF GPS fallita", e)
        }
    }

    /** Gradi decimali -> "dd/1,mm/1,ss/100" (formato EXIF). */
    private fun toDms(value: Double): String {
        val abs = abs(value)
        val deg = abs.toInt()
        val minutesFloat = (abs - deg) * 60f
        val min = minutesFloat.toInt()
        val sec = ((minutesFloat - min) * 60f * 100f).toInt()
        return "$deg/1,$min/1,$sec/100"
    }

    /** Converte "dd/1,mm/1,ss/100" in gradi decimali (per eventuali letture). */
    fun fromDms(dms: String): Double {
        val parts = dms.split(",")
        val deg = parts.getOrNull(0)?.substringBefore("/")?.toDoubleOrNull() ?: 0.0
        val min = parts.getOrNull(1)?.substringBefore("/")?.toDoubleOrNull() ?: 0.0
        val sec = parts.getOrNull(2)?.substringBefore("/")?.toDoubleOrNull() ?: 0.0
        return deg + min / 60.0 + sec / 3600.0
    }

    private const val MAX_LINES = 4
    private const val TAG = "ShareImageComposer"
}
