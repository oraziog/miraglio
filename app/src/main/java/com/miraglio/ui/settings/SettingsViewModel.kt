package com.miraglio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.data.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsStore,
    private val updates: UpdateManager
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

    /**
     * Stato dell'aggiornamento: e' lo stesso oggetto che alimenta il dialog
     * della radice, quindi qui si vede anche un download avviato da li'.
     */
    val updateState: StateFlow<UpdateManager.State> = updates.state

    /** Versione attualmente installata, per il confronto a colpo d'occhio. */
    val installedVersionName: String = updates.installedVersionName

    /**
     * Link e messaggio per far installare Miraglio su un altro telefono: la
     * card con QR e condivisione usa questi, senza sapere niente del repository.
     */
    val installPageUrl: String = updates.installPageUrl
    val installInviteMessage: String = updates.installInviteMessage

    fun checkForUpdates() = updates.checkNow()

    fun downloadUpdate() = updates.download()

    fun cancelDownload() = updates.cancelDownload()

    fun installUpdate() = updates.install()
}
