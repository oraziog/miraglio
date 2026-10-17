package com.miraglio.domain.focal

/**
 * Riga di riepilogo della calibrazione focale: quanti punti ha una lente e
 * dove sta nell'elenco delle posteriori ([position] e' 1-based, [lensCount] e'
 * il totale).
 *
 * [position] e' null quando la lente non compare piu' fra le posteriori del
 * dispositivo (tipico dei dati rimasti da un telefono precedente): in quel caso
 * la riga si etichetta con l'id grezzo, che e' scomodo ma non inventa un
 * numero che non corrisponde a nessuna lente.
 */
data class LensSummary(
    val lens: String,
    val position: Int?,
    val lensCount: Int,
    val points: Int,
    val isActive: Boolean
)

/**
 * Righe da mostrare, in ordine: la lente **attiva** per prima (e' quella che si
 * sta calibrando: se il riepilogo parte da un'altra, la prima riga mente), poi
 * le altre per numero di punti decrescente, e a pari punti nell'ordine delle
 * lenti del dispositivo.
 *
 * Si elencano solo le lenti con almeno un punto: una riga a zero non aggiunge
 * niente — quali lenti esistano lo dice gia' il selettore della fotocamera — e
 * riempirebbe la card di zeri su un telefono con quattro obiettivi.
 */
fun lensSummaryWithPoints(
    pointsByLens: Map<String, Int>,
    activeLens: String?,
    lensIdsInOrder: List<String>
): List<LensSummary> =
    pointsByLens
        .filterValues { it > 0 }
        .map { (lens, points) ->
            val index = lensIdsInOrder.indexOf(lens)
            LensSummary(
                lens = lens,
                position = if (index >= 0) index + 1 else null,
                lensCount = lensIdsInOrder.size,
                points = points,
                isActive = lens == activeLens
            )
        }
        .sortedWith(
            compareByDescending<LensSummary> { it.isActive }
                .thenByDescending { it.points }
                .thenBy { it.position ?: Int.MAX_VALUE }
                .thenBy { it.lens }
        )
