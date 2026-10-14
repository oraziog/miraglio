package com.miraglio.data.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.miraglio.data.location.LocationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos

/**
 * Bussola del dispositivo: azimuth di PUNTAMENTO della fotocamera.
 *
 * L'asse di mira e' -Z del dispositivo (la fotocamera guarda "attraverso" lo
 * schermo); l'azimuth e' la proiezione orizzontale di quell'asse sul piano
 * mondo, cosi' il valore resta valido sia col telefono verticale che sdraiato.
 *
 * Il nord e' VERO: la declinazione magnetica e' calcolata con GeomagneticField
 * sull'ultima posizione nota (senza fix si usa il nord magnetico, errore
 * tipico di pochi gradi).
 */
@Singleton
class Compass @Inject constructor(
    @ApplicationContext context: Context,
    private val locationTracker: LocationTracker
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Azimuth di mira in gradi 0-360 rispetto al nord vero (0 = nord, 90 = est). */
    private val _azimuthDegrees = MutableStateFlow(0f)
    val azimuthDegrees: StateFlow<Float> = _azimuthDegrees.asStateFlow()

    private val _sensorActive = MutableStateFlow(false)
    val sensorActive: StateFlow<Boolean> = _sensorActive.asStateFlow()

    /** Declinazione magnetica corrente (gradi, est positivo). */
    private val _declinationDegrees = MutableStateFlow(0f)
    val declinationDegrees: StateFlow<Float> = _declinationDegrees.asStateFlow()

    // Matrice di rotazione riusata a ogni evento (evita allocazioni a 50+ Hz).
    private val rotationMatrix = FloatArray(9)

    // Smoothing su componenti trigonometriche: gestisce il wrap 359->1 grado.
    private var smoothSin = 0f
    private var smoothCos = 0f
    private var hasSample = false

    fun start() {
        locationTracker.currentLocation.value?.let { updateDeclination(it) }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        _sensorActive.value = true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _sensorActive.value = false
    }

    /** Ricalcola la declinazione magnetica sulla nuova posizione. */
    fun updateDeclination(location: Location) {
        val field = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        _declinationDegrees.value = field.declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Asse fotocamera (-Z dispositivo) espresso in coordinate mondo
        // (X=est, Y=nord): e' la TERZA colonna della matrice, negata.
        // R[row][col] = matrix[row * 3 + col].
        val east = -rotationMatrix[2]  // R[0][2]
        val north = -rotationMatrix[5] // R[1][2]

        val magneticAzimuth =
            Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat().let {
                (it + 360f) % 360f
            }

        // Media mobile esponenziale sui vettori unitari (niente salto 359->1).
        val rad = Math.toRadians(magneticAzimuth.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        if (!hasSample) {
            smoothSin = s
            smoothCos = c
            hasSample = true
        } else {
            smoothSin = EMA_ALPHA * s + (1 - EMA_ALPHA) * smoothSin
            smoothCos = EMA_ALPHA * c + (1 - EMA_ALPHA) * smoothCos
        }

        val smoothed = Math.toDegrees(
            atan2(smoothSin.toDouble(), smoothCos.toDouble())
        ).toFloat()
        // Azimuth magnetico + declinazione = azimuth rispetto al nord vero.
        _azimuthDegrees.value = (smoothed + _declinationDegrees.value + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val EMA_ALPHA = 0.2f
    }
}
