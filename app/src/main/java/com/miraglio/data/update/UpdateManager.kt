package com.miraglio.data.update

import com.miraglio.BuildConfig
import com.miraglio.domain.update.AppVersion
import com.miraglio.domain.update.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unico punto di verita' dell'aggiornamento: dialogo e schermata Impostazioni
 * leggono lo stesso stato, cosi' un download avviato dal dialog si vede anche
 * nelle Impostazioni e viceversa.
 *
 * Vive quanto il processo dell'app (come [com.miraglio.data.places.PlacesRepository]),
 * quindi il download non si perde se la schermata viene ricreata.
 * Niente e' automatico: l'APK si scarica solo su richiesta dell'utente e
 * l'installazione la conferma sempre il sistema.
 */
@Singleton
class UpdateManager @Inject constructor(
    private val releases: GitHubReleasesClient,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller
) {

    sealed interface State {

        /** Mai controllato (o controllo appena avviato). */
        data object Idle : State

        data object Checking : State

        /** Nessuna release piu' recente: niente da fare. */
        data object UpToDate : State

        /** C'e' una versione piu' recente, non ancora scaricata. */
        data class Available(val release: ReleaseInfo) : State

        /** Download in corso; [progress] e' null se la dimensione e' ignota. */
        data class Downloading(val release: ReleaseInfo, val progress: Float?) : State

        /** APK pronto: manca solo la conferma dell'utente all'installatore. */
        data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : State

        /** Serve prima l'autorizzazione di sistema "installa app sconosciute". */
        data class PermissionRequired(val release: ReleaseInfo, val apk: File) : State

        /** Download o installazione non riusciti: si puo' riprovare. */
        data class Failed(val release: ReleaseInfo, val message: String) : State

        /** Il controllo non e' riuscito (di solito manca la rete): niente allarmi. */
        data class CheckFailed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * Versione piu' recente che l'utente ha rimandato: serve a non riaprire il
     * dialog a ogni avvio per la stessa versione, che sarebbe solo un fastidio.
     */
    private var postponedTag: String? = null

    val installedVersionName: String = BuildConfig.VERSION_NAME

    /**
     * Controllo silenzioso all'avvio: se c'e' una versione nuova apre il
     * dialog, se non c'e' rete non dice niente.
     */
    fun checkInBackground() = startCheck(announce = false)

    /** Controllo chiesto dall'utente (Impostazioni): mostra sempre l'esito. */
    fun checkNow() = startCheck(announce = true)

    private fun startCheck(announce: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = scope.launch {
            _state.value = State.Checking
            val result = releases.fetchLatest()
            val release = result.getOrNull()
            if (result.isFailure) {
                setState(State.CheckFailed(messageOf(result.exceptionOrNull())), showDialog = false)
                return@launch
            }
            // release == null: il repository non ha ancora nessuna release.
            val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
            if (release == null || !release.isNewerThan(installed)) {
                setState(State.UpToDate, showDialog = false)
                return@launch
            }
            setState(
                State.Available(release),
                showDialog = announce || release.tagName != postponedTag
            )
        }
    }

    /** "Piu' tardi" sul dialog: lo chiude e non lo riapre per questa versione. */
    fun postpone() {
        val release = currentRelease()
        if (release != null) postponedTag = release.tagName
        _showDialog.value = false
    }

    /** Scarica l'APK della release trovata. */
    fun download() {
        val release = currentRelease() ?: return
        if (downloadJob?.isActive == true) return
        setState(State.Downloading(release, null), showDialog = true)
        downloadJob = scope.launch {
            try {
                val apk = downloader.download(release.apkUrl) { progress ->
                    _state.value = State.Downloading(release, progress)
                }
                setState(State.ReadyToInstall(release, apk), showDialog = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState(State.Failed(release, messageOf(e)), showDialog = true)
            }
        }
    }

    /**
     * Annulla il download in corso: la release torna disponibile, senza
     * lasciare un APK incompleto in cache (il file parziale viene cancellato).
     */
    fun cancelDownload() {
        val release = (_state.value as? State.Downloading)?.release ?: return
        downloadJob?.cancel()
        setState(State.Available(release), showDialog = false)
    }

    /**
     * Passa all'installatore di sistema. Se manca l'autorizzazione, apre la
     * schermata dove concederla e resta in attesa: al ritorno basta ripremere.
     */
    fun install() {
        val ready = _state.value
        val release: ReleaseInfo
        val apk: File
        when (ready) {
            is State.ReadyToInstall -> {
                release = ready.release
                apk = ready.apk
            }
            is State.PermissionRequired -> {
                release = ready.release
                apk = ready.apk
            }
            else -> return
        }
        if (!apk.exists()) {
            setState(State.Failed(release, "Il file scaricato non c'e' piu'"), showDialog = true)
            return
        }
        if (!installer.canInstall()) {
            installer.openInstallPermissionSettings()
            setState(State.PermissionRequired(release, apk), showDialog = true)
            return
        }
        if (!installer.install(apk)) {
            setState(State.Failed(release, "Nessuna app di sistema ha aperto il file"), showDialog = true)
        }
    }

    private fun currentRelease(): ReleaseInfo? = when (val s = _state.value) {
        is State.Available -> s.release
        is State.Failed -> s.release
        is State.ReadyToInstall -> s.release
        else -> null
    }

    private fun setState(state: State, showDialog: Boolean) {
        _state.value = state
        _showDialog.value = showDialog
    }

    private fun messageOf(e: Throwable?): String =
        e?.message?.takeIf { it.isNotBlank() } ?: e?.javaClass?.simpleName ?: "Errore sconosciuto"
}
