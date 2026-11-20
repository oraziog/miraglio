package com.miraglio.data.profile

import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.ballistics.BallisticPoint

/**
 * Un tiro registrato su un profilo arco.
 *
 * Le due informazioni che rendono un tiro utilizzabile per la calibrazione sono
 * **indipendenti**: la distanza (l'arciere sa a che tacca ha tirato) e il tempo
 * misurato dall'audio (che c'è solo se il battifreccia è stato sentito). Un
 * tiro senza tempo resta salvato — l'arciere l'ha fatto — ma non entra nel fit:
 * la distanza da sola non dice a che velocità è partita la freccia.
 *
 * [discarded] è la conferma dell'utente su un punto segnalato come sospetto
 * dal controllo outlier: finché non arriva, il punto resta nel fit.
 */
data class CalibrationShot(
    val id: Long,
    val distanceM: Float?,
    val totalTimeS: Float?,
    val airTempC: Float,
    val pitchDeg: Float,
    val discarded: Boolean = false,
    val timestampMs: Long = 0L
) {

    val hasValidTime: Boolean
        get() = totalTimeS != null && totalTimeS.isFinite() && totalTimeS > 0f

    val usableForFit: Boolean
        get() = !discarded && hasValidTime && distanceM != null &&
            distanceM.isFinite() && distanceM > 0f

    fun toBallisticPoint(): BallisticPoint? {
        if (!usableForFit) return null
        val distance = distanceM ?: return null
        val time = totalTimeS ?: return null
        return BallisticPoint(distance, time, airTempC)
    }
}

/**
 * Un profilo arco: il nickname che descrive la configurazione, le due
 * impostazioni del clicker e i tiri fatti con quell'arco.
 *
 * Il nickname è l'identità del profilo (non c'è nessun elenco chiuso di
 * configurazioni): "Olimpico 36#, riser X, flettenti Y" dice all'arciere tutto
 * quello che gli serve per riconoscere l'arco con cui ha calibrato.
 */
data class BowProfile(
    val id: String,
    val nickname: String,
    val useClicker: Boolean = false,
    val releaseOffsetMs: Float = 0f,
    val createdMs: Long = 0L,
    val shots: List<CalibrationShot> = emptyList()
) {

    val fitPoints: List<BallisticPoint>
        get() = shots.mapNotNull { it.toBallisticPoint() }

    /** Quante distanze **diverse** hanno un tiro utilizzabile. */
    val calibratedDistances: Int
        get() = fitPoints.map { (it.distanceM * 10f).toInt() }.distinct().size

    /** Il modello è utilizzabile solo con almeno due distanze di calibrazione. */
    val canMeasure: Boolean
        get() = calibratedDistances >= BallisticCalculator.MIN_CALIBRATION_DISTANCES

    /** Tiri salvati che non possono entrare nel fit (distanza o tempo mancanti). */
    val unusableShots: Int
        get() = shots.count { !it.usableForFit }
}
