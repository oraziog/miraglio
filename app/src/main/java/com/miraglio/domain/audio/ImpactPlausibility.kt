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
 * **Tiro di campagna H&S: distanza sconosciuta, 5–50 m.** Nel tiro di campagna
 * l'arciere non sa a che distanza sta tirando, quindi la banda delle sole
 * distanze di calibrazione è troppo stretta: chi ha calibrato a 15 e 25 m e si
 * trova un bersaglio a 45 m vedrebbe il veto scartare l'impatto vero. Quando
 * il campo è dichiarato ([FIELD_RANGE_M]) la banda si allarga fino a
 * comprendere quei 5–50 m: il minimo è il tempo di volo a 5 m, il massimo
 * quello a 50 m. Il veto resta un veto fisico — un impatto che tornerebbe da
 * 3 m o da 60 m non è il battifreccia — ma non pretende più che il bersaglio
 * sia dentro il campo di calibrazione.
 *
 * **Degradazione esplicita.** `null` significa "nessun veto": il motore torna
 * al comportamento di prima (sceglie il candidato plausibile più forte senza
 * banda). Succede quando il profilo non è calibrato (modello assente o non
 * valido) o quando le distanze note sono meno di due e nessun campo è stato
 * dichiarato: in quel caso non esiste una finestra fisica, e inventarne una —
 * per esempio assumendo una velocità tipica — sposterebbe la scelta su un
 * transiente qualsiasi presentandola come misura. Meglio nessun veto che un
 * veto falso.
 */
object ImpactPlausibility {

    /**
     * Il campo di tiro del tiro di campagna H&S: bersagli a distanza
     * sconosciuta fra 5 e 50 m.
     *
     * Non è una preferenza dell'utente ma la regola della disciplina, ed è il
     * motivo per cui la finestra di ricerca dell'impatto non può più partire
     * fissa da 0,15 s: a 5 m il battifreccia torna in ~0,06 s.
     */
    val FIELD_RANGE_M: ClosedFloatingPointRange<Float> = 5f..50f

    /**
     * Banda `[Δt più corto, Δt più lungo]` per [model], per le distanze
     * [distancesM] del profilo e per [fieldRangeM], oppure `null` se non c'è
     * una base fisica.
     *
     * Le distanze sono quelle dei tiri di calibrazione: il campo di tiro su cui
     * l'arciere ha misurato il proprio arco. Servono almeno due distanze
     * **diverse** (arrotondate al decimetro, come fa il fit) — a meno che il
     * campo non sia dichiarato: allora la banda ha già i suoi due estremi e le
     * distanze di calibrazione servono solo ad allargarla se cadono fuori
     * (per esempio un profilo calibrato a 65–75 m).
     */
    fun band(
        model: ArrowSpeedModel?,
        distancesM: Collection<Float>,
        airTempC: Float,
        fieldRangeM: ClosedFloatingPointRange<Float>? = null
    ): ClosedFloatingPointRange<Float>? {
        if (model == null || !model.isValid || !airTempC.isFinite()) return null
        val valide = distancesM.filter { it.isFinite() && it > 0f }
        val campo = fieldRangeM?.takeIf {
            it.start.isFinite() && it.endInclusive.isFinite() &&
                it.start > 0f && it.endInclusive > it.start
        }

        if (campo == null) {
            val distinte = valide.map { (it * 10f).toInt() }.distinct()
            if (distinte.size < BallisticCalculator.MIN_CALIBRATION_DISTANCES) return null
        }

        val estremi = buildList {
            addAll(valide)
            if (campo != null) {
                add(campo.start)
                add(campo.endInclusive)
            }
        }
        if (estremi.isEmpty()) return null

        val vicina = BallisticCalculator.flightTime(estremi.min(), model, airTempC)
        val lontana = BallisticCalculator.flightTime(estremi.max(), model, airTempC)
        if (!vicina.isFinite() || !lontana.isFinite() || vicina <= 0f || lontana <= vicina) {
            return null
        }
        return vicina..lontana
    }
}
