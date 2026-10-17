package com.miraglio.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.miraglio.domain.format.UnitSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferenze dell'app, salvate in SharedPreferences come la calibrazione
 * focale ([com.miraglio.data.focal.FocalCalibrationStore]).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val PREFS = "miraglio_settings"
        private const val KEY_RECOGNITION = "recognition_enabled"
        private const val KEY_OUTDOOR = "outdoor_mode"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_PORTRAIT_LOCK = "portrait_lock"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Spento di default: finche' l'utente non lo accende l'app si comporta
    // esattamente come sempre e il modello ONNX non viene nemmeno caricato,
    // perche' la sessione di inferenza e' lazy e si crea al primo detect().
    private val _recognitionEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_RECOGNITION, false))

    /** "Riconoscimento paglione": bersagli riquadrati in verde e linee agganciate allo scatto. */
    val recognitionEnabled: StateFlow<Boolean> = _recognitionEnabled.asStateFlow()

    fun setRecognitionEnabled(enabled: Boolean) {
        if (_recognitionEnabled.value == enabled) return
        prefs.edit().putBoolean(KEY_RECOGNITION, enabled).apply()
        _recognitionEnabled.value = enabled
    }

    // Spento di default: l'app resta quella di sempre finche' l'utente non
    // attiva la modalita' outdoor, che cambia tema, colori dei pannelli sopra
    // l'immagine e dimensione dei testi.
    private val _outdoorModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_OUTDOOR, false))

    /** Modalita' outdoor: nero pieno, giallo fluorescente e testi ingranditi. */
    val outdoorModeEnabled: StateFlow<Boolean> = _outdoorModeEnabled.asStateFlow()

    fun setOutdoorModeEnabled(enabled: Boolean) {
        if (_outdoorModeEnabled.value == enabled) return
        prefs.edit().putBoolean(KEY_OUTDOOR, enabled).apply()
        _outdoorModeEnabled.value = enabled
    }

    // Metrico di default: e' il sistema con cui l'app e' nata, quindi chi
    // aggiorna non trova le distanze riscritte in iarde senza averlo chiesto.
    private val _unitSystem = MutableStateFlow(
        UnitSystem.of(prefs.getString(KEY_UNIT_SYSTEM, null))
    )

    /**
     * Sistema di unita' della schermata: metri o iarde. Non cambia niente di
     * quello che c'e' gia' salvato (luoghi, calibrazioni), che resta in metri.
     */
    val unitSystem: StateFlow<UnitSystem> = _unitSystem.asStateFlow()

    fun setUnitSystem(unit: UnitSystem) {
        if (_unitSystem.value == unit) return
        prefs.edit().putString(KEY_UNIT_SYSTEM, unit.stored).apply()
        _unitSystem.value = unit
    }

    // Bloccata di default: e' il comportamento che l'app ha sempre avuto (il
    // manifest la dichiara in portrait), quindi chi aggiorna trova la stessa
    // app di prima e non si ritrova l'interfaccia che gira. Chi la vuole
    // libera la sblocca, e la scelta resta.
    private val _portraitLocked = MutableStateFlow(prefs.getBoolean(KEY_PORTRAIT_LOCK, true))

    /**
     * Blocco della rotazione: con l'app in verticale il telefono non gira
     * mentre si mira. Non e' un'impostazione di sistema — cambia solo la
     * rotazione delle activity di Miraglio — quindi vale finche' l'app e'
     * aperta e non tocca gli altri programmi.
     */
    val portraitLocked: StateFlow<Boolean> = _portraitLocked.asStateFlow()

    fun setPortraitLocked(locked: Boolean) {
        if (_portraitLocked.value == locked) return
        prefs.edit().putBoolean(KEY_PORTRAIT_LOCK, locked).apply()
        _portraitLocked.value = locked
    }
}
