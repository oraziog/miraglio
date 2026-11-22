package com.miraglio.ui.audio

import com.miraglio.data.profile.CalibrationShot
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.ballistics.BallisticPoint
import com.miraglio.domain.ballistics.SpeedOfSound
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Un tiro di calibrazione sul grafico. */
data class ShotCurvePoint(
    val id: Long,
    val distanceM: Float,
    /** Velocità che quel tiro implica a quella distanza: `d / (t_tot - d/v_s)`. */
    val speedMs: Float,
    /** Di quanti metri il tiro sta fuori dalla curva attesa (col segno). */
    val deviationM: Float,
    /** true: sta fuori dalla curva di più della tolleranza. */
    val outlier: Boolean,
    /** Tiro che l'arciere ha già scartato: resta visibile ma fuori dal modello. */
    val discarded: Boolean
)

/**
 * La curva di calibrazione già calcolata, pronta da disegnare.
 *
 * La linea `v(d) = v0 · e^(-k·d)` è il modello vero del telemetro: i tiri buoni
 * ci stanno sopra, e uno sbagliato resta lontano. È lo stesso modello con cui
 * l'app misura, quindi il grafico non racconta una storia diversa da quella
 * del numero che poi compare in Misura.
 */
data class AudioCalibrationCurve(
    val points: List<ShotCurvePoint>,
    val model: ArrowSpeedModel,
    /** Scarto in metri oltre il quale un tiro è un indiziato. */
    val toleranceM: Float
) {
    /**
     * I tiri da rifare. Un tiro già scartato non ci sta: l'utente ha deciso,
     * e chiederglielo di nuovo sarebbe un consiglio che non serve più.
     */
    val outliers: List<ShotCurvePoint> get() = points.filter { it.outlier }

    /**
     * Scarto massimo dalla curva fra i tiri che contano (non scartati): dice
     * quanto è "stretta" la calibrazione anche quando nessuno supera la
     * tolleranza.
     */
    val maxDeviationM: Float
        get() = points.filterNot { it.discarded }
            .maxOfOrNull { abs(it.deviationM) } ?: 0f

    fun expectedSpeedAt(distanceM: Float): Float = model.speedAt(distanceM)
}

/**
 * Velocità implicita da un tiro a distanza nota, togliendo dal tempo totale il
 * tempo che il rumore dell'impatto impiega a tornare al microfono.
 *
 * `null` se il tempo è fisicamente impossibile (più corto del solo suono):
 * quel tiro non si può disegnare perché non implica nessuna velocità, e
 * inventarne una lo farebbe sembrare sbagliato di un errore che non ha.
 */
fun impliedSpeedMs(distanceM: Float, totalTimeS: Float, airTempC: Float): Float? {
    if (!distanceM.isFinite() || distanceM <= 0f) return null
    if (!totalTimeS.isFinite() || totalTimeS <= 0f) return null
    val sound = SpeedOfSound.of(airTempC)
    if (sound <= 0f) return null
    val flight = totalTimeS - distanceM / sound
    if (!flight.isFinite() || flight <= 0f) return null
    return (distanceM / flight).takeIf { it.isFinite() && it > 0f }
}

/**
 * Il grafico dei tiri di calibrazione: velocità implicita contro distanza,
 * curva attesa in mezzo e tiri sospetti segnalati.
 *
 * **Perché non tempo contro distanza**, che sarebbe la lettura più diretta: il
 * tempo dipende dalla distanza in due modi (il volo e il ritorno del suono) e
 * cresce quasi linearmente, quindi in un grafico tempo-distanza anche un tiro
 * sbagliato di tre metri resta quasi sulla retta degli altri: l'errore si vede
 * solo come un pixel. Riportando ogni tiro a ciò che deve essere **coerente** —
 * la velocità che implica a quella distanza — un tiro sbagliato salta fuori:
 * quella velocità non sta sulla curva che gli altri tiri disegnano.
 *
 * **Perché serve un fit robusto.** La curva si costruisce due volte:
 *
 * 1. con lo stimatore di **Theil-Sen** (la mediana delle pendenze fra tutte le
 *    coppie di tiri): la mediana non si muove se un tiro è fuori, mentre un
 *    fit ai minimi quadrati verrebbe trascinato dal tiro sbagliato — è
 *    esattamente l'effetto che maschera l'errore;
 * 2. si rifà il fit classico solo sui tiri vicini alla prima curva, e contro
 *    quella definitiva si misurano gli scarti con la stessa soglia che l'app
 *    usa quando salva un tiro ([BallisticCalculator.DEFAULT_OUTLIER_THRESHOLD_M]).
 *
 * La mediana degli scarti *non* si può usare come nell'ottico: là la focale
 * doveva essere costante, qui la velocità cala con la distanza — confrontarla
 * con la mediana scarterebbe i tiri corti, che sono legittimamente più veloci.
 * Per questo la robustezza sta sulla pendenza, in scala logaritmica, dove la
 * curva è una retta.
 *
 * Stesso contratto del grafico ottico: funzione pura, provabile senza telefono
 * (vedi AudioCalibrationCurveTest). `null` quando non c'è una curva da
 * disegnare — meno di due tiri con tempo, o tutti alla stessa distanza.
 */
fun audioCalibrationCurve(
    shots: List<CalibrationShot>,
    toleranceM: Float = BallisticCalculator.DEFAULT_OUTLIER_THRESHOLD_M
): AudioCalibrationCurve? {
    val samples = shots.mapNotNull { shot ->
        val distance = shot.distanceM
        if (!shot.hasValidTime || distance == null) return@mapNotNull null
        val speed = impliedSpeedMs(distance, shot.totalTimeS ?: return@mapNotNull null, shot.airTempC)
            ?: return@mapNotNull null
        SpeedSample(shot, distance, speed)
    }
    // I tiri scartati restano sul grafico (in grigio) ma non costruiscono la
    // curva: sono fuori dal modello per decisione dell'arciere.
    val forFit = samples.filterNot { it.shot.discarded }
    if (forFit.size < 2) return null

    val robust = robustModel(forFit) ?: return null
    val healthy = forFit.filter { sample ->
        val deviation = deviationOf(robust, sample) ?: return@filter false
        abs(deviation) <= toleranceM
    }
    // Con almeno due tiri sani la curva definitiva si costruisce solo su
    // quelli: è il filtro che impedisce all'indiziato di trascinarla. Con meno
    // di due non c'è una base per un fit classico, e resta quella robusta.
    val model = fitOf(if (healthy.size >= 2) healthy else forFit) ?: robust

    val points = samples.map { sample ->
        val deviation = deviationOf(model, sample) ?: 0f
        ShotCurvePoint(
            id = sample.shot.id,
            distanceM = sample.distanceM,
            speedMs = sample.speedMs,
            deviationM = deviation,
            // Un tiro già scartato non è un indiziato: è fuori dal modello per
            // decisione dell'arciere, e resta sul grafico solo come traccia.
            outlier = !sample.shot.discarded && abs(deviation) > toleranceM,
            discarded = sample.shot.discarded
        )
    }.sortedBy { it.distanceM }

    return AudioCalibrationCurve(
        points = points,
        model = model,
        toleranceM = toleranceM
    )
}

private class SpeedSample(
    val shot: CalibrationShot,
    val distanceM: Float,
    val speedMs: Float
)

private fun deviationOf(model: ArrowSpeedModel, sample: SpeedSample): Float? {
    val totalTime = sample.shot.totalTimeS ?: return null
    return BallisticCalculator.deviationM(
        model,
        sample.distanceM,
        totalTime,
        sample.shot.airTempC
    )
}

private fun fitOf(samples: List<SpeedSample>): ArrowSpeedModel? =
    BallisticCalculator.fit(
        samples.map {
            BallisticPoint(it.distanceM, it.shot.totalTimeS ?: 0f, it.shot.airTempC)
        }
    )

/**
 * Curva robusta in scala logaritmica: `ln v = ln v0 - k·d`, quindi la pendenza
 * è `-k` e si stima con la mediana delle pendenze fra coppie di tiri.
 *
 * `null` se non ci sono due distanze diverse: senza, nessuna pendenza esiste e
 * la curva sarebbe un numero inventato.
 */
private fun robustModel(samples: List<SpeedSample>): ArrowSpeedModel? {
    val slopes = buildList {
        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                val dx = samples[i].distanceM - samples[j].distanceM
                if (abs(dx) < 1e-4f) continue
                val dy = ln(samples[i].speedMs.toDouble()) - ln(samples[j].speedMs.toDouble())
                add(dy / dx)
            }
        }
    }
    if (slopes.isEmpty()) return null

    val slope = median(slopes)
    // Pendenza positiva vorrebbe dire che la freccia accelera: è rumore, e il
    // decadimento si blocca a zero, come fa già `BallisticCalculator.fit`.
    val decay = (-slope).coerceAtLeast(0.0)
    val muzzle = if (decay > 0.0) {
        val intercept = median(
            samples.map { ln(it.speedMs.toDouble()) - slope * it.distanceM }
        )
        exp(intercept)
    } else {
        // Velocità costante: la costante robusta è la mediana delle velocità.
        samples.map { it.speedMs.toDouble() }.sorted().let { ordinate ->
            val mid = ordinate.size / 2
            if (ordinate.size % 2 == 1) ordinate[mid]
            else (ordinate[mid - 1] + ordinate[mid]) / 2.0
        }
    }
    if (!muzzle.isFinite() || muzzle <= 0.0) return null
    return ArrowSpeedModel(muzzleSpeedMs = muzzle.toFloat(), decayPerM = decay.toFloat())
}

private fun median(values: List<Double>): Double {
    val ordinate = values.sorted()
    val mid = ordinate.size / 2
    return if (ordinate.size % 2 == 1) {
        ordinate[mid]
    } else {
        (ordinate[mid - 1] + ordinate[mid]) / 2.0
    }
}
