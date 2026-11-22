package com.miraglio.data.audio

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.miraglio.domain.audio.AcousticShotAnalyzer
import com.miraglio.domain.audio.ShotMeasurement
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingConfig
import com.miraglio.domain.audio.ShotTimingMachine
import com.miraglio.domain.audio.ShotTimingState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Come è finita una sessione, oltre allo stato del motore.
 *
 * Serve a non confondere due cose che all'utente sembrano la stessa e non lo
 * sono: il microfono che non si apre (permesso, driver, altra app che lo tiene)
 * e la sessione che scade perché il tiro non è arrivato in tempo. Prima
 * ambedue finivano in [ShotStage.IDLE], quindi un timeout mostrava il messaggio
 * "chiudi le altre applicazioni" e mandava a cercare un colpevole che non
 * c'era.
 */
data class AcousticSession(
    val state: ShotTimingState,
    /** Falso solo se [AudioCaptureSource.open] non è riuscita ad aprire l'ADC. */
    val microphoneOpened: Boolean,
    /** Vero se la sessione si è chiusa per [AcousticRangefinder.DEFAULT_SESSION_TIMEOUT_S]. */
    val timedOut: Boolean = false,
    /** WAV di debug salvato per questa sessione, se ce n'è uno. */
    val debugAudioPath: String? = null,
    /**
     * Blocco da 20 ms in cui cadono lo scocco e l'impatto, contati dall'avvio
     * del flusso: servono al grafico per segnare i due istanti.
     * `releaseBlock` c'è anche senza impatto, `impactBlock` solo con la misura.
     */
    val releaseBlock: Int? = null,
    val impactBlock: Int? = null
)

/**
 * Un tiro concluso durante l'ascolto continuo, con il suo audio in memoria.
 *
 * [audio] c'è solo per le misure riuscite ([ShotStage.DONE]) ed è il pezzo
 * intorno al tiro, non ancora scritto da nessuna parte: diventa un file solo se
 * l'utente preme Salva e sceglie la cartella. Se non lo fa, il buffer circolare
 * lo sovrascrive col tiro successivo e la memoria resta quella di sempre.
 */
data class AcousticShot(
    val state: ShotTimingState,
    val releaseBlock: Int? = null,
    val impactBlock: Int? = null,
    val audio: ShotAudioClip? = null
)

/**
 * Il telemetro acustico completo: microfono, filtri, macchina a stati.
 *
 * Ha due modi di funzionare, e la differenza è tutta nel ciclo di vita della
 * sessione. [measure] è il colpo singolo: aspetta che il telefono sia fermo,
 * arma il motore, legge fino alla conclusione del tiro e restituisce lo stato
 * finale — è quello che serve alla calibrazione. [listen] è l'ascolto continuo:
 * il microfono resta aperto e ogni tiro concluso arriva a chi ascolta, finché
 * non si ferma o non passano troppi minuti senza tiri — è quello che serve a
 * una serie di frecce.
 *
 * **Perché l'armamento non è immediato.** Diventare "fermo" e premere il
 * pulsante sono due cose diverse: chi appoggia il telefono lo lascia andare
 * con un colpo sul tavolo, e quel colpo è un transiente perfetto per essere
 * scambiato per uno scocco. Il motore si arma solo dopo [STABLE_HOLD_MS] di
 * quiete continua, cioè quando il colpo è passato e la mano è via.
 *
 * **Perché c'è un tetto di tempo.** Se il tiro non parte (l'arciere cambia
 * idea, la freccia si incastra, il telefono si è spento) il ciclo non deve
 * restare aperto sul microfono all'infinito: dopo
 * [DEFAULT_SESSION_TIMEOUT_S] si chiude e restituisce lo stato raggiunto. Il
 * tetto è largo apposta — quarantacinque secondi — perché in calibrazione si
 * deve avere il tempo di mettersi in posizione e tirare senza l'ansia
 * dell'allarme: con dodici secondi la sessione scadeva prima del tiro e
 * sembrava un guasto del microfono. Quel tetto è anche l'unico posto in cui
 * `System.nanoTime()` è lecito: misura la durata della sessione, non
 * l'intervallo fra due eventi.
 *
 * **L'audio resta in memoria.** Il buffer è circolare e tiene gli ultimi
 * dodici secondi (vedi [ShotAudioRecorder]): se una misura non torna, l'unico
 * modo per capire se il microfono sente troppo piano — o se ha agganciato
 * l'eco del rilascio invece dell'impatto — è riascoltare il pezzo fra rilascio
 * e impatto. Con [listen] quel pezzo viaggia dentro [AcousticShot.audio] e
 * nessuno lo scrive su disco: lo salva solo l'utente, nella cartella che
 * sceglie. La calibrazione continua invece a scrivere subito il suo WAV, che
 * serve alla condivisione.
 */
@Singleton
class AcousticRangefinder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Esegue una misura completa.
     *
     * [stability] è il flusso del monitor di movimento
     * ([com.miraglio.data.sensor.MotionMonitor]); [useClicker] e
     * [releaseOffsetMs] arrivano dal profilo arco.
     *
     * [plausibleTotalTimeS] è la banda in cui un impatto è fisicamente
     * possibile per questo arco: la deriva chi chiama dal profilo calibrato
     * ([com.miraglio.domain.audio.ImpactPlausibility]). È obbligatoria e
     * `null` significa "nessun veto", così nessun chiamante può ricadere su
     * una finestra fissa che descrive solo i 70 m.
     *
     * [autoClicker] fa riconoscere al motore, ascoltando, se il tiro ha un
     * clicker: non serve dichiararlo prima. Con l'automatico [useClicker] e
     * [releaseOffsetMs] sono ignorati, perché il cronometro parte dal picco
     * della corda che il motore ha sentito — non da un click spostato di un
     * offset dichiarato.
     *
     * [debugLabel] finisce nel nome del WAV di debug (per esempio la distanza
     * di calibrazione), così i file si riconoscono senza aprirli.
     *
     * [onState] riceve ogni cambio di stato utile all'interfaccia — è pensato
     * per una callback veloce, non per lavoro pesante. [onLevel] riceve il
     * picco di ogni blocco letto, per il grafico della forma d'onda.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun measure(
        useClicker: Boolean,
        releaseOffsetMs: Float,
        stability: StateFlow<Boolean>,
        plausibleTotalTimeS: ClosedFloatingPointRange<Float>?,
        autoClicker: Boolean = false,
        sampleRateHz: Int = AudioCaptureSource.DEFAULT_SAMPLE_RATE,
        sessionTimeoutS: Double = DEFAULT_SESSION_TIMEOUT_S,
        debugLabel: String = "",
        onState: (ShotTimingState) -> Unit = {},
        onLevel: (Float) -> Unit = {}
    ): AcousticSession = withContext(Dispatchers.Default) {

        val source = AudioCaptureSource(context, sampleRateHz)
        val analyzer = AcousticShotAnalyzer(sampleRateHz)
        val machine = ShotTimingMachine(
            ShotTimingConfig(
                sampleRateHz = sampleRateHz,
                plausibleTotalTimeS = plausibleTotalTimeS,
                autoClicker = autoClicker
            )
        )

        machine.startListening(useClicker, releaseOffsetMs)
        onState(machine.state.value)

        if (!source.open()) {
            machine.reset()
            return@withContext AcousticSession(
                state = machine.state.value,
                microphoneOpened = false
            )
        }

        val recorder = ShotAudioRecorder(
            sampleRateHz = sampleRateHz,
            directory = File(context.filesDir, DEBUG_AUDIO_DIRECTORY)
        ).apply { start() }

        // Blocchi da 20 ms: abbastanza piccoli da non perdere un evento
        // (il più corto dura 5 ms), abbastanza grandi da non svegliare la CPU
        // 48.000 volte al secondo.
        val buffer = FloatArray(sampleRateHz / 50)
        val startedAtNs = System.nanoTime()
        var stableSinceNs = 0L
        var lastStage = machine.state.value.stage
        var timedOut = false

        try {
            while (true) {
                // L'arresto dall'interfaccia deve fermare il ciclo subito, non
                // alla fine del timeout: senza questo controllo un `cancel()`
                // lascerebbe il microfono aperto fino alla scadenza.
                currentCoroutineContext().ensureActive()

                val elapsedS = (System.nanoTime() - startedAtNs) / 1_000_000_000.0
                if (elapsedS > sessionTimeoutS) {
                    timedOut = true
                    machine.reset()
                    break
                }

                val read = source.read(buffer)
                if (read <= 0) break
                recorder.append(buffer, read)

                var level = 0f
                for (i in 0 until read) {
                    val magnitude = abs(buffer[i])
                    if (magnitude > level) level = magnitude
                }
                onLevel(level)

                analyzer.processBlock(buffer, read).forEach { machine.onEvent(it) }
                machine.onProgress(analyzer.sampleIndex)

                val nowNs = System.nanoTime()
                if (stability.value) {
                    if (stableSinceNs == 0L) stableSinceNs = nowNs
                    val held = (nowNs - stableSinceNs) / 1_000_000L
                    if (held >= STABLE_HOLD_MS) machine.onStability(true)
                } else {
                    stableSinceNs = 0L
                }

                if (machine.state.value.stage == ShotStage.READY) {
                    machine.arm(analyzer.sampleIndex)
                }

                val current = machine.state.value
                if (current.stage != lastStage) {
                    lastStage = current.stage
                    onState(current)
                }
                if (current.isFinished) break
            }
        } finally {
            source.close()
        }

        val finalState = machine.state.value
        val debugFile = withContext(Dispatchers.IO) {
            runCatching {
                saveDebugAudio(recorder, finalState, debugLabel, sampleRateHz)
            }.getOrNull()
        }

        // Il grafico riceve ogni blocco letto (uno ogni 20 ms), quindi il
        // campione dello scocco e dell'impatto si riporta a un indice di blocco
        // dividendo per la dimensione del blocco.
        val blockSamples = (sampleRateHz / BLOCKS_PER_SECOND).coerceAtLeast(1)
        val measurement = finalState.measurement
        AcousticSession(
            state = finalState,
            microphoneOpened = true,
            timedOut = timedOut,
            debugAudioPath = debugFile?.absolutePath,
            releaseBlock = (measurement?.releaseIndex ?: finalState.releaseIndex)
                ?.div(blockSamples)?.toInt(),
            impactBlock = measurement?.impactIndex?.div(blockSamples)?.toInt()
        )
    }

    /**
     * Ascolta senza fermarsi: un tiro dopo l'altro, con lo stesso microfono
     * aperto.
     *
     * È il modo giusto di misurare una serie di tiri. Riaprire il microfono a
     * ogni tiro costerebbe tre o seicento millisecondi di buco (chiusura e
     * riapertura dell'ADC, più [STABLE_HOLD_MS] di assestamento) proprio fra un
     * tiro e l'altro, e riavvierebbe sensori e GPS ogni volta. Qui invece:
     *
     * - il microfono si apre **una volta** e la macchina a stati si riarma da
     *   sola alla fine di ogni tiro ([ShotTimingMachine.rearm]), senza
     *   ripassare da [ShotStage.SETTLING]: il telefono è già fermo;
     * - per [SHOT_COOLDOWN_S] dopo un tiro gli eventi non contano, così la coda
     *   dell'impatto (l'eco, la freccia sul paglione) non fa partire subito un
     *   secondo tiro inventato;
     * - "impatto non sentito" non chiude niente: il risultato a schermo resta e
     *   si continua ad ascoltare, come deve essere quando si tira una serie;
     * - la sessione si chiude da sola dopo [idleTimeoutS] senza tiri (tre
     *   minuti), non dopo un tetto fisso: chi sta per tirare non deve correre.
     *
     * L'audio resta in un buffer circolare e **non viene salvato**: [onShot]
     * riceve il pezzo del tiro già in memoria ([AcousticShot.audio]) e chi
     * chiama decide se e dove scriverlo. Un WAV per tiro, salvato sempre,
     * sarebbe memoria spesa senza motivo.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listen(
        useClicker: Boolean,
        releaseOffsetMs: Float,
        stability: StateFlow<Boolean>,
        plausibleTotalTimeS: ClosedFloatingPointRange<Float>?,
        autoClicker: Boolean = false,
        sampleRateHz: Int = AudioCaptureSource.DEFAULT_SAMPLE_RATE,
        idleTimeoutS: Double = DEFAULT_IDLE_TIMEOUT_S,
        onState: (ShotTimingState) -> Unit = {},
        onLevel: (Float) -> Unit = {},
        onShot: (AcousticShot) -> Unit = {}
    ): AcousticSession = withContext(Dispatchers.Default) {

        val source = AudioCaptureSource(context, sampleRateHz)
        val analyzer = AcousticShotAnalyzer(sampleRateHz)
        val machine = ShotTimingMachine(
            ShotTimingConfig(
                sampleRateHz = sampleRateHz,
                plausibleTotalTimeS = plausibleTotalTimeS,
                autoClicker = autoClicker
            )
        )

        machine.startListening(useClicker, releaseOffsetMs)
        onState(machine.state.value)

        if (!source.open()) {
            machine.reset()
            return@withContext AcousticSession(
                state = machine.state.value,
                microphoneOpened = false
            )
        }

        val recorder = ShotAudioRecorder(
            sampleRateHz = sampleRateHz,
            directory = File(context.filesDir, DEBUG_AUDIO_DIRECTORY)
        ).apply { start() }

        val buffer = FloatArray(sampleRateHz / BLOCKS_PER_SECOND)
        val blockSamples = (sampleRateHz / BLOCKS_PER_SECOND).coerceAtLeast(1)
        val cooldownSamples = (SHOT_COOLDOWN_S * sampleRateHz).toLong()
        val prerollSamples = (DEBUG_PREROLL_S * sampleRateHz).toLong()
        val postrollSamples = (DEBUG_POSTROLL_S * sampleRateHz).toLong()

        var stableSinceNs = 0L
        var lastStage = machine.state.value.stage
        var lastShotNs = System.nanoTime()
        var cooldownUntil = 0L
        var idleEnded = false

        try {
            while (true) {
                // Lo stop dall'interfaccia deve chiudere il microfono subito,
                // non al prossimo timeout: senza questo controllo `cancel()`
                // lascerebbe l'ADC aperto per minuti.
                currentCoroutineContext().ensureActive()

                if (idleTimeoutS > 0 &&
                    (System.nanoTime() - lastShotNs) / 1_000_000_000.0 > idleTimeoutS
                ) {
                    idleEnded = true
                    machine.reset()
                    break
                }

                val read = source.read(buffer)
                if (read <= 0) break
                recorder.append(buffer, read)

                var level = 0f
                for (i in 0 until read) {
                    val magnitude = abs(buffer[i])
                    if (magnitude > level) level = magnitude
                }
                onLevel(level)

                // Durante il cooldown il DSP continua a girare (gli indici
                // devono restare allineati al flusso) ma quello che sente non
                // entra nel motore: è la coda del tiro appena concluso.
                val accepting = analyzer.sampleIndex >= cooldownUntil
                analyzer.processBlock(buffer, read).forEach { event ->
                    if (accepting) machine.onEvent(event)
                }
                machine.onProgress(analyzer.sampleIndex)

                val nowNs = System.nanoTime()
                if (stability.value) {
                    if (stableSinceNs == 0L) stableSinceNs = nowNs
                    val held = (nowNs - stableSinceNs) / 1_000_000L
                    if (held >= STABLE_HOLD_MS) machine.onStability(true)
                } else {
                    stableSinceNs = 0L
                }

                if (machine.state.value.stage == ShotStage.READY) {
                    machine.arm(analyzer.sampleIndex)
                }

                val current = machine.state.value
                if (current.isFinished) {
                    lastShotNs = nowNs
                    onState(current)
                    if (current.stage == ShotStage.DONE) {
                        onShot(
                            shotOf(
                                state = current,
                                recorder = recorder,
                                prerollSamples = prerollSamples,
                                postrollSamples = postrollSamples,
                                blockSamples = blockSamples
                            )
                        )
                    }
                    machine.rearm(analyzer.sampleIndex)
                    cooldownUntil = analyzer.sampleIndex + cooldownSamples
                    onState(machine.state.value)
                    lastStage = machine.state.value.stage
                } else if (current.stage != lastStage) {
                    lastStage = current.stage
                    onState(current)
                }
            }
        } finally {
            source.close()
        }

        AcousticSession(
            state = machine.state.value,
            microphoneOpened = true,
            timedOut = idleEnded
        )
    }

    /** Il tiro misurato, con il suo audio in memoria e i due segni del grafico. */
    private fun shotOf(
        state: ShotTimingState,
        recorder: ShotAudioRecorder,
        prerollSamples: Long,
        postrollSamples: Long,
        blockSamples: Int
    ): AcousticShot {
        val measurement = requireNotNull(state.measurement)
        val clip = runCatching {
            recorder.clip(
                fromSample = measurement.releaseIndex - prerollSamples,
                toSample = measurement.impactIndex + postrollSamples,
                fileName = audioFileName(measurement)
            )
        }.getOrNull()
        return AcousticShot(
            state = state,
            releaseBlock = (measurement.releaseIndex / blockSamples).toInt(),
            impactBlock = (measurement.impactIndex / blockSamples).toInt(),
            audio = clip
        )
    }

    private fun audioFileName(measurement: ShotMeasurement): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val delta = "%.3fs".format(Locale.US, measurement.totalTimeS)
        return "miraglio_${stamp}_dt$delta.wav"
    }

    /**
     * Salva il WAV della sessione, tagliato intorno al tiro quando c'è.
     *
     * Con una misura si tengono i due secondi prima del rilascio (l'arco che si
     * tende) e i due dopo l'impatto (la freccia che cade): è la parte che serve
     * ad ascoltare se il rilascio è stato agganciato al picco giusto. Senza
     * impatto si tiene dal rilascio in poi; se non si è sentito proprio niente
     * (timeout) si tiene l'intera sessione, perché il problema può essere il
     * livello del microfono e non il motore.
     */
    private fun saveDebugAudio(
        recorder: ShotAudioRecorder,
        state: ShotTimingState,
        label: String,
        sampleRateHz: Int
    ): File? {
        val measurement = state.measurement
        val preroll = (DEBUG_PREROLL_S * sampleRateHz).toLong()
        val postroll = (DEBUG_POSTROLL_S * sampleRateHz).toLong()
        val fromSample = measurement?.let { it.releaseIndex - preroll }
        val toSample = when {
            measurement != null -> measurement.impactIndex + postroll
            state.releaseIndex != null -> state.releaseIndex!! + postroll
            else -> null
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val part = sanitize(label)
        val delta = measurement?.let { "_dt%.3fs".format(Locale.US, it.totalTimeS) }.orEmpty()
        return recorder.save(
            fileName = "miraglio_$stamp$part$delta.wav",
            fromSample = fromSample,
            toSample = toSample
        )
    }

    private fun sanitize(label: String): String {
        val cleaned = label.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return if (cleaned.isEmpty()) "" else "_$cleaned"
    }

    companion object {
        /** Quiete continua richiesta prima di armare il motore. */
        const val STABLE_HOLD_MS = 300L

        /**
         * Oltre questo tempo la sessione si chiude da sola.
         *
         * Quarantacinque secondi: in calibrazione bisogna potersi mettere in
         * posizione, tendere e tirare. Con i dodici di prima l'allarme arrivava
         * prima della freccia e sembrava un microfono occupato.
         */
        const val DEFAULT_SESSION_TIMEOUT_S = 45.0

        /**
         * Oltre questo tempo senza tiri l'ascolto continuo si ferma.
         *
         * Tre minuti: quanto basta per sistemare l'arco, cambiare bersaglio o
         * parlare con qualcuno senza che il microfono resti aperto per sempre.
         * Non è un tetto alla sessione — chi tira una serie può andare avanti
         * quanto vuole, ogni tiro azzera il conteggio.
         */
        const val DEFAULT_IDLE_TIMEOUT_S = 180.0

        /**
         * Silenzio fra un tiro e l'altro, in secondi.
         *
         * Dopo un tiro il motore si riarma, ma l'impatto ha una coda (l'eco sul
         * campo, la freccia che cade): senza questa pausa quella coda potrebbe
         * superare il pavimento e aprire un tiro che non esiste. Trecentocinquanta
         * millisecondi sono più della coda e meno del tempo fra due frecce.
         */
        const val SHOT_COOLDOWN_S = 0.35f

        /** Secondi di audio tenuti prima del rilascio nel WAV di debug. */
        private const val DEBUG_PREROLL_S = 2.0f

        /** Secondi di audio tenuti dopo l'impatto. */
        private const val DEBUG_POSTROLL_S = 2.0f

        /** Blocchi letti al secondo: uno ogni 20 ms, come il buffer di [measure]. */
        private const val BLOCKS_PER_SECOND = 50

        private const val DEBUG_AUDIO_DIRECTORY = "debug_audio"
    }
}
