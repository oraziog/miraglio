package com.miraglio.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.miraglio.domain.sensor.StabilityDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorveglia il movimento del telefono e pubblica "è fermo?" per il telemetro.
 *
 * Usa l'accelerazione lineare (gravità già tolta) quando il telefono ce l'ha;
 * se manca ripiega sull'accelerometro, che va benissimo lo stesso: il criterio
 * guarda il **modulo** dell'accelerazione, e il modulo della gravità non
 * cambia quando si ruota il telefono. Senza il ripiego, un telefono senza
 * sensore lineare resterebbe "in movimento" per sempre e il motore non si
 * armerebbe mai.
 */
@Singleton
class MotionMonitor @Inject constructor(
    @ApplicationContext context: Context
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val linear: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val fallback: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val detector = StabilityDetector()

    private val _stable = MutableStateFlow(false)

    /** True solo quando la finestra è piena e il telefono non si muove. */
    val stable: StateFlow<Boolean> = _stable.asStateFlow()

    fun start() {
        detector.reset()
        _stable.value = false
        val sensor = linear ?: fallback ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        detector.reset()
        _stable.value = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = event.sensor
        if (sensor.type != Sensor.TYPE_LINEAR_ACCELERATION &&
            sensor.type != Sensor.TYPE_ACCELEROMETER
        ) {
            return
        }
        _stable.value = detector.add(
            timestampNs = event.timestamp,
            ax = event.values[0],
            ay = event.values[1],
            az = event.values[2]
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
