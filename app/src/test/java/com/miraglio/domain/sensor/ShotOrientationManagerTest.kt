package com.miraglio.domain.sensor

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il criterio della specifica: **l'angolo resta a +15° anche dopo che il
 * telefono viene appoggiato a 0°**. Se l'inclinometro continuasse a dettare
 * legge, una misura fatta mirando in alto e poi posando il telefono darebbe
 * una distanza orizzontale più corta del 3% — due metri a 70 m.
 */
class ShotOrientationManagerTest {

    @Test
    fun lAngoloBloccatoNonSeguePiuIlSensore() {
        val sensor = MutableStateFlow(15f)
        val manager = ShotOrientationManager(sensor)

        assertEquals(15f, manager.aimPitchDeg)
        assertFalse(manager.isLocked)

        val locked = manager.lock()
        assertEquals(15f, locked)

        // Il telefono viene appoggiato a terra: il sensore dice 0.
        sensor.value = 0f

        assertEquals(15f, manager.aimPitchDeg)
        assertEquals(15f, manager.lockedPitchDeg.value)
        assertTrue(manager.isLocked)
    }

    @Test
    fun sbloccareRestituisceIlSensore() {
        val sensor = MutableStateFlow(12f)
        val manager = ShotOrientationManager(sensor)

        manager.lock()
        sensor.value = 3f
        manager.unlock()

        assertEquals(3f, manager.aimPitchDeg)
        assertEquals(null, manager.lockedPitchDeg.value)
        assertFalse(manager.isLocked)
    }

    /** Un angolo negativo (mirando in basso) si blocca come gli altri. */
    @Test
    fun lAngoloNegativoSiBlocca() {
        val sensor = MutableStateFlow(-8.5f)
        val manager = ShotOrientationManager(sensor)

        assertEquals(-8.5f, manager.lock())
        sensor.value = 0f

        assertEquals(-8.5f, manager.aimPitchDeg)
    }
}
