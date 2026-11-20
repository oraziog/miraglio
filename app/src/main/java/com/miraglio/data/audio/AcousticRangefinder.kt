package com.miraglio.data.audio

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.miraglio.domain.audio.AcousticShotAnalyzer
import com.miraglio.domain.audio.ShotStage
import com.miraglio.domain.audio.ShotTimingConfig
import com.miraglio.domain.audio.ShotTimingMachine
import com.miraglio.domain.audio.ShotTimingState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il telemetro acustico completo: microfono, filtri, macchina a stati.
 *
 * Fa una cosa sola e la fa in un colpo: aspetta che il telefono sia fermo,
 * arma il motore, legge fino a quando il tiro non è concluso e restituisce lo
 * stato finale (misura, oppure "impatto non sentito").
 *
 * **Perché l'armamento non è immediato.** Diventare "fermo" e premere il
 * pulsante sono due cose diverse: chi appoggia il telefono lo lascia andare
 * con un colpo sul tavolo, e quel colpo è un transiente perfetto per essere
 * scambiato per uno scocco. Il motore si arma solo dopo [STABLE_HOLD_MS] di
 * quiete continua, cioè quando il colpo è passato e la mano è via.
 *
 * **Perché c'è un tetto di tempo.** Se il tiro non parte (l'arciere cambia
 * idea, la freccia si incastra, il telefono si è spento) il ciclo non deve
 * restare aperto sul microfono: dopo [DEFAULT_SESSION_TIMEOUT_S] si chiude e
 * restituisce lo stato raggiunto. Quel tetto è anche l'unico posto in cui
 * `System.nanoTime()` è lecito: misura la durata della sessione, non
 * l'intervallo fra due eventi.
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
     * [onState] riceve ogni cambio di stato utile all'interfaccia — è pensato
     * per una callback veloce, non per lavoro pesante.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun measure(
        useClicker: Boolean,
        releaseOffsetMs: Float,
        stability: StateFlow<Boolean>,
        sampleRateHz: Int = AudioCaptureSource.DEFAULT_SAMPLE_RATE,
        sessionTimeoutS: Double = DEFAULT_SESSION_TIMEOUT_S,
        onState: (ShotTimingState) -> Unit = {}
    ): ShotTimingState = withContext(Dispatchers.Default) {

        val source = AudioCaptureSource(context, sampleRateHz)
        val analyzer = AcousticShotAnalyzer(sampleRateHz)
        val machine = ShotTimingMachine(ShotTimingConfig(sampleRateHz = sampleRateHz))

        machine.startListening(useClicker, releaseOffsetMs)
        onState(machine.state.value)

        if (!source.open()) {
            machine.reset()
            return@withContext machine.state.value
        }

        // Blocchi da 20 ms: abbastanza piccoli da non perdere un evento
        // (il più corto dura 5 ms), abbastanza grandi da non svegliare la CPU
        // 48.000 volte al secondo.
        val buffer = FloatArray(sampleRateHz / 50)
        val startedAtNs = System.nanoTime()
        var stableSinceNs = 0L
        var lastStage = machine.state.value.stage

        try {
            while (true) {
                val elapsedS = (System.nanoTime() - startedAtNs) / 1_000_000_000.0
                if (elapsedS > sessionTimeoutS) {
                    machine.reset()
                    break
                }

                val read = source.read(buffer)
                if (read <= 0) break

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

        machine.state.value
    }

    companion object {
        /** Quiete continua richiesta prima di armare il motore. */
        const val STABLE_HOLD_MS = 300L

        /** Oltre questo tempo la sessione si chiude da sola. */
        const val DEFAULT_SESSION_TIMEOUT_S = 12.0
    }
}
