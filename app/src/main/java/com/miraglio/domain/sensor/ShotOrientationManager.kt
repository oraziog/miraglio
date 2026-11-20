package com.miraglio.domain.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * L'angolo di sito del tiro, bloccato al momento del puntamento.
 *
 * Il telefono si appoggia, si mira, si preme "Distanza via Audio": da quel
 * momento l'angolo **non cambia più**, anche se il telefono viene appoggiato a
 * terra o ruotato per tirare. È il motivo per cui questo oggetto esiste: se
 * l'inclinometro continuasse ad aggiornare, un telefono appoggiato a 0° dopo
 * aver mirato a 15° restituirebbe una distanza orizzontale sbagliata del 3% —
 * due metri a 70 m.
 *
 * La fonte resta osservabile: finché non c'è un blocco, l'angolo mostrato è
 * quello vero, così il mirino può dire "stai mirando troppo in alto" prima
 * della misura.
 */
class ShotOrientationManager(
    private val pitchSource: StateFlow<Float>
) {

    private val _lockedPitchDeg = MutableStateFlow<Float?>(null)

    /** L'angolo bloccato, o null se la misura non è ancora stata avviata. */
    val lockedPitchDeg: StateFlow<Float?> = _lockedPitchDeg.asStateFlow()

    /** True quando l'angolo è congelato. */
    val isLocked: Boolean
        get() = _lockedPitchDeg.value != null

    /**
     * Blocca l'angolo corrente. Ritorna il valore bloccato, che è anche quello
     * da salvare col tiro: due letture diverse dello stesso istante
     * (l'interfaccia e la macchina) non devono dare numeri diversi.
     */
    fun lock(): Float {
        val current = pitchSource.value
        _lockedPitchDeg.value = current
        return current
    }

    /** Sblocca: si ricomincia a seguire il sensore. */
    fun unlock() {
        _lockedPitchDeg.value = null
    }

    /**
     * L'angolo da usare per la compensazione: quello bloccato se c'è, altrimenti
     * quello vivo del sensore.
     */
    val aimPitchDeg: Float
        get() = _lockedPitchDeg.value ?: pitchSource.value
}
