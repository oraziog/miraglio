package com.miraglio.domain.share

import com.miraglio.domain.location.GpsFix
import java.util.Locale
import kotlin.math.abs

/**
 * Compone il messaggio di condivisione "universale": testo con i dati del
 * luogo + link Google Maps in forma https://maps.google.com/?q=lat,lon,
 * tappabile sia da Android che da iPhone (nessuna dipendenza da app esterne).
 */
object ShareMessageComposer {

    /** Link Google Maps (pin) per una posizione. */
    fun mapsLink(fix: GpsFix): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", fix.latitudeDeg, fix.longitudeDeg)

    /**
     * Messaggio per un luogo scattato con la fotocamera: foto + misura +
     * direzione + pin del bersaglio stimato. Senza fix GPS il pin viene
     * omesso (la foto resta condivisibile).
     */
    fun cameraPlace(
        fix: GpsFix?,
        bearingDeg: Float,
        distanceM: Float,
        horizontalDistanceM: Float,
        pitchDeg: Float,
        measuredSizeM: Float? = null,
        target: GpsFix? = null
    ): String = buildString {
        appendLine("📍 Luogo salvato con Miraglio")
        appendLine(String.format(Locale.US, "Distanza: %.1f m (orizzontale %.1f m)", distanceM, horizontalDistanceM))
        measuredSizeM?.let {
            appendLine(String.format(Locale.US, "Dimensione bersaglio: %.2f m", it))
        }
        appendLine(String.format(Locale.US, "Direzione: %s (%.0f°)", compassLabel(bearingDeg), bearingDeg))
        appendLine(String.format(Locale.US, "Inclinazione: %.1f°", pitchDeg))
        appendLine()
        if (fix != null) {
            appendLine(String.format(Locale.US, "Da qui: %.6f, %.6f (±%.0f m)", fix.latitudeDeg, fix.longitudeDeg, fix.accuracyM))
            appendLine(mapsLink(fix))
        } else {
            appendLine("Posizione GPS non disponibile al momento dello scatto")
        }
        if (target != null) {
            appendLine()
            appendLine(String.format(Locale.US, "🎯 Bersaglio (stimato): %.6f, %.6f (±%.0f m)", target.latitudeDeg, target.longitudeDeg, target.accuracyM))
            appendLine(mapsLink(target))
        }
    }

    /**
     * Messaggio per una misura GPS percorsa a piedi (partenza -> arrivo).
     * Se manca il fix di partenza il pin viene omesso (avviso a monte).
     */
    fun gpsPlace(
        distanceM: Float,
        start: GpsFix?,
        end: GpsFix
    ): String = buildString {
        appendLine("📏 Misura GPS salvata con Miraglio")
        appendLine(String.format(Locale.US, "Distanza: %.1f m", distanceM))
        appendLine()
        appendLine(
            String.format(
                Locale.US,
                "Arrivo: %.6f, %.6f (±%.0f m)",
                end.latitudeDeg, end.longitudeDeg, end.accuracyM
            )
        )
        appendLine(mapsLink(end))
        if (start != null) {
            appendLine()
            appendLine(
                String.format(
                    Locale.US,
                    "Partenza: %.6f, %.6f (±%.0f m)",
                    start.latitudeDeg, start.longitudeDeg, start.accuracyM
                )
            )
            appendLine(mapsLink(start))
        }
    }

    /** Etichetta breve della direzione (8 settori). */
    fun compassLabel(bearingDeg: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val index = ((bearingDeg % 360f + 360f) % 360f / 45f + 0.5f).toInt() % 8
        return names[index]
    }

    /** Formattazione compatta dell'accuratezza (senza decimali se >= 10 m). */
    fun accuracyLabel(accuracyM: Float): String =
        if (abs(accuracyM) >= 10f) "±${accuracyM.toInt()} m" else String.format(Locale.US, "±%.1f m", accuracyM)
}
