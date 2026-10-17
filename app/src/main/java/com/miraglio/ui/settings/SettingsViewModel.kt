package com.miraglio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.data.update.UpdateManager
import com.miraglio.domain.format.UnitSystem
import com.miraglio.i18n.AppLanguage
import com.miraglio.i18n.AppLanguageChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettingsStore,
    private val updates: UpdateManager,
    private val language: AppLanguage
) : ViewModel() {

    /** Lingua dell'app: la scelta in corso, per il selettore qui sotto. */
    val appLanguage: StateFlow<AppLanguageChoice> = language.selected

    /** Cambia lingua: i testi a schermo si aggiornano subito. */
    fun setAppLanguage(choice: AppLanguageChoice) = language.select(choice)

    /**
     * Sistema di unita' delle lunghezze. E' indipendente dalla lingua: un
     * inglese puo' misurare in metri e un italiano in iarde.
     */
    val unitSystem: StateFlow<UnitSystem> = settings.unitSystem
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settings.unitSystem.value
        )

    fun setUnitSystem(unit: UnitSystem) {
        settings.setUnitSystem(unit)
    }

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
     * Blocco della rotazione: la stessa preferenza del pulsante nella barra
     * della schermata di mira (una sola, due posti da cui cambiarla).
     */
    val portraitLocked: StateFlow<Boolean> = settings.portraitLocked
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settings.portraitLocked.value
        )

    fun setPortraitLock(locked: Boolean) {
        settings.setPortraitLocked(locked)
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

    /** Testo dell'invito, composto ora: segue la lingua scelta nelle Impostazioni. */
    fun installInviteMessage(): String = updates.installInviteMessage()

    fun checkForUpdates() = updates.checkNow()

    fun downloadUpdate() = updates.download()

    fun cancelDownload() = updates.cancelDownload()

    fun installUpdate() = updates.install()
}
