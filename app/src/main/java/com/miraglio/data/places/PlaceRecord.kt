package com.miraglio.data.places

import com.miraglio.domain.location.GpsFix
import kotlin.math.sqrt

/** Tipo di luogo salvato. */
enum class PlaceKind {
    /** Foto di un bersaglio con distanza stimata e bussola. */
    CAMERA,

    /** Misura GPS percorsa a piedi (partenza -> arrivo). */
    GPS
}

/** Esito dell'azione di salvataggio di un luogo. */
enum class PlaceSaveState { IDLE, SAVING, SAVED, FAILED }

/**
 * Un "luogo" salvato dall'utente: foto + misura + coordinate, condivisibile
 * come messaggio con pin Google Maps.
 *
 * [photoFile] e' solo il NOME del file dentro `filesDir/places/` (il percorso
 * cambia con l'ID); i fix sono separati perche' la condivisione combina
 * partenza/bersaglio in modi diversi per CAMERA e GPS.
 */
data class PlaceRecord(
    val id: String,
    val createdAtMs: Long,
    val kind: PlaceKind,
    /** Etichetta modificabile (default: data/ora dello scatto). */
    val label: String,
    val photoFile: String?,

    // --- Dati comuni dello scatto ---
    val shotFix: GpsFix?,
    /** Direzione di mira, gradi dal nord vero (CAMERA). */
    val bearingDeg: Float? = null,
    val pitchDeg: Float? = null,
    val distanceM: Float? = null,
    val horizontalDistanceM: Float? = null,
    val zoomRatio: Float = 1f,
    val lensLabel: String? = null,

    // --- Bersaglio stimato (solo CAMERA con bussola + distanza) ---
    val targetFix: GpsFix? = null,
    /** Dimensione reale del bersaglio tra le linee (modalita' ALTEZZA). */
    val measuredSizeM: Float? = null,

    // --- Misura GPS (solo GPS) ---
    val startFix: GpsFix? = null,
    val endFix: GpsFix? = null,
    val walkedM: Float? = null
) {
    val hasAnyPin: Boolean
        get() = shotFix != null || targetFix != null || startFix != null || endFix != null

    /**
     * Errore stimato della distanza GPS partenza -> arrivo: le accuratezze
     * dei due fix sono raggi di confidenza, quindi si combinano in
     * quadratura (due fix da ±4 m danno ~±5,7 m sulla distanza).
     * Null per gli scatti CAMERA (la distanza viene dalla visione).
     */
    val gpsErrorM: Float?
        get() {
            val s = startFix ?: return null
            val e = endFix ?: return null
            return sqrt(s.accuracyM * s.accuracyM + e.accuracyM * e.accuracyM)
        }
}
