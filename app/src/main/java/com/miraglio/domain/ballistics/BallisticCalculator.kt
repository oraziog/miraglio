package com.miraglio.domain.ballistics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln

/**
 * Velocità del suono in aria secca in funzione della temperatura.
 *
 * `v_s = 331.3 + 0.606 * T`, con T in gradi Celsius: a 20 °C sono 343.4 m/s.
 * E' il termine che rende il tempo di volo del suono non trascurabile: a 70 m
 * il suono impiega 204 ms contro gli 1.100 ms della freccia, cioè un quinto
 * del tempo totale. Ignorarlo sposterebbe la misura di circa 12 m.
 */
object SpeedOfSound {

    /** Temperatura usata quando non c'è né un valore manuale né il meteo. */
    const val FALLBACK_CELSIUS = 20f

    /** Oltre questi limiti il numero non è una temperatura dell'aria. */
    val RANGE = -40f..60f

    fun of(celsius: Float): Float = 331.3f + 0.606f * celsius
}

/**
 * Velocità della freccia in funzione della distanza: `v(d) = v0 * e^(-k*d)`.
 *
 * Il decadimento è esponenziale e non lineare perché la resistenza dell'aria
 * cresce con il quadrato della velocità, e la perdita per metro è quindi
 * proporzionale alla velocità che la freccia ha in quel momento. In pratica è
 * anche la forma che si adatta meglio a 3-4 distanze misurate.
 *
 * I due coefficienti si ricavano dai tiri di calibrazione ([BallisticCalculator.fit])
 * e non hanno un valore "giusto" a priori: dipendono da libbraggio, flettenti,
 * peso e forma della punta.
 */
data class ArrowSpeedModel(
    val muzzleSpeedMs: Float,
    val decayPerM: Float
) {
    fun speedAt(distanceM: Float): Float = muzzleSpeedMs * exp(-decayPerM * distanceM)

    val isValid: Boolean
        get() = muzzleSpeedMs.isFinite() && muzzleSpeedMs > 0f &&
            decayPerM.isFinite() && decayPerM >= 0f
}

/**
 * Un tiro a distanza nota, con il tempo totale scocco-impatto misurato
 * dall'audio. E' l'unità con cui si calibra il modello.
 */
data class BallisticPoint(
    val distanceM: Float,
    val totalTimeS: Float,
    val airTempC: Float
)

/**
 * La fisica del telemetro acustico, in Kotlin puro: nessun sensore, nessun
 * permesso, nessun Android. Tutto quello che si può sbagliare di questa
 * funzione si sbaglia qui, e qui si può provare su JVM.
 *
 * La relazione di partenza è una sola:
 *
 * ```
 * t_tot = d / v_f(d) + d / v_s
 * ```
 *
 * cioè il tempo che la freccia impiega ad arrivare più il tempo che il rumore
 * dell'impatto impiega a tornare al microfono. La distanza compare da entrambe
 * le parti (v_f dipende da d), quindi ricavarla è un punto fisso: si parte da
 * una stima grossolana e si itera finché non si muove più.
 */
object BallisticCalculator {

    /** Sotto due distanze diverse il modello ha troppi gradi di libertà. */
    const val MIN_CALIBRATION_DISTANCES = 2

    /**
     * Scarto oltre il quale un tiro calibrato viene segnalato come sospetto.
     * Tre metri a 70 m sono molto più di un errore di misura: sono un numero
     * inserito male o un tiro anomalo, e vanno guardati prima di entrare nel fit.
     */
    const val DEFAULT_OUTLIER_THRESHOLD_M = 3f

    /**
     * L'intervallo di Δ (scocco→impatto) in cui un bersaglio può stare. Fuori di
     * qui il transiente che il motore ha preso per impatto non è il
     * battifreccia, per quanto somigli a un urto.
     *
     * Da dove viene: dal modello calibrato, non da un numero scelto a mano. A
     * 20 °C, 1,0 s sono una cinquantina di metri e 2,0 s poco più di novanta —
     * sotto è il poligono coperto, sopra è oltre il tiro utile dell'arco da
     * caccia che questo telemetro serve. Sulle quaranta frecce vere a ~70 m i
     * tiri buoni stanno fra 1,407 e 1,558 s (68,5–70,7 m): la banda li contiene
     * tutti con due decimi di secondo di margine per lato, che è più del doppio
     * dell'incertezza misurata sui riferimenti (±80 ms).
     */
    val PLAUSIBLE_TOTAL_TIME_S: ClosedFloatingPointRange<Float> = 1.0f..2.0f

    private const val MAX_ITERATIONS = 12
    private const val MIN_DISTANCE_STEP_M = 0.01f

    /** Tempo totale atteso per una freccia che segue [model], a [distanceM]. */
    fun flightTime(distanceM: Float, model: ArrowSpeedModel, airTempC: Float): Float {
        if (distanceM <= 0f) return 0f
        val speed = model.speedAt(distanceM)
        if (!speed.isFinite() || speed <= 0f) return Float.NaN
        return distanceM / speed + distanceM / SpeedOfSound.of(airTempC)
    }

    /**
     * Distanza in linea d'aria dal tempo totale misurato, o `null` se il
     * profilo non è ancora calibrato.
     *
     * Il `null` è voluto: senza due distanze di calibrazione non esiste un
     * modello, e inventare una velocità di uscita tipica darebbe un numero
     * sbagliato di decine di metri presentato come misura.
     */
    fun distance(totalTimeS: Float, model: ArrowSpeedModel?, airTempC: Float): Float? {
        if (model == null || !model.isValid) return null
        if (!totalTimeS.isFinite() || totalTimeS <= 0f) return null
        val sound = SpeedOfSound.of(airTempC)
        if (sound <= 0f) return null

        // Prima stima: come se la freccia viaggiasse sempre alla velocità di
        // uscita. Erra per eccesso, e il punto fisso converge dall'alto.
        var distance = totalTimeS * model.muzzleSpeedMs
        repeat(MAX_ITERATIONS) {
            val speed = model.speedAt(distance)
            if (!speed.isFinite() || speed <= 0f) return null
            val next = totalTimeS / (1f / speed + 1f / sound)
            val step = abs(next - distance)
            distance = next
            if (step < MIN_DISTANCE_STEP_M) return@repeat
        }
        return distance.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Distanza orizzontale (quella che conta per il punteggio e per il
     * "quanto è lontano" sul campo) dalla distanza in linea d'aria e
     * dall'angolo di sito bloccato al puntamento.
     */
    fun horizontalDistance(distanceM: Float, pitchDeg: Float): Float =
        distanceM * cos(pitchDeg * PI.toFloat() / 180f)

    /**
     * Ricava `v0` e `k` dai tiri a distanza nota.
     *
     * Per ogni punto si toglie dal tempo totale il tempo del suono e si ottiene
     * la velocità **a quella distanza**: `v(d) = d / (t_tot - d/v_s)`. Passando
     * ai logaritmi la relazione esponenziale diventa una retta, e la retta si
     * fitta ai minimi quadrati — senza dipendenze, in tre righe di conti.
     *
     * `null` se i punti non bastano: serve almeno [MIN_CALIBRATION_DISTANCES]
     * distanze **diverse**, non due tiri alla stessa distanza.
     */
    fun fit(points: List<BallisticPoint>): ArrowSpeedModel? {
        val valid = points.filter {
            it.distanceM.isFinite() && it.distanceM > 0f &&
                it.totalTimeS.isFinite() && it.totalTimeS > 0f &&
                it.airTempC.isFinite()
        }
        if (valid.size < MIN_CALIBRATION_DISTANCES) return null
        if (valid.map { roundToDecimetre(it.distanceM) }.distinct().size < MIN_CALIBRATION_DISTANCES) {
            return null
        }

        val data = valid.mapNotNull { point ->
            val sound = SpeedOfSound.of(point.airTempC)
            val flight = point.totalTimeS - point.distanceM / sound
            if (!flight.isFinite() || flight <= 0f) return@mapNotNull null
            val speed = point.distanceM / flight
            if (!speed.isFinite() || speed <= 0f) return@mapNotNull null
            point.distanceM to ln(speed.toDouble())
        }
        if (data.size < MIN_CALIBRATION_DISTANCES) return null

        val n = data.size.toDouble()
        val sumX = data.sumOf { it.first.toDouble() }
        val sumY = data.sumOf { it.second }
        val sumXX = data.sumOf { it.first.toDouble() * it.first }
        val sumXY = data.sumOf { it.first.toDouble() * it.second }
        val denominator = n * sumXX - sumX * sumX
        if (abs(denominator) < 1e-9) return null

        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n
        val muzzle = exp(intercept)
        if (!muzzle.isFinite() || muzzle <= 0.0) return null

        // Una pendenza positiva direbbe che la freccia accelera: è rumore del
        // fit, non fisica. Si blocca a zero (velocità costante) invece di
        // restituire un modello che peggiora le misure.
        return ArrowSpeedModel(
            muzzleSpeedMs = muzzle.toFloat(),
            decayPerM = (-slope).coerceAtLeast(0.0).toFloat()
        )
    }

    /**
     * Di quanto il tiro si scosta dalla curva attuale, in metri.
     *
     * Serve al controllo outlier: un punto che si allontana di più della soglia
     * viene mostrato all'utente e resta fuori dal fit solo se lui conferma che
     * era sbagliato. Il segno dice da che parte sta lo scarto.
     */
    fun deviationM(
        model: ArrowSpeedModel,
        distanceM: Float,
        totalTimeS: Float,
        airTempC: Float
    ): Float? {
        if (!model.isValid || distanceM <= 0f) return null
        val predicted = flightTime(distanceM, model, airTempC)
        if (!predicted.isFinite() || predicted <= 0f) return null
        val speed = model.speedAt(distanceM)
        if (!speed.isFinite() || speed <= 0f) return null
        val sound = SpeedOfSound.of(airTempC)
        // Millisecondi di scarto -> metri: è la pendenza locale di t(d).
        return (totalTimeS - predicted) / (1f / speed + 1f / sound)
    }

    fun isOutlier(
        model: ArrowSpeedModel,
        distanceM: Float,
        totalTimeS: Float,
        airTempC: Float,
        thresholdM: Float = DEFAULT_OUTLIER_THRESHOLD_M
    ): Boolean {
        val deviation = deviationM(model, distanceM, totalTimeS, airTempC) ?: return false
        return abs(deviation) > thresholdM
    }

    private fun roundToDecimetre(value: Float): Int = (value * 10f).toInt()
}
