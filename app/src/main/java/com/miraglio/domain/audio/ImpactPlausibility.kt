package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator

/**
 * La finestra di Δ (scocco→impatto) in cui un impatto può stare, ricavata dal
 * **profilo arco** invece che da una costante.
 *
 * Serve perché la vecchia banda fissa 1,0–2,0 s descriveva un arco a ~70 m e
 * nient'altro: su un barebow indoor a 18 m (Δ ≈ 0,42 s) il veto non si
 * applicava mai e il motore ripiegava su un transiente più tardi. La banda
 * giusta è quella che il profilo già contiene: il modello calibrato (`v_f` e
 * decadimento) e il campo di tiro su cui l'arciere ha calibrato, cioè le
 * distanze dei suoi tiri di calibrazione. Il tempo minimo e massimo si
 * calcolano con [BallisticCalculator.flightTime], cioè con la stessa fisica
 * della misura, non con una seconda formula.
 *
 * **Degradazione esplicita.** `null` significa "nessun veto": il motore torna
 * al comportamento di prima (sceglie il candidato plausibile più forte senza
 * banda). Succede quando il profilo non è calibrato (modello assente o non
 * valido) o quando le distanze note sono meno di due: in quel caso non esiste
 * una finestra fisica, e inventarne una — per esempio assumendo una velocità
 * tipica — sposterebbe la scelta su un transiente qualsiasi presentandola come
 * misura. Meglio nessun veto che un veto falso.
 */
object ImpactPlausibility {

    /**
     * Le velocità fra cui può stare una freccia: 20–120 m/s.
     *
     * Coprono dal longbow lento alla compound da 100 libbre, cioè tutto quello
     * che può essere teso con le braccia. Non è una stima della velocità
     * dell'arco — quella la dà la calibrazione — ma l'intervallo dentro cui un
     * tempo di volo è **fisicamente** possibile; fuori, il transiente non è il
     * battifreccia.
     */
    val ARROW_SPEED_RANGE_MS: ClosedFloatingPointRange<Float> = 20f..120f

    /**
     * Banda di Δ per un tiro a **distanza nota** ma senza modello calibrato:
     * è il caso della calibrazione, dove la distanza la scrive l'arciere e il
     * modello è proprio quello che si sta costruendo.
     *
     * Serve perché la finestra fissa dei 0,15 s perde il tiro corto: a 5 m il
     * battifreccia torna in 0,10–0,11 s, ma il motore non lo cercava prima di
     * 0,15 e finiva sul rimbombo (0,175 s misurati, 31 m/s contro i 54 veri: il
     * modello calibrato usciva a 17,4 m/s e ogni tiro a 18 m tornava "sei
     * metri").
     *
     * Aprire invece la ricerca a 0,02 s per tutti è peggio: sui tiri a 18 m del
     * 21/11/2026 due file su cinque cronometravano l'onda della coda dello
     * scocco (0,038–0,043 s). Con questa banda quei candidati restano fuori e
     * vincono gli attacchi veri (0,385 s).
     *
     * `null` se la distanza o la temperatura non sono numeri utilizzabili.
     */
    fun bandAtKnownDistance(distanceM: Float, airTempC: Float): ClosedFloatingPointRange<Float>? {
        if (!distanceM.isFinite() || distanceM <= 0f) return null
        if (!airTempC.isFinite()) return null
        val rapidissima = flightTimeAtConstantSpeed(distanceM, ARROW_SPEED_RANGE_MS.endInclusive, airTempC)
        val lentissima = flightTimeAtConstantSpeed(distanceM, ARROW_SPEED_RANGE_MS.start, airTempC)
        if (!rapidissima.isFinite() || !lentissima.isFinite()) return null
        if (rapidissima <= 0f || lentissima <= rapidissima) return null
        return rapidissima..lentissima
    }

    /** Tempo di volo a velocità costante, con lo stesso conto della misura. */
    private fun flightTimeAtConstantSpeed(
        distanceM: Float,
        speedMs: Float,
        airTempC: Float
    ): Float = BallisticCalculator.flightTime(distanceM, ArrowSpeedModel(speedMs, 0f), airTempC)

    /**
     * Banda `[Δt più corto, Δt più lungo]` per [model] alle distanze
     * [distancesM] del profilo, oppure `null` se non c'è una base fisica.
     *
     * Le distanze sono quelle dei tiri di calibrazione: il campo di tiro su cui
     * l'arciere ha misurato il proprio arco. Servono almeno due distanze
     * **diverse** (arrotondate al decimetro, come fa il fit).
     */
    fun band(
        model: ArrowSpeedModel?,
        distancesM: Collection<Float>,
        airTempC: Float
    ): ClosedFloatingPointRange<Float>? {
        if (model == null || !model.isValid || !airTempC.isFinite()) return null
        val valide = distancesM.filter { it.isFinite() && it > 0f }
        val distinte = valide.map { (it * 10f).toInt() }.distinct()
        if (distinte.size < BallisticCalculator.MIN_CALIBRATION_DISTANCES) return null
        if (valide.isEmpty()) return null

        val vicina = BallisticCalculator.flightTime(valide.min(), model, airTempC)
        val lontana = BallisticCalculator.flightTime(valide.max(), model, airTempC)
        if (!vicina.isFinite() || !lontana.isFinite() || vicina <= 0f || lontana <= vicina) {
            return null
        }
        return vicina..lontana
    }
}
