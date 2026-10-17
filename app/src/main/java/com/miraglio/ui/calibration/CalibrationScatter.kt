package com.miraglio.ui.calibration

import com.miraglio.data.focal.FocalPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Un punto sul grafico della calibrazione. */
data class ScatterPoint(
    val id: Long,
    val distanceM: Float,
    /** Quanta focale implicherebbe questo punto (px del frame di analisi). */
    val focalPx: Float,
    /** true: sta lontano dalla retta di piu' della tolleranza. */
    val outlier: Boolean,
    /** Dimensione nota e asse del punto: servono per riproporre la misura. */
    val knownSizeM: Float,
    /** "H" o "V", come su `FocalPoint.axis`. */
    val axis: String
)

/**
 * Il grafico gia' calcolato, pronto da disegnare.
 *
 * La retta `f = a·distanza + b` e' un'approssimazione lineare della focale in
 * funzione della distanza: con i punti sani dovrebbe stare quasi piatta, e se
 * pende e' un segnale (una distanza sbagliata sistematica, uno zoom non
 * compensato).
 */
data class CalibrationScatter(
    val points: List<ScatterPoint>,
    val slopePxPerMeter: Float,
    val interceptPx: Float,
    /** Scostamento tollerato dalla retta, come frazione del valore della retta. */
    val tolerance: Float
) {
    val outliers: List<ScatterPoint> get() = points.filter { it.outlier }

    /**
     * Scostamento relativo massimo dalla retta: dice quanto e' "stretta" la
     * calibrazione anche quando nessun punto supera la tolleranza. Con punti
     * tutti vicini il numero e' basso; se si avvicina al 10% conviene rifare
     * le misure piu' scostate, prima che diventino indiziati.
     */
    val maxDeviation: Float
        get() = points.maxOfOrNull { p ->
            val attesa = expectedFocalAt(p.distanceM)
            if (attesa > 0f) abs(p.focalPx - attesa) / attesa else 0f
        } ?: 0f

    fun expectedFocalAt(distanceM: Float): Float = slopePxPerMeter * distanceM + interceptPx
}

/**
 * Grafico dei punti di calibrazione: scatter + retta + indiziati.
 *
 * **Perche' non e' pixel contro distanza**, come sembrerebbe naturale. Il modello
 * pinhole dice `pixel = focale × dimensione / distanza`: a distanze diverse lo
 * stesso bersaglio occupa pixel diversi, quindi in pixel-vs-distanza i punti
 * giusti stanno su un'**iperbole** e nessuna retta ci sta dentro. In piu' i
 * punti possono avere dimensioni note e zoom diversi fra loro: due misure
 * corrette non sono neppure confrontabili in pixel grezzi.
 *
 * Ogni punto viene quindi riportato a cio' che deve essere **costante**: la
 * focale implicita `f = pixel × distanza / (dimensione × zoom)`. In quell'asse
 * i punti giusti cadono su una retta orizzontale (la focale non dipende dalla
 * distanza: e' il motivo per cui si puo' calibrare una volta sola), e un punto
 * misurato male salta agli occhi perche' la sua focale "impossibile" non
 * combina con le altre.
 *
 * La retta si fa in **due passi** proprio perche' servono gli indiziati:
 *
 * 1. si calcola la **mediana** delle focali (la mediana non si muove se un
 *    punto e' fuori, mentre un fit diretto verrebbe trascinato dal punto
 *    sbagliato — e' esattamente l'effetto che maschera l'errore);
 * 2. solo sui punti vicini alla mediana si fa il fit ai minimi quadrati, e
 *    contro quella retta si segnalano gli scostamenti oltre [tolerance].
 *
 * Stesso contratto di `reticleScale`: e' una funzione pura, provabile senza
 * telefono (vedi CalibrationScatterTest).
 */
fun calibrationScatter(
    points: List<FocalPoint>,
    tolerance: Float = DEFAULT_OUTLIER_TOLERANCE
): CalibrationScatter? {
    val validi = points.mapNotNull { punto ->
        val focale = punto.impliedFocalPx
        if (punto.distanceM > 0f && punto.distanceM.isFinite() &&
            focale > 0f && focale.isFinite()
        ) {
            ScatterPoint(
                id = punto.id,
                distanceM = punto.distanceM,
                focalPx = focale,
                outlier = false,
                knownSizeM = punto.knownSizeM,
                axis = punto.axis
            )
        } else {
            null
        }
    }.sortedBy { it.distanceM }

    if (validi.isEmpty()) return null

    val mediana = median(validi.map { it.focalPx })
    val sani = validi.filter { abs(it.focalPx - mediana) <= mediana * tolerance }
    // Con almeno due punti sani la retta si costruisce solo su quelli: e' il
    // filtro che impedisce all'indiziato di trascinarla. Con meno di due non
    // c'e' una base robusta (due punti devono passarci entrambi, altrimenti
    // si accuserebbe uno dei due senza dati), e la retta si fa su tutti.
    val (slope, intercept) = leastSquares(if (sani.size >= 2) sani else validi)

    val punti = validi.map { p ->
        val attesa = slope * p.distanceM + intercept
        val scostamento = if (attesa > 0f) abs(p.focalPx - attesa) / attesa else 0f
        p.copy(outlier = scostamento > tolerance)
    }
    return CalibrationScatter(
        points = punti,
        slopePxPerMeter = slope,
        interceptPx = intercept,
        tolerance = tolerance
    )
}

/** Soglia di scostamento dalla retta oltre la quale un punto e' un indiziato. */
const val DEFAULT_OUTLIER_TOLERANCE = 0.10f

/**
 * Valori "tondi" da scrivere sotto l'asse della distanza, al massimo
 * [maxTicks] e tutti dentro `[min, max]`.
 *
 * Il passo si sceglie fra 1, 2 e 5 volte una potenza di dieci: sono i passi
 * che una persona legge senza fare conti (5, 10, 15), mentre un passo pari a
 * `(max-min)/3` darebbe etichette come "9,33" che non aiutano a riconoscere
 * il punto sulla scala.
 *
 * Con estremi che coincidono (una sola distanza, o asse non ancora aperto)
 * l'unica tacca sensata e' il valore stesso: le tacche non inventano un
 * intervallo che i dati non hanno.
 */
fun axisTicks(min: Float, max: Float, maxTicks: Int = 4): List<Float> {
    if (!min.isFinite() || !max.isFinite() || maxTicks < 2) return emptyList()
    // Estremi invertiti: non e' un asse con un valore solo, e' un intervallo
    // che non esiste. Disegnarci una tacca la metterebbe fuori dal grafico.
    if (max < min) return emptyList()
    if (max == min) return listOf(min)

    val span = (max - min).toDouble()
    // Passo "ideale": lo spazio diviso per il numero di intervalli. Si parte
    // da una potenza di dieci sotto quella del passo ideale e si sale (1, 2, 5,
    // 10, 20, 50...). Vince il primo passo che lascia **almeno due** tacche e
    // non piu' di [maxTicks].
    //
    // Non basta dividere lo spazio per il numero di intervalli: su un asse
    // largo 725 px (700-1400, un grafico vero) il passo ideale sarebbe ~240 e
    // il criterio "span / passo <= 3" accetterebbe 500, lasciando una tacca
    // sola a 1000. Con il conteggio reale dei multipli vince 200, che da'
    // 800, 1000, 1200 e 1400.
    val base = 10.0.pow(floor(log10(span / (maxTicks - 1))))
    val steps = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0).map { it * base }
    return steps.firstNotNullOfOrNull { step ->
        ticksFor(min, max, step, maxTicks).takeIf { it.size >= 2 }
    } ?: ticksFor(min, max, steps.last(), maxTicks).ifEmpty { listOf(min) }
}

/** Multipli di [step] dentro `[min, max]`, se non sono piu' di [maxTicks]. */
private fun ticksFor(min: Float, max: Float, step: Double, maxTicks: Int): List<Float> {
    val first = ceil(min / step).toLong()
    val last = floor(max / step).toLong()
    if (last < first) return emptyList()
    val count = last - first + 1
    if (count > maxTicks) return emptyList()
    return (first..last).map { (it * step).toFloat() }
}

/**
 * Cifre decimali da mostrare per un passo di [step]: un passo da 5 m si legge
 * "5", uno da 0,5 yd si legge "0,5".
 */
fun tickDecimals(step: Float): Int = when {
    !step.isFinite() || step <= 0f -> 0
    step >= 1f -> 0
    step >= 0.1f -> 1
    else -> 2
}

private fun median(values: List<Float>): Float {
    val ordinate = values.sorted()
    val mid = ordinate.size / 2
    return if (ordinate.size % 2 == 1) {
        ordinate[mid]
    } else {
        (ordinate[mid - 1] + ordinate[mid]) / 2f
    }
}

/**
 * Fit ai minimi quadrati `f = a·d + b`. Se le distanze sono tutte uguali
 * (varianza zero) la pendenza non e' calcolabile: si cade su una retta
 * piatta alla media, che e' comunque cio' che i dati dicono.
 */
private fun leastSquares(points: List<ScatterPoint>): Pair<Float, Float> {
    val n = points.size
    val meanX = points.map { it.distanceM }.average()
    val meanY = points.map { it.focalPx }.average()
    var varX = 0.0
    var covXY = 0.0
    points.forEach { p ->
        val dx = p.distanceM - meanX
        varX += dx * dx
        covXY += dx * (p.focalPx - meanY)
    }
    val slope = if (varX > 0.0) (covXY / varX).toFloat() else 0f
    return slope to (meanY - slope * meanX).toFloat()
}
