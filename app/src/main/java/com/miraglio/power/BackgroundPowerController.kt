package com.miraglio.power

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.sensor.Compass
import com.miraglio.data.sensor.Inclinometer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spegne i consumatori quando l'app va in background (schermo spento o
 * app ridotta a icona):
 *  - sensori (accelerometro, giroscopio, compasso): si fermano sempre subito;
 *  - camera: CameraX gia' la stacca da solo legandola al lifecycle della rotta;
 *  - GPS: si ferma subito, TRANNE durante una Misura della distanza (fase
 *    TRACKING), quando resta attivo in background per [MEASUREMENT_GRACE_MS]
 *    per dare all'arciere il tempo di raggiungere il punto finale a piedi.
 *
 * Al ritorno in primo piano: se la misura e' ancora in corso il GPS riparte
 * subito; i sensori ripartono quando si torna sulla schermata di miro
 * (CaptureScreen riavvia startSensors su ON_RESUME) e la camera si rilega
 * con rebindCamera(). Nessun dato viene perso: l'immagine congelata e le
 * misure restano in memoria durante il background.
 *
 * Installazione: MiraglioApp lo registra su ProcessLifecycleOwner, il
 * lifecycle del processo: i suoi eventi ON_START/ON_STOP corrispondono
 * all'app in primo piano / in background, indipendentemente dalla rotta.
 */
@Singleton
class BackgroundPowerController @Inject constructor(
    private val inclinometer: Inclinometer,
    private val compass: Compass,
    private val tracker: LocationTracker
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var graceJob: Job? = null

    /** True solo durante una Misura della distanza GPS (fase TRACKING). */
    @Volatile
    private var measurementTracking = false

    /** Chiamato dal MeasurementViewModel a ogni cambio di fase TRACKING/IDLE. */
    fun setMeasurementTracking(active: Boolean) {
        measurementTracking = active
        if (!active) {
            graceJob?.cancel()
            graceJob = null
        }
    }

    fun install(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // Sensori: in background nessun consumo utile per l'app.
        inclinometer.stop()
        compass.stop()

        graceJob?.cancel()
        if (measurementTracking) {
            // Misura in corso: finestra di grazia, poi GPS spento.
            graceJob = scope.launch {
                delay(MEASUREMENT_GRACE_MS)
                if (measurementTracking) {
                    tracker.stopLocationUpdates()
                }
            }
        } else {
            tracker.stopLocationUpdates()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        graceJob?.cancel()
        graceJob = null
        if (measurementTracking) {
            tracker.startLocationUpdates()
        }
    }

    companion object {
        /** Finestra GPS in background durante una misura: 10 minuti. */
        const val MEASUREMENT_GRACE_MS = 10L * 60_000
    }
}
