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
     * Banda `[Δt più corto, Δt più lungo]` per [model] e per le distanze
     * [distancesM] del profilo, oppure `null` se non c'è una base fisica.
     *
     * Le distanze sono quelle dei tiri di calibrazione: il campo di tiro su cui
     * l'arciere ha misurato il proprio arco. Servono almeno due distanze
     * **diverse** (arrotondate al decimetro, come fa il fit): con una sola non
     * c'è un modello calibrato, e la banda sarebbe un punto senza senso.
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

        val vicina = BallisticCalculator.flightTime(valide.min(), model, airTempC)
        val lontana = BallisticCalculator.flightTime(valide.max(), model, airTempC)
        if (!vicina.isFinite() || !lontana.isFinite() || vicina <= 0f || lontana <= vicina) {
            return null
        }
        return vicina..lontana
    }
}
