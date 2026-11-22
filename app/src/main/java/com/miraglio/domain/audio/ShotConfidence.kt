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
 * - [MEDIUM] — il numero esiste ma manca qualcosa: l'impatto è un grave senza
 *   acuto coincidente (poteva essere una voce), oppure è un urto che cade
 *   appena **oltre** il campo di calibrazione, dentro la sola tolleranza di
 *   coda del picco d'inviluppo. Nel secondo caso il profilo dice che il tempo
 *   è quello di un bersaglio un po' più lontano del campo misurato: la
 *   distanza c'è, ma è una stima, non un valore dentro il campo.
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
    /** Impatto a banda larga dentro la finestra fisica. */
    OK,

    /** Impatto grave senza acuto coincidente: poteva essere rumore. */
    NO_BROADBAND_PROOF,

    /**
     * Urto appena oltre il campo di calibrazione, preso per la coda del picco
     * d'inviluppo: coerente con il profilo, ma fuori dal campo misurato.
     */
    IMPACT_JUST_OUTSIDE_FIELD,

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
         * L'ordine dei controlli è di gravità: prima il ripiego fuori banda,
         * poi la mancanza di prova a banda larga.
         */
        fun evaluate(evidence: ImpactEvidence): ShotQuality = when (evidence) {
            ImpactEvidence.OUT_OF_BAND ->
                ShotQuality(ShotConfidence.LOW, ShotConfidenceReason.IMPACT_OUT_OF_BAND)

            ImpactEvidence.NEAR_BAND ->
                ShotQuality(ShotConfidence.MEDIUM, ShotConfidenceReason.IMPACT_JUST_OUTSIDE_FIELD)

            ImpactEvidence.LOW_IN_BAND ->
                ShotQuality(ShotConfidence.MEDIUM, ShotConfidenceReason.NO_BROADBAND_PROOF)

            else -> ShotQuality(ShotConfidence.HIGH, ShotConfidenceReason.OK)
        }
    }
}
