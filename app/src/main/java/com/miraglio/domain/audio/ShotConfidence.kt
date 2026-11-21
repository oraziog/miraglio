package com.miraglio.domain.audio

/**
 * Quanta fiducia merita il numero mostrato: **verde**, **giallo**, **rosso**.
 *
 * Non è una probabilità inventata: ogni livello corrisponde a una cosa che il
 * motore ha davvero visto o non ha visto.
 *
 * - [HIGH] — l'impatto è stato scelto come picco d'inviluppo o come transiente
 *   a banda larga **dentro** la banda fisica. Sono i due casi in cui il
 *   battifreccia ha una firma propria (un urto eccita anche l'acuto) e non è
 *   solo "il transiente grave più forte della finestra".
 * - [MEDIUM] — il numero esiste ma manca una delle due conferme: l'impatto è
 *   un grave senza acuto coincidente (poteva essere una voce), oppure la
 *   distanza esce dal campo dichiarato 5–50 m (il modello sta estrapolando).
 * - [LOW] — nessun candidato cadeva nella banda fisica e il motore ha ripiegato
 *   sul transiente più forte. Il numero c'è, ma è il ripiego: va guardato, non
 *   usato per decidere la tacca.
 *
 * Il colore non cambia la fisica: `BallisticCalculator` calcola la stessa
 * distanza in tutti e tre i casi. Cambia solo quanto l'app è disposta a dire
 * "fidati".
 */
enum class ShotConfidence { HIGH, MEDIUM, LOW }

/**
 * Perché un tiro è verde, giallo o rosso. È un enum e non una frase perché la
 * traduzione sta nelle risorse: il dominio non conosce la lingua dell'utente.
 */
enum class ShotConfidenceReason {
    /** Impatto a banda larga e distanza dentro il campo dichiarato. */
    OK,

    /** Impatto grave senza acuto coincidente: poteva essere rumore. */
    NO_BROADBAND_PROOF,

    /** Distanza fuori dal campo 5–50 m: il modello sta estrapolando. */
    OUT_OF_FIELD_RANGE,

    /** Nessun candidato nella banda fisica: la scelta è un ripiego. */
    IMPACT_OUT_OF_BAND
}

data class ShotQuality(
    val confidence: ShotConfidence,
    val reason: ShotConfidenceReason
) {
    companion object {

        /**
         * Il giudizio sul tiro appena misurato.
         *
         * [fieldRangeM] è il campo in cui l'arciere sta tirando (5–50 m nel
         * tiro di campagna), oppure `null` quando non ne è stato dichiarato
         * nessuno: in quel caso non si può dire che la distanza sia "fuori
         * campo", e la discriminante resta solo la prova dell'impatto.
         *
         * L'ordine dei controlli è di gravità: prima il ripiego fuori banda,
         * poi l'estrapolazione, poi la mancanza di prova a banda larga.
         */
        fun evaluate(
            distanceM: Float,
            evidence: ImpactEvidence,
            fieldRangeM: ClosedFloatingPointRange<Float>?
        ): ShotQuality = when {
            evidence == ImpactEvidence.OUT_OF_BAND ->
                ShotQuality(ShotConfidence.LOW, ShotConfidenceReason.IMPACT_OUT_OF_BAND)

            fieldRangeM != null && distanceM.isFinite() && distanceM !in fieldRangeM ->
                ShotQuality(ShotConfidence.MEDIUM, ShotConfidenceReason.OUT_OF_FIELD_RANGE)

            evidence == ImpactEvidence.LOW_IN_BAND ->
                ShotQuality(ShotConfidence.MEDIUM, ShotConfidenceReason.NO_BROADBAND_PROOF)

            else -> ShotQuality(ShotConfidence.HIGH, ShotConfidenceReason.OK)
        }
    }
}
