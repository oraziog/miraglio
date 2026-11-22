package com.miraglio.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.audio.AcousticRangefinder
import com.miraglio.data.audio.AcousticSession
import com.miraglio.data.audio.AcousticShot
import com.miraglio.data.audio.ShotAudioClip
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.profile.BowProfile
import com.miraglio.data.profile.BowProfileStore
import com.miraglio.data.sensor.MotionMonitor
import com.miraglio.data.weather.TemperatureReading
import com.miraglio.data.weather.TemperatureSource
import com.miraglio.data.weather.WeatherCachePolicy
import com.miraglio.data.weather.WeatherRepository
import com.miraglio.domain.audio.ImpactPlausibility
import com.miraglio.domain.audio.ShotReport
import com.miraglio.domain.audio.ShotReportBuilder
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingState
import com.miraglio.domain.audio.WaveformBuffer
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La schermata di misura: unisce i pezzi degli Step 1–4 e non fa conti suoi.
 *
 * Il risultato passa da [ShotReportBuilder]: la ViewModel decide *quando*
 * calcolare, il dominio decide *come*.
 */
@HiltViewModel
class AudioRangefinderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: BowProfileStore,
    private val weather: WeatherRepository,
    private val rangefinder: AcousticRangefinder,
    private val motion: MotionMonitor,
    private val location: LocationTracker
) : ViewModel() {

    private val waveform = WaveformBuffer()

    data class UiState(
        val profiles: List<BowProfile> = emptyList(),
        val profile: BowProfile? = null,
        val stage: ShotStage = ShotStage.IDLE,
        val listening: Boolean = false,
        val report: ShotReport? = null,
        val temperature: TemperatureReading = TemperatureReading(
            WeatherCachePolicy.FALLBACK_CELSIUS,
            TemperatureSource.FALLBACK
        ),
        val model: ArrowSpeedModel? = null,
        val canMeasure: Boolean = false,
        /** Il microfono non si è aperto: non è un tiro mancato, è un guasto. */
        val captureFailed: Boolean = false,
        /** L'ascolto si è fermato da solo per inattività: non è un guasto. */
        val timedOut: Boolean = false,
        /**
         * Contatore dei tiri misurati in questa sessione.
         *
         * Serve al lampeggio: due tiri con la stessa distanza devono
         * illuminare la scheda due volte, e un valore uguale non cambierebbe
         * niente. Cambia solo quando arriva un risultato nuovo.
         */
        val resultPulse: Int = 0,
        /** L'ultimo tiro ha l'audio in memoria, non ancora salvato. */
        val pendingAudio: Boolean = false,
        /** Nome del file appena scritto nella cartella scelta, se è andata. */
        val audioSavedName: String? = null,
        /** Il salvataggio nella cartella scelta non è riuscito. */
        val audioSaveFailed: Boolean = false,
        /**
         * WAV scritto in cache per la condivisione, in attesa che la schermata
         * apra il chooser. La schermata lo azzera con [onAudioShared].
         */
        val shareAudioPath: String? = null,
        val zoom: Float = WaveformBuffer.MIN_ZOOM,
        val waveform: List<Float> = emptyList(),
        /**
         * Dove cadono scocco e impatto nel grafico dei livelli, in indici della
         * lista [waveform]. `null` quando quel punto è fuori dalla finestra
         * visibile (o prima che il tiro sia stato misurato).
         */
        val releaseMarker: Float? = null,
        val impactMarker: Float? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: Job? = null

    /** L'audio dell'ultimo tiro: in memoria, scritto solo se l'utente lo chiede. */
    private var pendingClip: ShotAudioClip? = null

    /**
     * L'utente ha fermato l'ascolto: entrando o tornando nella schermata non si
     * riparte da soli. Premere il pulsante lo rimette a falso.
     */
    private var stoppedByUser = false

    /** Blocchi da 20 ms letti in questa sessione: indice di chi sta disegnando. */
    private var blocksRead = 0

    /** Blocco del rilascio e dell'impatto, in indici di blocco dall'avvio. */
    private var releaseBlock: Int? = null
    private var impactBlock: Int? = null

    init {
        viewModelScope.launch {
            combine(profileStore.profiles, profileStore.activeProfile) { list, active ->
                list to active
            }.collect { (list, active) ->
                val model = active?.let { BallisticCalculator.fit(it.fitPoints) }
                _state.value = _state.value.copy(
                    profiles = list,
                    profile = active,
                    model = model,
                    canMeasure = model != null,
                    // Cancellate le misure di calibrazione, il risultato a
                    // schermo non è più una misura: era calcolato con un
                    // modello che non esiste più.
                    report = _state.value.report.takeIf { model != null }
                )
            }
        }
        viewModelScope.launch {
            // Il GPS serve solo alla temperatura: appena arriva un fix, se non
            // c'è un valore manuale si chiede la previsione di quel posto.
            location.currentLocation.collect { fix ->
                if (fix != null && weather.manualCelsius() == null) refreshTemperature()
            }
        }
        refreshTemperature()
    }

    // ------------------------------------------------------------ sessione

    /**
     * Avvia l'ascolto continuo: blocco dell'angolo, sensori accesi, microfono
     * aperto una volta sola.
     *
     * La sessione non finisce al primo tiro: ogni misura aggiorna il risultato
     * e il motore si riarma da solo (vedi `AcousticRangefinder.listen`). Si
     * chiude quando l'utente preme Ferma, quando l'app va in background o dopo
     * tre minuti senza tiri.
     */
    fun start() {
        if (session?.isActive == true) return
        val profile = _state.value.profile ?: return
        // Il pulsante è già spento in questo caso, ma la regola vale anche per
        // chi chiama `start()`: senza almeno due distanze di calibrazione il
        // modello non esiste e l'ascolto produrrebbe solo un tempo senza
        // distanza.
        if (_state.value.model == null) return
        stoppedByUser = false
        pendingClip = null

        session = viewModelScope.launch {
            // Il permesso si controlla qui e non solo nella schermata: chi
            // chiama questa funzione non deve poter aprire il microfono senza
            // averlo chiesto, e la schermata potrebbe essere ricreata mentre
            // l'utente revoca il permesso dalle impostazioni.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = _state.value.copy(
                    stage = ShotStage.IDLE,
                    listening = false,
                    captureFailed = true
                )
                return@launch
            }

            waveform.clear()
            blocksRead = 0
            releaseBlock = null
            impactBlock = null
            runCatching { motion.start() }
            runCatching { location.startLocationUpdates() }

            _state.value = _state.value.copy(
                listening = true,
                stage = ShotStage.SETTLING,
                report = null,
                captureFailed = false,
                timedOut = false,
                waveform = emptyList(),
                releaseMarker = null,
                impactMarker = null,
                resultPulse = 0,
                pendingAudio = false,
                audioSavedName = null,
                audioSaveFailed = false,
                shareAudioPath = null
            )

            // La finestra in cui un impatto è fisicamente possibile esce dal
            // profilo (modello calibrato + distanze di calibrazione), non da
            // una costante: a 18 m la costante 1,0–2,0 s non conteneva il tiro.
            // `null` (profilo non calibrato) lascia il motore senza veto, come
            // prima: meglio nessun veto che una finestra inventata.
            val impactWindow = ImpactPlausibility.band(
                model = _state.value.model,
                distancesM = profile.fitPoints.map { it.distanceM },
                airTempC = _state.value.temperature.celsius
            )

            var cancelled = false
            var ended: AcousticSession? = null
            try {
                ended = rangefinder.listen(
                    // Se l'arciere non ha dichiarato il clicker, il motore lo
                    // riconosce da solo ascoltando la coppia click→corda. Se
                    // lo ha dichiarato si usa il percorso già validato, con il
                    // suo offset: non c'è motivo di rimetterlo in discussione.
                    useClicker = profile.useClicker,
                    releaseOffsetMs = profile.releaseOffsetMs,
                    autoClicker = !profile.useClicker,
                    plausibleTotalTimeS = impactWindow,
                    stability = motion.stable,
                    onState = { publishStage(it) },
                    onLevel = ::onLevel,
                    onShot = { shot -> publishShot(shot) }
                )
            } catch (stop: CancellationException) {
                cancelled = true
                _state.value = _state.value.copy(stage = ShotStage.IDLE, listening = false)
                throw stop
            } finally {
                runCatching { motion.stop() }
                runCatching { location.stopLocationUpdates() }
                if (!cancelled) {
                    _state.value = _state.value.copy(
                        listening = false,
                        captureFailed = ended?.microphoneOpened == false,
                        timedOut = ended?.timedOut == true
                    )
                }
                session = null
            }
        }
    }

    /** Ferma l'ascolto e non lo fa ripartire da solo al prossimo ingresso. */
    fun stop() {
        stoppedByUser = true
        session?.cancel()
    }

    /**
     * Ferma l'ascolto perché l'app va in background, senza cambiare idea per
     * quando si torna: al rientro [autoStart] riapre il microfono.
     */
    fun stopForBackground() {
        session?.cancel()
    }

    /**
     * Ascolto automatico all'ingresso nel telemetro.
     *
     * Parte solo se c'è davvero qualcosa da misurare (modello calibrato) e il
     * permesso del microfono è già stato concesso: chiedere il permesso da soli
     * all'apertura della schermata sarebbe una richiesta a sorpresa. Se
     * l'utente ha premuto Ferma, non riparte.
     */
    fun autoStart() {
        if (stoppedByUser) return
        if (!_state.value.canMeasure) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Tornando dal background la sessione precedente può essere ancora in
        // chiusura: aspettarla invece di rinunciare, altrimenti un passaggio
        // rapido (il selettore di cartelle, il blocco schermo) lascerebbe il
        // telemetro fermo senza che l'utente abbia premuto Ferma.
        val closing = session
        if (closing != null && closing.isActive) {
            viewModelScope.launch {
                closing.join()
                if (!stoppedByUser) autoStart()
            }
            return
        }
        start()
    }

    /**
     * Scrive l'audio dell'ultimo tiro nella cartella scelta dall'utente.
     *
     * La cartella arriva dal selettore di sistema (SAF), quindi lo Storage
     * Access Framework dà il permesso di scrittura solo su quella: nessun
     * permesso generico sui file, e il WAV esiste solo da qui in poi.
     */
    fun savePendingAudio(treeUri: Uri) {
        val clip = pendingClip ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val document = DocumentsContract.createDocument(
                        resolver,
                        parent,
                        "audio/wav",
                        clip.fileName
                    ) ?: return@runCatching null
                    val stream = resolver.openOutputStream(document)
                        ?: return@runCatching null
                    stream.use { clip.writeTo(it) }
                    clip.fileName
                }.getOrNull()
            }
            _state.value = _state.value.copy(
                audioSavedName = saved,
                audioSaveFailed = saved == null
            )
        }
    }

    /**
     * Scrive l'audio dell'ultimo tiro in cache e lascia alla schermata il
     * compito di aprire il chooser.
     *
     * La cartella della cache è già dichiarata nel [androidx.core.content.FileProvider],
     * quindi chi riceve il file legge solo quell'URI: nessun permesso sui file e
     * nessun salvataggio definitivo. Il percorso passa dallo stato perché
     * l'intent va aperto con il contesto della schermata, non con quello
     * dell'applicazione.
     */
    fun sharePendingAudio() {
        val clip = pendingClip ?: return
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    val file = File(dir, clip.fileName)
                    file.outputStream().use { clip.writeTo(it) }
                    file.absolutePath
                }.getOrNull()
            }
            _state.value = _state.value.copy(shareAudioPath = written)
        }
    }

    /** La schermata ha aperto il chooser: la cache non serve più come tramite. */
    fun onAudioShared() {
        _state.value = _state.value.copy(shareAudioPath = null)
    }

    private fun publishStage(state: ShotTimingState) {
        _state.value = _state.value.copy(stage = state.stage)
    }

    /**
     * Un tiro concluso: aggiorna il risultato, tiene l'audio da parte e fa
     * lampeggiare la scheda.
     *
     * Il report si costruisce qui e non nel motore perché serve il modello e
     * l'angolo bloccato all'avvio, che il motore non conosce. `resultPulse`
     * avanza a ogni tiro misurato: è quello che fa ripartire l'animazione anche
     * quando il numero a schermo è identico al precedente.
     */
    private fun publishShot(shot: AcousticShot) {
        val measurement = shot.state.measurement
        pendingClip = shot.audio
        releaseBlock = shot.releaseBlock
        impactBlock = shot.impactBlock
        val report = measurement?.let {
            ShotReportBuilder.build(
                totalTimeS = it.totalTimeS,
                model = _state.value.model,
                airTempC = _state.value.temperature.celsius,
                evidence = it.evidence,
                clickerDetected = it.clickerDetected
            )
        }
        _state.value = _state.value.copy(
            stage = shot.state.stage,
            report = report,
            resultPulse = _state.value.resultPulse + 1,
            pendingAudio = shot.audio != null,
            audioSavedName = null,
            audioSaveFailed = false,
            shareAudioPath = null
        )
        publishWaveform(_state.value.zoom)
    }

    private fun onLevel(level: Float) {
        waveform.push(level)
        blocksRead++
        // Il grafico si ridisegna cinque volte al secondo: a ogni blocco (50
        // volte) sarebbe lavoro inutile per l'occhio e per la CPU.
        if (blocksRead % 5 != 0) return
        publishWaveform(_state.value.zoom)
    }

    fun setZoom(zoom: Float) {
        val clamped = zoom.coerceIn(WaveformBuffer.MIN_ZOOM, WaveformBuffer.MAX_ZOOM)
        publishWaveform(clamped)
    }

    /**
     * Pubblica la finestra visibile del grafico e le due linee del tiro.
     *
     * Le posizioni dei segni sono indici di blocco dall'avvio; la finestra
     * mostrata parte da `blocksRead - size` blocchi fa, quindi lo spostamento
     * è una sottrazione. Un segno uscito dalla finestra (zoom stretto, tiro
     * vecchio) non si disegna: meglio nessuna linea che una linea nel punto
     * sbagliato.
     */
    private fun publishWaveform(zoom: Float) {
        val clamped = zoom.coerceIn(WaveformBuffer.MIN_ZOOM, WaveformBuffer.MAX_ZOOM)
        val levels = waveform.window(WaveformBuffer.visibleCount(clamped)).toList()
        val shift = blocksRead - levels.size
        fun position(block: Int?): Float? {
            val index = (block ?: return null) - shift
            return index.toFloat().takeIf { it >= 0f && it <= levels.lastIndex.toFloat() }
        }
        _state.value = _state.value.copy(
            zoom = clamped,
            waveform = levels,
            releaseMarker = position(releaseBlock),
            impactMarker = position(impactBlock)
        )
    }

    // ------------------------------------------------------------- profilo

    fun createProfile(nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        val created = profileStore.addProfile(trimmed)
        profileStore.setActive(created.id)
    }

    fun selectProfile(id: String) {
        if (_state.value.listening) return
        profileStore.setActive(id)
    }

    fun setClicker(enabled: Boolean) {
        val profile = _state.value.profile ?: return
        if (_state.value.listening) return
        profileStore.setUseClicker(profile.id, enabled)
    }

    fun setReleaseOffset(text: String) {
        val profile = _state.value.profile ?: return
        val value = parseDecimal(text) ?: return
        if (_state.value.listening) return
        profileStore.setReleaseOffsetMs(profile.id, value)
    }

    // --------------------------------------------------------- temperatura

    fun applyManualTemperature(text: String) {
        val value = parseDecimal(text) ?: return
        weather.setManualCelsius(value)
        refreshTemperature()
    }

    fun clearManualTemperature() {
        weather.setManualCelsius(null)
        refreshTemperature()
    }

    /** Rilegge la temperatura: manuale, meteo del luogo, oppure ripiego. */
    fun refreshTemperature() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                temperature = resolveTemperature(weather, location)
            )
        }
    }
}
