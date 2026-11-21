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
    val requireWeakerImpact: Boolean = true,

    /**
     * L'intervallo di Δ (scocco→impatto) fisicamente possibile per questo arco,
     * quando lo si conosce dal modello calibrato.
     *
     * Serve all'ultimo filtro della scelta dell'impatto: un candidato la cui Δ
     * implichi una distanza fuori dal campo di tiro non può essere il
     * battifreccia, per quanto somigli a un urto. `null` (nessuna calibrazione)
     * lascia il comportamento precedente.
     */
    val plausibleTotalTimeS: ClosedFloatingPointRange<Float>? = null
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
 *
 * Il primo transiente può però essere una frase: quando ne arriva un altro
 * almeno [ANCORA_CLICK_FACTOR] volte più forte, quello è il click vero e la
 * finestra riparte da lì (vedi [onEvent]).
 *
 * **L'impatto non è solo un evento triggerato.** A distanza breve il colpo
 * torna troppo quieto rispetto al rilascio per superare il trigger del ramo
 * grave (a 18 m il rapporto STA/LTA all'impatto è 0,4): nella scelta entrano
 * perciò anche i picchi locali dell'inviluppo lo+mid ([ShotBranch.IMPACT_PEAK],
 * vedi [BroadbandPeakDetector]), filtrati dalla stessa banda di plausibilità.
 * L'ordine della scelta è: prima i picchi dell'inviluppo, poi i transienti a
 * banda larga, poi i gravi triggerati. I picchi vengono prima perché sono
 * l'unico candidato che esiste anche a distanza breve; i transienti a banda
 * larga restano la scelta buona quando i picchi mancano (parlato che copre
 * l'impatto). Il rilevatore di scocco e il clicker non cambiano.
 */
class ShotTimingMachine(
    private val config: ShotTimingConfig = ShotTimingConfig()
) {

    private val samplesPerSecond = config.sampleRateHz.toFloat()
    private val releaseStartSamples = msToSamples(config.releaseWindowStartMs)
    private val releaseEndSamples = msToSamples(config.releaseWindowEndMs)
    private val slackSamples = msToSamples(config.resolutionSlackMs)
    private val broadbandSamples = msToSamples(BROADBAND_TOLERANCE_MS)

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

    /** Picchi locali di lo+mid: candidati d'impatto che il trigger non vede. */
    private var peakEvents = mutableListOf<AcousticEvent>()

    /** Transienti acuti recenti: l'impatto deve avere un corrispondente qui. */
    private var highEvents = mutableListOf<AcousticEvent>()
    private var highestIndex = 0L

    /**
     * Azzera tutto e comincia ad aspettare che il telefono sia fermo.
     */
    fun startListening(useClicker: Boolean, releaseOffsetMs: Float) {
        clicker = useClicker
        offsetSamples = (releaseOffsetMs * samplesPerSecond / 1000f).toLong()
        click = null
        releaseCandidates = mutableListOf()
        release = null
        lowEvents = mutableListOf()
        peakEvents = mutableListOf()
        highEvents = mutableListOf()
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
        // I picchi d'inviluppo sono candidati d'impatto, non eventi della
        // macchina a stati: non possono essere uno scocco né far ripartire il
        // tiro, e si conservano solo per la scelta dell'impatto.
        if (event.branch == ShotBranch.IMPACT_PEAK) {
            peakEvents += event
            pruneEvents()
            return
        }
        // Gli acuti si tengono tutti: servono a riconoscere l'impatto, che è
        // un urto e quindi arriva anche lassù (vedi [scegliImpatto]).
        if (event.branch == ShotBranch.HIGH_BAND) highEvents += event
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
                    // dopo sono candidati al rilascio. Ma il primo picco può
                    // essere una frase: il click vero, quando arriva, è di
                    // un'altra classe di ampiezza (vedi [ANCORA_CLICK_FACTOR]).
                    val clickEvent = click
                    when {
                        clickEvent == null -> click = event
                        event.peakAmplitude >= clickEvent.peakAmplitude * ANCORA_CLICK_FACTOR -> {
                            click = event
                            releaseCandidates = mutableListOf()
                        }

                        else -> releaseCandidates += event
                    }
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
        pruneEvents()
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
        // La plausibilità si misura dal riferimento vero del cronometro, non
        // dal picco grezzo: con un clicker dichiarato i due non coincidono.
        val releaseIndex = releaseReference(releaseEvent)
        val best = scegliImpatto(
            lowEvents.filter { it.peakIndex in start..end },
            peakEvents.filter { it.peakIndex in start..end },
            releaseIndex = releaseIndex
        )

        if (best == null) {
            _state.value = _state.value.copy(stage = ShotStage.DONE_NO_IMPACT)
            return
        }
        if (config.requireWeakerImpact && best.peakAmplitude > releaseEvent.peakAmplitude) {
            _state.value = _state.value.copy(stage = ShotStage.DONE_NO_IMPACT)
            return
        }

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
     * Fra i transienti gravi della finestra sceglie **quale** è l'impatto.
     *
     * Prendere il più forte funziona su un campo silenzioso e sbaglia appena
     * qualcuno parla: sul file di riferimento il battifreccia sta a 0,002–0,02
     * di ampiezza mentre la voce arriva a 0,3, quindi il più forte è quasi
     * sempre una parola (misurato: 11 tiri su 36 con il Δ sbagliato, distanze
     * da 30 a 96 m). Né la durata, né il rapporto STA/LTA, né la salita sul
     * rumore locale separano i due casi.
     *
     * Quello che li separa è la **larghezza di banda**: una freccia che picchia
     * sul paglione è un urto, e un urto eccita anche l'acuto; una voce è
     * armonica e sta in basso. Sui tiri sbagliati misurati: l'impatto vero ha
     * un transiente del ramo acuto entro ±20 ms in 11 casi su 11, le scelte
     * sbagliate solo in 3 su 11. Quindi si preferisce il candidato grave che ha
     * un acuto coincidente.
     *
     * A distanza breve, però, il trigger grave non produce proprio l'evento
     * (l'impatto è quieto rispetto al rilascio appena avvenuto): per questo
     * [picchi] porta i picchi locali dell'inviluppo lo+mid, già filtrati sulla
     * presenza di **entrambe** le bande d'impatto da [BroadbandPeakDetector].
     * Vengono **prima** dei transienti a banda larga e dei gravi senza acuto:
     * a 18 m sono l'unico candidato che esiste. Il file a 70 m resta a 39/40 con
     * 188 rilevamenti e +8 ms mediani, quindi la precedenza non gli costa nulla.
     */
    private fun scegliImpatto(
        candidati: List<AcousticEvent>,
        picchi: List<AcousticEvent>,
        releaseIndex: Long
    ): AcousticEvent? {
        if (candidati.isEmpty() && picchi.isEmpty()) return null
        val conAcuto = candidati.mapNotNull { grave ->
            val acuto = highEvents
                .filter { kotlin.math.abs(it.peakIndex - grave.peakIndex) <= broadbandSamples }
                .maxByOrNull { it.peakAmplitude }
            acuto?.let { grave to it }
        }
        val banda = config.plausibleTotalTimeS
        fun plausibile(index: Long): Boolean {
            if (banda == null) return true
            return (index - releaseIndex) / samplesPerSecond in banda
        }
        fun plausibile(evento: AcousticEvent): Boolean = plausibile(evento.peakIndex)

        // 1. Picco d'inviluppo lo+mid nella banda: la misura più fine, perché
        //    l'inviluppo somma le due bande d'impatto invece di aspettare che
        //    il grave superi un trigger (a 18 m non lo supera: STA/LTA 0,4).
        //    Vince il più forte. Provato a scartare i picchi che seguono uno
        //    precedente entro 200 ms (l'eco di un urto arriva dopo ed è più
        //    forte): sui tiri 6 e 14 del compound li ripara, ma rompe i tiri
        //    in cui un rumore secco precede l'impatto di 55–78 ms (barebow
        //    54/60, compound 32/36). Non c'è modo di separarli senza toccare lo
        //    scocco o stringere la banda: resta il più forte.
        picchi
            .filter { plausibile(it) }
            .maxByOrNull { it.peakAmplitude }
            ?.let { return it }

        // 2. Nessun picco: banda larga **e** fisicamente possibile, il caso
        //    buono dei transienti triggerati (è quello che regge i 70 m).
        conAcuto
            .filter { plausibile(it.first) }
            .maxByOrNull { (_, acuto) -> acuto.peakAmplitude }
            ?.let { return it.first }

        // 3. Nessun picco: si ripiega su un grave plausibile, anche senza
        //    acuto coincidente.
        candidati
            .filter { plausibile(it) }
            .maxByOrNull { it.peakAmplitude }
            ?.let { return it }

        // 4. Nessun candidato entra nella banda (bersaglio fuori dal campo di
        //    tiro): meglio un impatto a banda larga che niente, poi il
        //    comportamento di sempre.
        conAcuto.maxByOrNull { (_, acuto) -> acuto.peakAmplitude }?.let { return it.first }
        return (candidati + picchi).maxByOrNull { it.peakAmplitude }
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

    /** Tiene solo gli eventi che possono ancora cadere in una finestra. */
    private fun pruneEvents() {
        val cutoff = highestIndex - impactEndSamples - slackSamples
        lowEvents = lowEvents.filter { it.peakIndex >= cutoff }.toMutableList()
        peakEvents = peakEvents.filter { it.peakIndex >= cutoff }.toMutableList()
        highEvents = highEvents.filter { it.peakIndex >= cutoff }.toMutableList()
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
        peakEvents = mutableListOf()
        highEvents = mutableListOf()
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

        /**
         * Di quanto un transiente acuto deve superare il "click" in corso per
         * essere il click vero.
         *
         * Sui tiri 3, 34 e 20 della registrazione il primo picco acuto è una
         * frase (0,003–0,006) e il click del clicker arriva 350–470 ms dopo a
         * 0,25–0,40, cioè 50–113 volte tanto; lo scocco della corda, che viene
         * dopo il click, non supera mai il click di più di 3 volte su tutti i
         * quaranta tiri. Quattro lascia fuori le frasi e non tocca la coppia
         * click→scocco.
         */
        const val ANCORA_CLICK_FACTOR = 4.0f

        /**
         * Di quanto può distare l'acuto dall'impatto perché siano lo stesso
         * urto: 20 ms. Misurato sui tiri veri; i due rami sono filtri diversi
         * dello stesso colpo, e il picco può cadere qualche millisecondo
         * prima o dopo.
         */
        const val BROADBAND_TOLERANCE_MS = 20f
    }
}
