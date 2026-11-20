package com.miraglio.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Il criterio della specifica: **non deve attivarsi durante l'appoggio**. Un
 * telefono posato e lasciato andare con un colpo è esattamente il caso che
 * farebbe partire il motore su un transiente che non è un tiro.
 */
class StabilityDetectorTest {

    private val periodNs = 10_000_000L // 100 Hz, come SENSOR_DELAY_GAME

    @Test
    fun unTelefonoFermoEDichiaratoStabile() {
        val detector = StabilityDetector()

        var stable = false
        for (i in 0..70) {
            stable = detector.add(
                timestampNs = i * periodNs,
                ax = 0.01f,
                ay = -0.02f,
                az = 0.005f
            )
        }

        assertTrue("fermo con rumore di fondo: dovrebbe essere stabile", stable)
    }

    @Test
    fun unTelefonoAppoggiatoNonEStabileSubito() {
        val detector = StabilityDetector()

        var stable = false
        // Primi 100 ms: il colpo sull'appoggio, con oscillazioni forti.
        for (i in 0..10) {
            stable = detector.add(
                timestampNs = i * periodNs,
                ax = 2.5f * sin(2.0 * PI * 4.0 * i / 100.0).toFloat(),
                ay = 1.0f,
                az = 0.0f
            )
        }

        assertEquals(false, stable)
    }

    @Test
    fun ilMovimentoContinuoNonDiventaStabile() {
        val detector = StabilityDetector()

        var stable = false
        for (i in 0..120) {
            stable = detector.add(
                timestampNs = i * periodNs,
                ax = 2.0f * sin(2.0 * PI * 3.0 * i / 100.0).toFloat(),
                ay = 0.0f,
                az = 0.0f
            )
        }

        assertEquals("una mano che oscilla non è un appoggio", false, stable)
    }

    /** La finestra deve essere piena: "fermo da 50 ms" non è fermo. */
    @Test
    fun laFinestraDeveEsserePiena() {
        val detector = StabilityDetector()

        var stable = false
        for (i in 0..4) {
            stable = detector.add(i * periodNs, 0.0f, 0.0f, 0.0f)
        }

        assertEquals(false, stable)
    }

    /** Dopo il colpo il telefono si ferma: prima o poi lo dice. */
    @Test
    fun dopoIlColpoTornaStabile() {
        val detector = StabilityDetector()
        var index = 0

        repeat(10) {
            detector.add(index * periodNs, 3.0f, 0.0f, 0.0f)
            index++
        }
        for (i in 0..70) {
            detector.add(index * periodNs, 0.0f, 0.0f, 0.0f)
            index++
        }

        assertTrue(detector.add(index * periodNs, 0.0f, 0.0f, 0.0f))
    }
}
