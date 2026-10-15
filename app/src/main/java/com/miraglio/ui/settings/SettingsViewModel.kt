package com.miraglio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.settings.AppSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsStore
) : ViewModel() {

    val recognitionEnabled: StateFlow<Boolean> = settings.recognitionEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settings.recognitionEnabled.value
        )

    fun setRecognitionEnabled(enabled: Boolean) {
        settings.setRecognitionEnabled(enabled)
    }

    /** Modalita' outdoor: tema ad alto contrasto e testi ingranditi. */
    val outdoorMode: StateFlow<Boolean> = settings.outdoorModeEnabled
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settings.outdoorModeEnabled.value
        )

    fun setOutdoorMode(enabled: Boolean) {
        settings.setOutdoorModeEnabled(enabled)
    }
}
