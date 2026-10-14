package com.miraglio.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

@Singleton
class Inclinometer @Inject constructor(
    @ApplicationContext context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _pitchAngleDegrees = MutableStateFlow(0f)
    val pitchAngleDegrees: StateFlow<Float> = _pitchAngleDegrees.asStateFlow()

    private val _sensorActive = MutableStateFlow(false)
    val sensorActive: StateFlow<Boolean> = _sensorActive.asStateFlow()

    private var lastAccelTimestamp = 0L
    private var filteredPitchDegrees = 0f
    private var lastUpdateTimeNs = 0L
    private var biasCompensation = 0f

    companion object {
        private const val EMA_ALPHA = 0.15f
        private const val GYRO_WEIGHT = 0.98f
        private const val NOISE_TO_SENSOR_RATIO = 0.8
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        _sensorActive.value = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _sensorActive.value = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
        }
    }

    private fun processAccelerometer(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // Elevazione dell'asse di mira della fotocamera rispetto al piano
        // orizzontale (positiva = fotocamera alzata, 0 = mira orizzontale,
        // +90 = fotocamera verso il cielo, -90 = verso il suolo).
        // Con l'app in portrait la fotocamera guarda lungo l'asse Z del
        // dispositivo: alzare o abbassare il miro ruota attorno all'asse X
        // e muove la gravita' nel piano Y-Z, quindi ay = g*cos(elevazione)
        // e az = g*sin(elevazione) INDIPENDENTEMENTE dal rollio attorno
        // all'asse di mira. La formula precedente (atan2(-ax, ...)) misurava
        // l'inclinazione laterale: in portrait restava ~0 per costruzione
        // e dava +/-90 solo con il telefono appoggiato verticalmente sul fianco.
        val rawPitch = Math.toDegrees(atan2(az.toDouble(), ay.toDouble())).toFloat()

        if (lastAccelTimestamp == 0L) {
            filteredPitchDegrees = rawPitch
            lastAccelTimestamp = event.timestamp
            return
        }

        filteredPitchDegrees = EMA_ALPHA * rawPitch + (1 - EMA_ALPHA) * filteredPitchDegrees
        lastAccelTimestamp = event.timestamp

        _pitchAngleDegrees.value = filteredPitchDegrees
    }

    private fun processGyroscope(event: SensorEvent) {
        // L'elevazione ruota attorno all'asse X del dispositivo: valori[0].
        val gyroRateDegPerSec = Math.toDegrees(event.values[0].toDouble()).toFloat()

        if (lastUpdateTimeNs == 0L) {
            lastUpdateTimeNs = event.timestamp
            return
        }

        val dtSeconds = (event.timestamp - lastUpdateTimeNs) / 1_000_000_000.0f
        lastUpdateTimeNs = event.timestamp

        val gyroPitch = filteredPitchDegrees + gyroRateDegPerSec * dtSeconds

        val complementary = GYRO_WEIGHT * gyroPitch + (1 - GYRO_WEIGHT) * filteredPitchDegrees
        filteredPitchDegrees = complementary
        _pitchAngleDegrees.value = filteredPitchDegrees
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}