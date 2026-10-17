package com.miraglio.domain.calibration

import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.CRC32

/**
 * Trasferimento della calibrazione fra due telefoni: un arciere la esporta come
 * testo (per WhatsApp, email...) o come QR, un altro la importa.
 *
 * Tre scelte che stanno tutte qui, perche' e' il formato a doverle reggere:
 *
 * 1. **Somma di controllo.** Il testo puo' arrivare tagliato da un messaggio o
 *    letto male da un QR: senza controllo, una parentesi persa produrrebbe una
 *    calibrazione sbagliata *in silenzio*. Con il CRC32 sbagliato il testo si
 *    rifiuta, e l'utente lo sa.
 * 2. **Lenti per posizione.** La calibrazione e' per obiettivo, ma gli id delle
 *    fotocamere sono di quel telefono: l'unica cosa che si puo' confrontare fra
 *    due telefoni e' **l'ordine** (1a, 2a, 3a posteriore), che e' anche la
 *    numerazione che l'utente vede sul mirino. Se il numero di lenti non
 *    coincide la mappa **puo' essere sbagliata**, e chi importa lo deve sapere:
 *    vedi [mapLenses] e il messaggio di conferma della schermata.
 * 3. **La marca e il modello viaggiano nel file.** La focale e' una proprieta'
 *    fisica di quel telefono (e' scritto anche nel manuale): sapere da quale
 *    telefono arriva una calibrazione e' l'informazione piu' importante che
 *    l'utente riceve quando la importa.
 */
data class CalibrationBundle(
    /** Marca e modello del telefono che ha esportato. */
    val device: String,
    /** Quante lenti posteriori aveva quel telefono. */
    val lensCount: Int,
    val lenses: List<TransferLens>
) {
    val totalPoints: Int get() = lenses.sumOf { it.points.size }
}

/** La calibrazione di una lente, come viaggia nel file. */
data class TransferLens(
    /** Posizione fra le fotocamere posteriori (1 = la prima). */
    val position: Int,
    val points: List<TransferPoint>
)

/** Un punto di calibrazione, come viaggia nel file. */
data class TransferPoint(
    val distanceM: Float,
    val pixels: Float,
    val knownSizeM: Float,
    /** "H" = linee orizzontali (altezza, fy), "V" = verticali (larghezza, fx). */
    val axis: String,
    val zoom: Float,
    val frameLongSide: Int
)

/** Cosa fare, su questo telefono, di ogni lente del file. */
data class MappedLens(
    /** Posizione della lente nel file (1-based, come la numera l'altro telefono). */
    val fromPosition: Int,
    /** Indice della stessa lente qui (0-based), o null se qui non esiste. */
    val localIndex: Int?,
    val points: List<TransferPoint>
) {
    val skipped: Boolean get() = localIndex == null
}

object CalibrationTransfer {

    /** Prefisso del testo: dice che e' roba nostra e di che versione. */
    const val PREFIX = "MIRAGLIO-CAL1"

    /**
     * Oltre questa lunghezza il QR non ce la fa (un QR versione 40 arriva a
     * circa 2.300 byte) e si usa il testo: la schermata lo dice invece di
     * provare e fallire.
     */
    const val MAX_QR_CHARS = 1800

    /** Esporta: `MIRAGLIO-CAL1:<crc32>:<json>`. */
    fun encode(bundle: CalibrationBundle): String {
        val json = JSONObject().apply {
            put("dev", bundle.device)
            put("n", bundle.lensCount)
            put(
                "lenses",
                JSONArray().apply {
                    bundle.lenses.forEach { lens ->
                        put(
                            JSONObject().apply {
                                put("p", lens.position)
                                put(
                                    "pts",
                                    JSONArray().apply {
                                        lens.points.forEach { punto ->
                                            // Array di valori e non oggetto: su un
                                            // QR ogni carattere conta, e le chiavi
                                            // si ripeterebbero per ogni punto.
                                            put(
                                                JSONArray().apply {
                                                    put(punto.distanceM.toDouble())
                                                    put(punto.pixels.toDouble())
                                                    put(punto.knownSizeM.toDouble())
                                                    put(punto.axis)
                                                    put(punto.zoom.toDouble())
                                                    put(punto.frameLongSide)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }.toString()
        return "$PREFIX:${checksum(json)}:$json"
    }

    /**
     * Legge un testo esportato. Null se non e' un export di Miraglio, se la
     * somma di controllo non torna (testo tagliato o letto male) o se dentro
     * c'e' un valore che non puo' essere una misura.
     *
     * Non solleva eccezioni apposta: chi importa deve poter dire "questo testo
     * non e' leggibile" guardando il risultato, non gestendo un errore.
     */
    fun decode(text: String): CalibrationBundle? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$PREFIX:")) return null
        val resto = trimmed.removePrefix("$PREFIX:")
        val separatore = resto.indexOf(':')
        if (separatore <= 0) return null
        val somma = resto.substring(0, separatore)
        val json = resto.substring(separatore + 1)
        if (checksum(json) != somma) return null
        return runCatching { parse(json) }.getOrNull()
    }

    /**
     * Mappa le lenti del file su quelle di questo telefono, per **posizione**:
     * la prima con la prima, la seconda con la seconda. Gli id delle fotocamere
     * non sono confrontabili fra telefoni, l'ordine si'.
     *
     * Una lente del file che qui non esiste resta con [MappedLens.localIndex]
     * null: viene **saltata e dichiarata**, non appoggiata a un'altra lente —
     * scriverla sulla lente sbagliata sarebbe peggio che non importarla.
     */
    fun mapLenses(bundle: CalibrationBundle, localLensCount: Int): List<MappedLens> =
        bundle.lenses.map { lens ->
            MappedLens(
                fromPosition = lens.position,
                localIndex = (lens.position - 1).takeIf { it in 0 until localLensCount },
                points = lens.points
            )
        }

    private fun checksum(json: String): String {
        val crc = CRC32()
        crc.update(json.toByteArray(Charsets.UTF_8))
        return crc.value.toString(16)
    }

    private fun parse(json: String): CalibrationBundle {
        val root = JSONObject(json)
        // Marca e modello sono obbligatori: chi importa una calibrazione deve
        // poter sapere da che telefono arriva, e un file che non lo dice non e'
        // un export di Miraglio (o e' stato ripulito strada facendo).
        val device = root.getString("dev").trim()
        require(device.isNotEmpty()) { "no device" }
        val lensCount = root.getInt("n")
        val array = root.getJSONArray("lenses")

        val lensi = buildList {
            for (i in 0 until array.length()) {
                val lens = array.getJSONObject(i)
                val position = lens.getInt("p")
                require(position >= 1) { "lens $position" }
                val punti = lens.getJSONArray("pts")
                val points = buildList {
                    for (j in 0 until punti.length()) {
                        add(pointFrom(punti.getJSONArray(j)))
                    }
                }
                require(points.isNotEmpty()) { "lens $position without points" }
                add(TransferLens(position = position, points = points))
            }
        }
        require(lensi.isNotEmpty()) { "no lenses" }
        return CalibrationBundle(device = device, lensCount = lensCount, lenses = lensi)
    }

    /**
     * Un punto dal file, con i controlli su quello che puo' essere una misura.
     * Un valore fuori scala qui non e' un dettaglio da tollerare: e' la
     * differenza fra una calibrazione e un numero inventato.
     */
    private fun pointFrom(array: JSONArray): TransferPoint {
        val distanceM = array.getDouble(0).toFloat()
        val pixels = array.getDouble(1).toFloat()
        val knownSizeM = array.getDouble(2).toFloat()
        val axis = array.getString(3)
        val zoom = array.getDouble(4).toFloat()
        val frameLongSide = array.getInt(5)

        require(distanceM > 0f && distanceM.isFinite()) { "distance $distanceM" }
        require(pixels > 0f && pixels.isFinite()) { "pixels $pixels" }
        require(knownSizeM > 0f && knownSizeM.isFinite()) { "known size $knownSizeM" }
        require(zoom > 0f && zoom.isFinite()) { "zoom $zoom" }
        require(frameLongSide > 0) { "frame long side $frameLongSide" }
        require(axis == "H" || axis == "V") { "axis $axis" }

        return TransferPoint(
            distanceM = distanceM,
            pixels = pixels,
            knownSizeM = knownSizeM,
            axis = axis,
            zoom = zoom,
            frameLongSide = frameLongSide
        )
    }
}
