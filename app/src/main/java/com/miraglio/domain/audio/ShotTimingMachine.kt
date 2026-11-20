package com.miraglio.domain.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gli stati del telemetro. Sono quelli della specifica, nell'ordine in cui si
 * attraversano: si parte da [IDLE], si aspetta che il telefono sia fermo
 * ([SETTLING]) e poi si aspetta il tiro ([READY] → [ARMED]).
 */
enum class ShotStage {
    IDLE,
    SETTLING,
    READY,
    ARMED,
    RELEASE_DETECTED,
    IMPACT_WAIT,
    DONE,
    DONE_NO_IMPACT
}

/** Il risultato di un tiro: quello che l'interfaccia mostra e la calibrazione salva. */
data class ShotMeasurement(
    /** Tempo fra rilascio effettivo (offset compreso) e impatto. */
    val totalTimeS: Float,
    val releaseIndex: Long,
    val impactIndex: Long,
    val releaseAmplitude: Float,
    val impactAmplitude: Float
)

data class ShotTimingState(
    val stage: ShotStage,
    val measurement: ShotMeasurement? = null,
    val clickerUsed: Boolean = false,
    /**
     * Da dove parte il cronometro, appena il rilascio è riconosciuto.
     *
     * Non è ridondante con [measurement]: quello esiste solo se si è sentito
     * anche l'impatto, mentre lo scocco si sa già prima — serve a chi analizza
     * una registrazione e vuole sapere se il motore ha agganciato il tiro, o
     * all'interfaccia che può mostrare un "sentito scocco" durante l'attesa.
     */
    val releaseIndex: Long? = null
) {
    val isFinished: Boolean
        get() = stage == ShotStage.DONE || stage == ShotStage.DONE_NO_IMPACT
}

/**
 * Soglie e finestre del telemetro. Stanno tutte qui perché sono le uniche cose
 * che ha senso cambiare dopo una sessione sul campo, e perché il motore
 * (`AcousticShotAnalyzer`) e la macchina a stati devono usare **le stesse**
 * (un trigger a 3 sul rilevatore e una finestra diversa nella macchina
 * darebbero eventi che non entrano mai in nessuna finestra).
 */
data class ShotTimingConfig(
    val sampleRateHz: Int = 48000,
    val triggerRatio: Float = 3.0f,
    val detriggerRatio: Float = 1.5f,
    /** Inizio della finestra in cui cercare il rilascio dopo il clicker. */
    val releaseWindowStartMs: Float = 50f,
    /** Fine della stessa finestra: oltre, il clicker ha "cliccato a vuoto". */
    val releaseWindowEndMs: Float = 450f,
    /** Da quanto dopo il rilascio può arrivare il battifreccia. */
    val impactWindowStartS: Float = 0.15f,
    /** Fino a quando ha senso aspettarlo: oltre sono 100 m e passa. */
    val impactWindowEndS: Float = 2.20f,
    /**
     * Margine oltre la fine della finestra prima di decidere. Un evento è noto
     * solo quando è finito, quindi decide chi aspetta un pelo in più: la
     * finestra di ricerca resta quella di sopra, cambia solo il momento in cui
     * si tira la somma.
     */
    val resolutionSlackMs: Float = 60f,
    /**
     * Scarta un impatto più forte del rilascio.
     *
     * La specifica lo chiede, ma va detto che le due ampiezze arrivano da rami
     * filtrati diversi (acuto per il rilascio, grave per l'impatto): il
     * confronto è grossolano e serve a scartare un secondo rilascio scambiato
     * per impatto, non a misurare. È uno dei punti da validare sul campo.
     */
    val requireWeakerImpact: Boolean = true
)

/**
 * La logica del tiro: quando è arrivato il rilascio, quando l'impatto, e
 * quanto tempo è passato in mezzo.
 *
 * Riceve eventi già rilevati ([AcousticEvent]) e l'avanzamento del flusso
 * ([onProgress]), tutto in indici di campione. Non conosce audio, sensori né
 * Android: è la parte che si prova sui WAV sintetici.
 *
 * **Senza clicker** il primo transiente acuto è il rilascio. **Con clicker** il
 * primo transiente è il click, e il rilascio è il transiente **più forte**
 * dentro i 450 ms successivi: così un pre-click (la punta che sfiora il
 * bottone, o il rumore della corda che sfrega) non viene scambiato per il
 * rilascio solo perché arriva prima.
 */
class ShotTimingMachine(
    private val config: ShotTimingConfig = ShotTimingConfig()
) {

    private val samplesPerSecond = config.sampleRateHz.toFloat()
    private val releaseStartSamples = msToSamples(config.releaseWindowStartMs)
    private val releaseEndSamples = msToSamples(config.releaseWindowEndMs)
    private val slackSamples = msToSamples(config.resolutionSlackMs)
    private val impactStartSamples = (config.impactWindowStartS * samplesPerSecond).toLong()
    private val impactEndSamples = (config.impactWindowEndS * samplesPerSecond).toLong()

    private val _state = MutableStateFlow(ShotTimingState(ShotStage.IDLE))
    val state: StateFlow<ShotTimingState> = _state.asStateFlow()

    private var clicker = false
    private var offsetSamples = 0L
    private var click: AcousticEvent? = null
    private var releaseCandidates = mutableListOf<AcousticEvent>()
    private var release: AcousticEvent? = null
    private var lowEvents = mutableListOf<AcousticEvent>()
    private var highestIndex = 0L

    /**
     * Una promozione per tiro al massimo.
     *
     * Serve perch\u00e9 il caso da correggere \u00e8 uno solo: una frase presa per
     * click prima del click vero. Senza limite, dopo la promozione il battito
     * della corda pu\u00f2 superare di nuovo la soglia e il tiro riparte
     * all'infinito, perdendolo.
     */

    /** Azzera tutto e comincia ad aspettare che il telefono sia fermo. */
    fun startListening(useClicker: Boolean, releaseOffsetMs: Float) {
        clicker = useClicker
        offsetSamples = (releaseOffsetMs * samplesPerSecond / 1000f).toLong()
        click = null
        releaseCandidates = mutableListOf()
        release = null
        lowEvents = mutableListOf()
        highestIndex = 0L
        _state.value = ShotTimingState(ShotStage.SETTLING, clickerUsed = useClicker)
    }

    /** Il telefono è fermo: da qui si può armare il motore. */
    fun onStability(stable: Boolean) {
        if (_state.value.stage == ShotStage.SETTLING && stable) {
            _state.value = _state.value.copy(stage = ShotStage.READY)
        }
    }

    /** Arma il motore: da questo campione in poi gli eventi contano. */
    fun arm(sampleIndex: Long) {
        if (_state.value.stage != ShotStage.READY) return
        highestIndex = maxOf(highestIndex, sampleIndex)
        _state.value = _state.value.copy(stage = ShotStage.ARMED)
    }

    fun onEvent(event: AcousticEvent) {
        when (_state.value.stage) {
            ShotStage.ARMED -> when {
                event.branch == ShotBranch.HIGH_BAND && !clicker -> {
                    release = event
                    _state.value = _state.value.copy(
                        stage = ShotStage.RELEASE_DETECTED,
                        releaseIndex = event.peakIndex
                    )
                }

                event.branch == ShotBranch.HIGH_BAND -> {
                    // Con il clicker il primo picco acuto è il click; quelli
                    // dopo sono candidati al rilascio.
                    if (click == null) click = event else releaseCandidates += event
                }

                else -> lowEvents += event
            }

            ShotStage.RELEASE_DETECTED, ShotStage.IMPACT_WAIT ->
                if (event.branch == ShotBranch.LOW_BAND) {
                    lowEvents += event
                } else {
                    ripartiSePiuForte(event)
                }

            else -> Unit
        }
        pruneLowEvents()
    }

    /**
     * Avanzamento del flusso: serve a chiudere le finestre che nessun evento
     * chiuderebbe (il clicker che non trova il rilascio, il tiro senza
     * impatto). Va chiamata a ogni blocco letto, con l'ultimo indice.
     */
    fun onProgress(sampleIndex: Long): ShotTimingState {
        if (sampleIndex > highestIndex) highestIndex = sampleIndex

        when (_state.value.stage) {
            ShotStage.ARMED -> {
                val clickEvent = click
                if (clicker && clickEvent != null) {
                    val deadline = clickEvent.peakIndex + releaseEndSamples + slackSamples
                    if (highestIndex >= deadline) resolveClickerRelease()
                }
            }

            ShotStage.RELEASE_DETECTED -> {
                _state.value = _state.value.copy(stage = ShotStage.IMPACT_WAIT)
                resolveImpactIfDue()
            }

            ShotStage.IMPACT_WAIT -> resolveImpactIfDue()

            else -> Unit
        }
        return _state.value
    }

    /** Cliccato ma non rilasciato: si torna a sorvegliare, senza perdere il profilo. */
    private fun resolveClickerRelease() {
        val clickEvent = click
        if (clickEvent == null) {
            _state.value = _state.value.copy(stage = ShotStage.READY)
            return
        }
        val start = clickEvent.peakIndex + releaseStartSamples
        val end = clickEvent.peakIndex + releaseEndSamples
        val best = releaseCandidates
            .filter { it.peakIndex in start..end }
            .maxByOrNull { it.peakAmplitude }

        if (best == null) {
            click = null
            releaseCandidates = mutableListOf()
            _state.value = _state.value.copy(stage = ShotStage.READY)
        } else {
            release = best
            _state.value = _state.value.copy(
                stage = ShotStage.RELEASE_DETECTED,
                releaseIndex = releaseReference(best)
            )
        }
    }

    /**
     * Mentre si aspetta l'impatto si resta sordi allo scocco successivo per
     * due secondi e passa. In un campo silenzioso non succede niente (un tiro
     * ogni decina di secondi), ma su una registrazione con il parlato il motore
     * si aggancia a una frase e resta li' a fissarla mentre l'arciere tira:
     * su quaranta tiri veri ne trovava nove.
     *
     * Il rimedio si basa su una differenza che i dati mostrano netta: lo scocco
     * vero è il transiente acuto più forte del file, il parlato no. Se mentre
     * si aspetta l'impatto arriva un transiente **almeno il doppio** di quello
     * che si sta cronometrando, quello di prima era rumore: si riparte dal
     * nuovo, svuotando anche i gravi già raccolti (erano il rumore di prima,
     * non un impatto).
     */
    private fun ripartiSePiuForte(event: AcousticEvent) {
        val corrente = release ?: return
        if (event.peakAmplitude < corrente.peakAmplitude * RESTART_FACTOR) return

        lowEvents = mutableListOf()
        release = null
        if (clicker) {
            // Il nuovo colpo è il click: la corda deve ancora partire, e la sua
            // finestra si apre da qui.
            click = event
            releaseCandidates = mutableListOf()
            _state.value = ShotTimingState(ShotStage.ARMED, clickerUsed = true)
        } else {
            release = event
            _state.value = ShotTimingState(
                ShotStage.RELEASE_DETECTED,
                clickerUsed = false,
                releaseIndex = event.peakIndex
            )
        }
    }

    private fun resolveImpactIfDue() {
        val releaseEvent = release ?: return
        val deadline = releaseEvent.peakIndex + impactEndSamples + slackSamples
        if (highestIndex < deadline) return

        val start = releaseEvent.peakIndex + impactStartSamples
        val end = releaseEvent.peakIndex + impactEndSamples
        val best = lowEvents
            .filter { it.peakIndex in start..end }
            .maxByOrNull { it.peakAmplitude }

        if (best == null) {
            _state.value = _state.value.copy(stage = ShotStage.DONE_NO_IMPACT)
            return
        }
        if (config.requireWeakerImpact && best.peakAmplitude > releaseEvent.peakAmplitude) {
            _state.value = _state.value.copy(stage = ShotStage.DONE_NO_IMPACT)
            return
        }

        // Da dove parte il cronometro: vedi [releaseReference].
        val releaseIndex = releaseReference(releaseEvent)
        val totalTimeS = (best.peakIndex - releaseIndex) / samplesPerSecond

        _state.value = ShotTimingState(
            stage = ShotStage.DONE,
            measurement = ShotMeasurement(
                totalTimeS = totalTimeS,
                releaseIndex = releaseIndex,
                impactIndex = best.peakIndex,
                releaseAmplitude = releaseEvent.peakAmplitude,
                impactAmplitude = best.peakAmplitude
            ),
            clickerUsed = clicker,
            releaseIndex = releaseIndex
        )
    }

    /**
     * Da dove parte il cronometro per un rilascio già scelto.
     *
     * Il picco trovato nella finestra è la corda che parte: il click del
     * clicker è [click] e cade prima, fuori dalla finestra. Spostare indietro
     * quel picco dell'offset dichiarato conterebbe due volte lo stesso
     * ritardo — su un arco dove il click precede il rilascio di 150 ms,
     * 1,445 s diventerebbero 1,595 s, cioè sette metri di troppo a 70 m.
     *
     * L'offset serve perciò a spostare il riferimento **dal click al
     * rilascio**: quando l'utente lo dichiara, il riferimento è il click più
     * il ritardo. Con zero ("non lo so") resta la misura diretta del picco.
     */
    private fun releaseReference(releaseEvent: AcousticEvent): Long {
        val clickEvent = click
        return if (clicker && clickEvent != null && offsetSamples != 0L) {
            clickEvent.peakIndex + offsetSamples
        } else {
            releaseEvent.peakIndex
        }
    }

    /** Tiene solo gli eventi gravi che possono ancora cadere in una finestra. */
    private fun pruneLowEvents() {
        val cutoff = highestIndex - impactEndSamples - slackSamples
        lowEvents = lowEvents.filter { it.peakIndex >= cutoff }.toMutableList()
    }

    /**
     * Riporta la macchina in attesa: si usa per il timeout ("non è arrivato
     * niente") e per annullare la misura. Il profilo arco resta com'era.
     */
    fun reset() {
        click = null
        releaseCandidates = mutableListOf()
        release = null
        lowEvents = mutableListOf()
        highestIndex = 0L
        _state.value = ShotTimingState(ShotStage.IDLE, clickerUsed = clicker)
    }

    private fun msToSamples(milliseconds: Float): Long =
        (milliseconds * samplesPerSecond / 1000f).toLong()

    private companion object {
        /**
         * Di quanto un transiente deve essere più forte di quello in corso per
         * far ripartire il tiro: sul file reale gli scocchi stanno a 27–85 di
         * rapporto STA/LTA contro il 99° percentile del parlato a 14, quindi
         * il doppio separa i due casi senza prendere abbagli.
         */
        const val RESTART_FACTOR = 2.0f

    }
}
