package com.miraglio.data.places

import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.location.combinedFixError
import java.util.Locale

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
    /**
     * Nota libera: quello che dopo una settimana non si ricorda piu'
     * ("facciata nord", "misurato con zoom 2x", "dubbio sulla distanza").
     *
     * A differenza di [label] non entra in nessun titolo ne' nella
     * condivisione: e' un appunto per chi ha salvato il luogo, e il campo
     * vuoto e' un valore come gli altri (cancellarla e' una modifica vera,
     * non "nessuna modifica").
     */
    val notes: String = "",
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
     * Il punto che rappresenta il luogo nel riepilogo: per uno scatto e' il
     * bersaglio (dove si e' mirato), per una misura a piedi l'arrivo. Se manca
     * si ripiega sugli altri fix, cosi' il tap lungo non resta senza niente da
     * copiare proprio sui record vecchi.
     */
    val summaryFix: GpsFix?
        get() = targetFix ?: endFix ?: shotFix ?: startFix

    /**
     * Coordinate pronte da incollare in un'altra app: `lat, lon` con sei cifre e
     * punto decimale, senza l'accuratezza (e' un numero, non un pin).
     */
    fun coordinatesText(): String? = summaryFix?.let {
        String.format(Locale.US, "%.6f, %.6f", it.latitudeDeg, it.longitudeDeg)
    }

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
            return combinedFixError(s.accuracyM, e.accuracyM)
        }
}
