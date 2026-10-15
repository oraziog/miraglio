package com.miraglio.data.settings

import android.content.Context
import android.content.SharedPreferences
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
}
