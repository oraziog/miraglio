package com.miraglio.power

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.miraglio.data.location.LocationTracker
import com.miraglio.data.sensor.Compass
import com.miraglio.data.sensor.Inclinometer
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val inclinometer: Inclinometer,
    private val compass: Compass,
    private val tracker: LocationTracker
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var graceJob: Job? = null

    /** True solo durante una Misura della distanza GPS (fase TRACKING). */
    @Volatile
    private var measurementTracking = false

    /**
     * WakeLock **parziale**, tenuto solo mentre la misura cammina.
     *
     * Camminando con il telefono in mano lo schermo si spegne (o si blocca) e
     * Android mette la CPU in doze: i fix GPS rallentano e il percorso perde
     * i passi giusti nel momento in cui servono di piu'. Un wake lock PARZIALE
     * non tiene acceso lo schermo (cio' che drenerebbe la batteria): tiene
     * sveglia la CPU, finche' la misura e' in corso. Appena finisce si rilascia,
     * e se l'app resta in background oltre la finestra di grazia GPS pure.
     *
     * Il timeout non e' una durata vera della misura: e' il **tappo di
     * sicurezza** che chiede lint (senza, un wake lock dimenticato resterebbe
     * acceso fino alla prossima ricarica) e una barriera contro il caso in cui
     * un'anomalia dimentichi il rilascio. Una camminata piu' lunga lo tiene
     * riacquisito a ogni rientro in TRACKING.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Chiamato dal MeasurementViewModel a ogni cambio di fase TRACKING/IDLE. */
    fun setMeasurementTracking(active: Boolean) {
        measurementTracking = active
        if (active) {
            acquireWalkWakeLock()
        } else {
            releaseWalkWakeLock()
            graceJob?.cancel()
            graceJob = null
        }
    }

    private fun acquireWalkWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Non si sopprime la regola lint del timeout: gli si dà quello che chiede.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.acquire(WAKE_LOCK_MAX_MS) }
    }

    private fun releaseWalkWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
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
            // Misura in corso: finestra di grazia, poi GPS spento (e con lui
            // il wake lock: senza piu' GPS non c'e' piu' niente da tenere
            // sveglio, e un wake lock senza rilascio resta acceso).
            graceJob = scope.launch {
                delay(MEASUREMENT_GRACE_MS)
                if (measurementTracking) {
                    tracker.stopLocationUpdates()
                    releaseWalkWakeLock()
                }
            }
        } else {
            tracker.stopLocationUpdates()
            releaseWalkWakeLock()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        graceJob?.cancel()
        graceJob = null
        if (measurementTracking) {
            tracker.startLocationUpdates()
            acquireWalkWakeLock()
        }
    }

    companion object {
        /** Finestra GPS in background durante una misura: 10 minuti. */
        const val MEASUREMENT_GRACE_MS = 10L * 60_000

        /**
         * Tappo del wake lock: 60 minuti, ben oltre una camminata normale
         * (la finestra GPS in background è di 10). Serve a non lasciare la CPU
         * sveglia per sempre se il rilascio non arrivasse mai.
         */
        private const val WAKE_LOCK_MAX_MS = 60L * 60_000

        /** Etichetta nel dump batteria: chi drena deve sapere chi è. */
        private const val WAKE_LOCK_TAG = "miraglio:measurement"
    }
}
