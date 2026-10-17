package com.miraglio.domain.reticle

import kotlin.math.tan

/** Un MOA in radianti: un sessantesimo di grado (un MOA e' 1/60 di grado). */
private const val RADIANS_PER_MOA = 0.0002908882f

/**
 * Quanti MOA vale un pollice visto a 20 iarde: e' l'unita' con cui sono
 * tarati i mirini ("in/20yd", IPHY). Un pollice a 720 pollici sottende 1/720
 * di radiante, cioe' 4,7746 MOA.
 */
const val MOA_PER_INCH_AT_20_YARDS = 4.7746483f

/**
 * Dimensione angolare di un bersaglio: quanto "copre" nel mirino.
 *
 * E' la grandezza che serve all'arciere per confrontare il bersaglio con le
 * tacche del proprio mirino, e non dipende dalla fotocamera: dipende solo da
 * quanto e' grande il bersaglio e da quanto e' lontano.
 */
data class AngularSize(
    /** Dimensione in MOA (1 MOA = 1/60 di grado). */
    val moa: Float,
    /** La stessa dimensione scritta come la leggono i mirini: pollici a 20 iarde. */
    val inchesAt20Yards: Float
)

/**
 * Dimensione angolare di un bersaglio di [targetSizeM] metri a [distanceM]
 * metri. Null se i due numeri non sono utilizzabili (servono positivi e
 * finiti): senza bersaglio o senza distanza non c'e' nessun angolo da mostrare.
 *
 * L'angolo e' quello pieno, `2·atan(dimensione/2 / distanza)`: e' la stessa
 * geometria con cui l'app stima la distanza (modello pinhole, raggi dal centro
 * dell'obiettivo), quindi le due cose restano coerenti.
 */
fun angularSize(targetSizeM: Float, distanceM: Float): AngularSize? {
    if (!targetSizeM.isFinite() || !distanceM.isFinite()) return null
    if (targetSizeM <= 0f || distanceM <= 0f) return null

    // Il conto si fa in Double e si riporta a Float: atan di un angolo piccolo
    // e' il punto in cui i Float perdono cifre, e il risultato finale (MOA)
    // resta comunque un numero che si legge a occhio.
    val radians = (2.0 * kotlin.math.atan(targetSizeM.toDouble() / (2.0 * distanceM))).toFloat()
    return AngularSize(
        moa = radians / RADIANS_PER_MOA,
        // 20 iarde = 720 pollici: la dimensione che, a quella distanza, copre
        // lo stesso angolo.
        inchesAt20Yards = 720f * tan(radians)
    )
}

/** Pixel di immagine per ogni MOA, da una focale in pixel. */
fun pixelsPerMoa(focalPx: Float): Float? =
    if (focalPx.isFinite() && focalPx > 0f) focalPx * tan(RADIANS_PER_MOA) else null

/**
 * Passi ammessi per le tacche, in MOA. Sono i numeri che si contano a occhio:
 * una scala che dice "ogni 37 MOA" non si legge.
 */
private val STEPS_MOA = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500)

/** Le tacche da disegnare sul mirino, gia' decise. */
data class ReticleScale(
    /** MOA fra una tacca e la successiva. */
    val stepMoa: Int,
    /** Quanti pixel separano due tacche sull'immagine a schermo. */
    val stepPx: Float,
    /** Lo stesso passo scritto nell'altra unita': pollici a 20 iarde. */
    val stepInchesAt20Yards: Float
)

/**
 * Scala da disegnare: il passo piu' fine la cui spaziatura a schermo arriva
 * almeno a [minStepPx].
 *
 * Il passo NON e' fisso, ed e' una scelta obbligata: a 1x una fotocamera di
 * telefono ha una focale di circa 1200 px, quindi 1 MOA vale **0,35 px** e
 * tacche da 1 MOA sarebbero invisibili — disegnarle comunque vorrebbe dire
 * mostrare una scala fina che non misura niente. Qui il passo si allarga
 * finche' la scala si vede, e accanto c'e' scritto quanto vale: cosi' una scala
 * grossolana e' dichiarata invece che finta. Zoomando, la focale effettiva
 * cresce e il passo si stringe da solo.
 */
fun reticleScale(pxPerMoa: Float, minStepPx: Float): ReticleScale? {
    if (!pxPerMoa.isFinite() || pxPerMoa <= 0f || minStepPx <= 0f || !minStepPx.isFinite()) {
        return null
    }
    val step = STEPS_MOA.firstOrNull { it * pxPerMoa >= minStepPx } ?: STEPS_MOA.last()
    return ReticleScale(
        stepMoa = step,
        stepPx = step * pxPerMoa,
        stepInchesAt20Yards = step / MOA_PER_INCH_AT_20_YARDS
    )
}
