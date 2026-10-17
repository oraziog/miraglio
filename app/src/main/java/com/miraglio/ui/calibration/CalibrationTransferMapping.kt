package com.miraglio.ui.calibration

import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.calibration.CalibrationBundle
import com.miraglio.domain.calibration.CalibrationTransfer
import com.miraglio.domain.calibration.TransferLens
import com.miraglio.domain.calibration.TransferPoint

/**
 * Le due conversioni fra la calibrazione salvata su questo telefono e il file
 * che viaggia verso un altro (vedi [CalibrationTransfer]).
 *
 * Stanno fuori dalla ViewModel perche' sono **la parte che sbaglia in
 * silenzio**: se i punti finiscono sulla lente sbagliata, l'app funziona, la
 * schermata non protesta, e la distanza sbaglia di un fattore che nessuno vede.
 * Qui sono funzioni pure, coperte da test.
 */
internal object CalibrationTransferMapping {

    /**
     * La calibrazione di questo telefono come viaggia nel file.
     *
     * Le lenti si numerano **per posizione** fra le posteriori (`lensIds` e'
     * l'ordine che vede l'utente sul mirino, 1a, 2a, 3a): e' l'unico modo di
     * confrontare due telefoni diversi. Le lenti senza punti non entrano nel
     * file — una lente dichiarata e vuota obbligherebbe chi importa a mapparla
     * per niente.
     */
    fun bundle(
        device: String,
        lensIds: List<String>,
        pointsByLens: Map<String, List<FocalPoint>>
    ): CalibrationBundle {
        val lenses = lensIds.mapIndexedNotNull { index, lensId ->
            val punti = pointsByLens[lensId].orEmpty()
            if (punti.isEmpty()) return@mapIndexedNotNull null
            TransferLens(position = index + 1, points = punti.map { it.toTransferPoint() })
        }
        return CalibrationBundle(device = device, lensCount = lensIds.size, lenses = lenses)
    }

    /**
     * I punti del file scritti sulle lenti di **questo** telefono, accoppiati
     * per posizione. Le lenti che qui non esistono si saltano: appoggiarle a
     * un'altra lente sarebbe una calibrazione giusta scritta sulla lente
     * sbagliata, cioe' un errore che si vede solo sul bersaglio.
     *
     * Gli id dei punti sono nuovi e crescenti ([nowMillis] + contatore): sono
     * numeri di questa importazione, non di quella che li ha generati.
     */
    fun localPoints(
        bundle: CalibrationBundle,
        lensIds: List<String>,
        nowMillis: Long
    ): Map<String, List<FocalPoint>> {
        var offset = 0L
        return CalibrationTransfer.mapLenses(bundle, lensIds.size)
            .mapNotNull { riga ->
                val lensId = riga.localIndex?.let { lensIds.getOrNull(it) } ?: return@mapNotNull null
                lensId to riga.points.map { punto ->
                    FocalPoint(
                        id = nowMillis + offset++,
                        distanceM = punto.distanceM,
                        pixels = punto.pixels,
                        knownSizeM = punto.knownSizeM,
                        axis = punto.axis,
                        timestamp = nowMillis,
                        zoom = punto.zoom,
                        frameLongSide = punto.frameLongSide
                    )
                }
            }
            .toMap()
    }
}

/** Il punto come viaggia nel file: stessi numeri, meno roba locale (id, data). */
private fun FocalPoint.toTransferPoint(): TransferPoint = TransferPoint(
    distanceM = distanceM,
    pixels = pixels,
    knownSizeM = knownSizeM,
    axis = axis,
    zoom = zoom,
    frameLongSide = frameLongSide
)
