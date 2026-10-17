package com.miraglio.domain.share

import androidx.annotation.StringRes
import com.miraglio.R
import com.miraglio.domain.location.GpsFix
import com.miraglio.i18n.Texts
import java.util.Locale
import kotlin.math.abs

/**
 * Compone il messaggio di condivisione "universale": testo con i dati del
 * luogo + link Google Maps in forma https://maps.google.com/?q=lat,lon,
 * tappabile sia da Android che da iPhone (nessuna dipendenza da app esterne).
 *
 * Il testo arriva dal file delle traduzioni, passando per [Texts]: queste sono
 * funzioni pure, chiamate sia da una ViewModel sia da una schermata Compose, e
 * la lingua la decide Android nella risorsa. Compose pero' non puo' passare un
 * `Texts` (ha `stringResource`, che e' `@Composable`), quindi cio' che serve
 * anche alle schermate — i settori della rosa dei venti — e' esposto come
 * **id di risorsa** ([compassLabelRes]), non come testo gia' composto.
 */
object ShareMessageComposer {

    /**
     * Soglia di tolleranza della verifica (stima app vs misura GPS): oltre
     * questa incertezza la precisione va dichiarata anche a chi riceve il
     * messaggio, non solo a chi misura.
     */
    const val PRECISION_WARNING_METERS = 3f

    /** Da dieci metri in su l'accuratezza si scrive senza decimali. */
    const val ACCURACY_WHOLE_METERS = 10f

    /** Link Google Maps (pin) per una posizione. */
    fun mapsLink(fix: GpsFix): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", fix.latitudeDeg, fix.longitudeDeg)

    /**
     * Messaggio per un luogo scattato con la fotocamera: foto + misura +
     * direzione + pin del bersaglio stimato. Senza fix GPS il pin viene
     * omesso (la foto resta condivisibile).
     */
    fun cameraPlace(
        texts: Texts,
        fix: GpsFix?,
        bearingDeg: Float,
        distanceM: Float,
        horizontalDistanceM: Float,
        pitchDeg: Float,
        measuredSizeM: Float? = null,
        target: GpsFix? = null
    ): String = buildString {
        appendLine(texts.get(R.string.share_camera_title))
        appendLine(
            texts.get(
                R.string.share_distance_and_horizontal,
                texts.format.distance(distanceM),
                texts.format.distance(horizontalDistanceM)
            )
        )
        measuredSizeM?.let {
            appendLine(texts.get(R.string.share_target_size, texts.format.objectSize(it)))
        }
        appendLine(
            texts.get(
                R.string.share_direction,
                compassLabel(texts, bearingDeg),
                texts.format.number(bearingDeg, 0)
            )
        )
        appendLine(texts.get(R.string.share_pitch, texts.format.number(pitchDeg, 1)))
        appendLine()
        if (fix != null) {
            appendLine(texts.get(R.string.share_from_here, coordinates(texts, fix)))
            appendLine(mapsLink(fix))
        } else {
            appendLine(texts.get(R.string.share_no_gps_fix))
        }
        if (target != null) {
            appendLine()
            appendLine(texts.get(R.string.share_target_estimated, coordinates(texts, target)))
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
        texts: Texts,
        distanceM: Float,
        start: GpsFix?,
        end: GpsFix,
        distanceErrorM: Float? = null
    ): String = buildString {
        appendLine(texts.get(R.string.share_gps_title))
        appendLine(
            if (distanceErrorM != null && distanceErrorM > 0f) {
                texts.get(
                    R.string.share_distance_with_error,
                    texts.format.distance(distanceM),
                    texts.format.accuracy(distanceErrorM)
                )
            } else {
                texts.get(R.string.share_distance, texts.format.distance(distanceM))
            }
        )
        if (distanceErrorM != null && distanceErrorM > PRECISION_WARNING_METERS) {
            appendLine(texts.get(R.string.share_low_precision_warning))
        }
        appendLine()
        appendLine(texts.get(R.string.share_arrival, coordinates(texts, end)))
        appendLine(mapsLink(end))
        if (start != null) {
            appendLine()
            appendLine(texts.get(R.string.share_start, coordinates(texts, start)))
            appendLine(mapsLink(start))
        }
    }

    /**
     * Coordinate pronte da incollare: punto decimale (non virgola, che
     * spezzerebbe l'indirizzo) e accuratezza con la virgola come a schermo.
     *
     * I due numeri si formattano qui e arrivano alla risorsa gia' come testo:
     * la risorsa non contiene nessun `%f`, quindi la virgola decimale non
     * dipende dai formati numerici della traduzione.
     *
     * La latitudine e la longitudine restano in gradi con il punto: sono dati da
     * incollare in altre app, non una lunghezza da leggere. L'accuratezza invece
     * e' una lunghezza e segue le unita' scelte: "±4,5 m" o "±16,4 ft".
     */
    private fun coordinates(texts: Texts, fix: GpsFix): String {
        val position = String.format(Locale.US, "%.6f, %.6f", fix.latitudeDeg, fix.longitudeDeg)
        return texts.get(
            R.string.share_coordinates,
            position,
            texts.format.accuracy(fix.accuracyM, accuracyDecimals(fix.accuracyM))
        )
    }

    /** Etichetta breve della direzione (8 settori), gia' tradotta. */
    fun compassLabel(texts: Texts, bearingDeg: Float): String =
        texts.get(compassLabelRes(bearingDeg))

    /**
     * Settore della rosa dei venti come `R.string.…`: la usano le schermate
     * Compose con `stringResource`, che non puo' chiamare [compassLabel] perche'
     * le serve un contesto. In inglese il settore ovest e' `W` (`SW`, `NW`),
     * in italiano `O` (`SO`, `NO`).
     */
    @StringRes
    fun compassLabelRes(bearingDeg: Float): Int = COMPASS_RES[compassSector(bearingDeg)]

    /**
     * Indice del settore a 45° piu' vicino: il mezzo grado di scarto evita che
     * un rilevamento appena sotto il confine (44,9°) finisca nel settore
     * precedente per via del troncamento.
     */
    private fun compassSector(bearingDeg: Float): Int =
        ((bearingDeg % 360f + 360f) % 360f / 45f + 0.5f).toInt() % 8

    /**
     * Arrotondamento dell'accuratezza: senza decimali quando e' grossolana
     * (10 m in su), con un decimale quando conta davvero.
     */
    fun accuracyDecimals(accuracyM: Float): Int =
        if (abs(accuracyM) >= ACCURACY_WHOLE_METERS) 0 else 1

    /** Accuratezza scritta come a schermo: "±12 m" / "±4,5 m" / "±16,4 ft". */
    fun accuracyLabel(texts: Texts, accuracyM: Float): String =
        texts.get(
            R.string.share_accuracy,
            texts.format.accuracy(accuracyM, accuracyDecimals(accuracyM))
        )

    private val COMPASS_RES = intArrayOf(
        R.string.compass_n,
        R.string.compass_ne,
        R.string.compass_e,
        R.string.compass_se,
        R.string.compass_s,
        R.string.compass_sw,
        R.string.compass_w,
        R.string.compass_nw
    )
}
