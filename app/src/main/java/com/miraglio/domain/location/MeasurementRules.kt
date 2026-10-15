package com.miraglio.domain.location

import android.location.Location

/**
 * Fix GPS ridotto ai valori che servono alla misura a piedi. Non e' un
 * Location perche' e' anche il tipo su cui girano le regole di
 * [MeasurementRules]: cosi' le regole si verificano senza telefono.
 */
data class WalkFix(
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Float,
    /** Istante del fix sull'orologio monotono di Android (elapsedRealtime). */
    val elapsedMs: Long,
    /** Velocita' dichiarata dal GNSS, null se il provider non la fornisce. */
    val speedMps: Float? = null
) {
    companion object {
        fun from(location: Location): WalkFix = WalkFix(
            latDeg = location.latitude,
            lonDeg = location.longitude,
            accuracyM = location.accuracy,
            elapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
            speedMps = if (location.hasSpeed()) location.speed else null
        )
    }
}

/**
 * Regole con cui la misura a piedi accetta o scarta i fix GPS.
 *
 * Stanno qui, fuori dal ViewModel, perche' decidono il risultato: qui si
 * verificano con valori scritti a mano (MeasurementRulesTest).
 *
 * Cosa correggono:
 *  - la partenza veniva presa dal fix "in cache" del tracker, che dopo uno
 *    stop e' quello di prima (o un fix di rete): partendo 20 m indietro la
 *    distanza usciva sbagliata e per difetto fin dal primo passo. Ora una
 *    partenza si blocca solo su un fix satellitare di adesso, accurato e
 *    confermato dal fix successivo;
 *  - i passi sotto il metro (GPS a 2 Hz) sparivano dal percorso a piedi.
 *    Ora si somma ogni spostamento plausibile e si scartano solo rumore da
 *    fermo, salti impossibili e buchi negli aggiornamenti.
 */
object MeasurementRules {

    /** Oltre questa eta' un fix non e' piu' "di adesso". */
    const val MAX_FIX_AGE_MS = 5_000L

    /** Accuratezza richiesta per bloccare (e confermare) la partenza. */
    const val START_MAX_ACCURACY_M = 10f

    /** Distanza entro cui il fix successivo conferma la partenza. */
    const val START_CONFIRM_DISTANCE_M = 8f

    /**
     * Dopo questo tempo senza un fix buono si parte comunque, con
     * l'accuratezza effettiva dichiarata nel messaggio: meglio una misura
     * con errore noto che una misura impossibile.
     */
    const val START_GIVE_UP_MS = 45_000L

    /** Accuratezza massima per continuare a misurare (passi e chiusura). */
    const val TRACK_MAX_ACCURACY_M = 20f

    /** Sotto questa distanza il movimento e' rumore del fix, non un passo. */
    const val STEP_NOISE_FLOOR_M = 0.4f

    /** Sopra questa velocita' e' un salto del fix, non una camminata. */
    const val MAX_WALK_SPEED_MPS = 3f

    /** Sotto questa velocita' dichiarata dal GNSS il fix e' da fermo. */
    const val MIN_MOVING_SPEED_MPS = 0.3f

    /** Buco negli aggiornamenti oltre il quale il tratto non e' un passo. */
    const val MAX_STEP_GAP_MS = 10_000L

    /**
     * Vero se [fix] puo' essere usato come partenza o arrivo: accurato quanto
     * basta e non piu' vecchio di [MAX_FIX_AGE_MS]. L'eta' si misura
     * sull'orologio monotono, non su quello di sistema: un cambio d'ora non
     * fa diventare "fresco" un fix vecchio.
     */
    fun isUsable(fix: WalkFix, nowElapsedMs: Long, maxAccuracyM: Float): Boolean {
        if (fix.accuracyM <= 0f || fix.accuracyM > maxAccuracyM) return false
        return nowElapsedMs - fix.elapsedMs <= MAX_FIX_AGE_MS
    }

    /** Distanza in metri fra due fix (Haversine). */
    fun distanceM(a: WalkFix, b: WalkFix): Float =
        Haversine.distanceMeters(a.latDeg, a.lonDeg, b.latDeg, b.lonDeg).toFloat()

    /**
     * Metri da sommare al percorso a piedi fra [previous] e [current], o null
     * se il tratto non e' un passo: fix scadenti, rumore da fermo, salto
     * impossibile per la velocita' di camminata o buco negli aggiornamenti.
     */
    fun stepMeters(previous: WalkFix, current: WalkFix): Float? {
        if (previous.accuracyM > TRACK_MAX_ACCURACY_M ||
            current.accuracyM > TRACK_MAX_ACCURACY_M
        ) {
            return null
        }
        val dtMs = current.elapsedMs - previous.elapsedMs
        if (dtMs <= 0L || dtMs > MAX_STEP_GAP_MS) return null

        val distance = distanceM(previous, current)
        if (distance < STEP_NOISE_FLOOR_M) return null
        val speed = distance / (dtMs / 1000f)
        if (speed > MAX_WALK_SPEED_MPS) return null

        // Se il GNSS dichiara la velocita', un fix da fermo dice che lo
        // spostamento e' rumore del fix: non deve gonfiare il percorso.
        val declaredSpeed = current.speedMps
        if (declaredSpeed != null && declaredSpeed < MIN_MOVING_SPEED_MPS) return null

        return distance
    }
}
