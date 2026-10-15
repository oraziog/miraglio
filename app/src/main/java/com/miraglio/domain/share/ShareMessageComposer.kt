package com.miraglio.domain.share

import com.miraglio.domain.format.ItalianFormat
import com.miraglio.domain.location.GpsFix
import java.util.Locale
import kotlin.math.abs

/**
 * Compone il messaggio di condivisione "universale": testo con i dati del
 * luogo + link Google Maps in forma https://maps.google.com/?q=lat,lon,
 * tappabile sia da Android che da iPhone (nessuna dipendenza da app esterne).
 */
object ShareMessageComposer {

    /**
     * Soglia di tolleranza della verifica (stima app vs misura GPS): oltre
     * questa incertezza la precisione va dichiarata anche a chi riceve il
     * messaggio, non solo a chi misura.
     */
    const val PRECISION_WARNING_METERS = 3f

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
        appendLine(
            "Distanza: ${ItalianFormat.meters(distanceM)} " +
                "(orizzontale ${ItalianFormat.meters(horizontalDistanceM)})"
        )
        measuredSizeM?.let {
            appendLine("Dimensione bersaglio: ${ItalianFormat.meters(it, 2)}")
        }
        appendLine("Direzione: ${compassLabel(bearingDeg)} (${ItalianFormat.number(bearingDeg, 0)}°)")
        appendLine("Inclinazione: ${ItalianFormat.number(pitchDeg, 1)}°")
        appendLine()
        if (fix != null) {
            appendLine("Da qui: ${coordinates(fix)}")
            appendLine(mapsLink(fix))
        } else {
            appendLine("Posizione GPS non disponibile al momento dello scatto")
        }
        if (target != null) {
            appendLine()
            appendLine("🎯 Bersaglio (stimato): ${coordinates(target)}")
            appendLine(mapsLink(target))
        }
    }

    /**
     * Messaggio per una misura GPS percorsa a piedi (partenza -> arrivo).
     * Se manca il fix di partenza il pin viene omesso (avviso a monte).
     *
     * [distanceErrorM] e' l'errore stimato sulla distanza (accuratezze dei
     * due fix combinate in quadratura). Viene sempre scritto accanto alla
     * distanza: la misura resta valida anche con errore elevato, ma chi la
     * riceve deve sapere di quanto puo' sbagliare il numero.
     */
    fun gpsPlace(
        distanceM: Float,
        start: GpsFix?,
        end: GpsFix,
        distanceErrorM: Float? = null
    ): String = buildString {
        appendLine("📏 Misura GPS salvata con Miraglio")
        appendLine(
            if (distanceErrorM != null && distanceErrorM > 0f) {
                "Distanza: ${ItalianFormat.meters(distanceM)} " +
                    "(errore stimato ±${ItalianFormat.meters(distanceErrorM)})"
            } else {
                "Distanza: ${ItalianFormat.meters(distanceM)}"
            }
        )
        if (distanceErrorM != null && distanceErrorM > PRECISION_WARNING_METERS) {
            appendLine(
                "⚠️ Precisione limitata: il valore può sbagliare di diversi metri."
            )
        }
        appendLine()
        appendLine("Arrivo: ${coordinates(end)}")
        appendLine(mapsLink(end))
        if (start != null) {
            appendLine()
            appendLine("Partenza: ${coordinates(start)}")
            appendLine(mapsLink(start))
        }
    }

    /**
     * Coordinate pronte da incollare: punto decimale (non virgola, che
     * spezzerebbe l'indirizzo) e accuratezza con la virgola come a schermo.
     */
    private fun coordinates(fix: GpsFix): String =
        String.format(
            Locale.US,
            "%.6f, %.6f (±%s m)",
            fix.latitudeDeg,
            fix.longitudeDeg,
            ItalianFormat.number(fix.accuracyM, 0)
        )

    /** Etichetta breve della direzione (8 settori). */
    fun compassLabel(bearingDeg: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val index = ((bearingDeg % 360f + 360f) % 360f / 45f + 0.5f).toInt() % 8
        return names[index]
    }

    /** Formattazione compatta dell'accuratezza (senza decimali se >= 10 m). */
    fun accuracyLabel(accuracyM: Float): String =
        if (abs(accuracyM) >= 10f) {
            "±${ItalianFormat.number(accuracyM, 0)} m"
        } else {
            "±${ItalianFormat.meters(accuracyM)}"
        }
}
