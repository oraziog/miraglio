package com.miraglio.domain.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il grafico della forma d'onda è l'unica cosa che dice *perché* una misura non
 * parte: se resta piatto, il microfono non sente. Perché sia leggibile, la
 * finestra deve contenere gli ultimi valori nell'ordine giusto e girare bene
 * quando il buffer si riempie.
 */
class WaveformBufferTest {

    @Test
    fun laFinestraSonoGliUltimiValoriInOrdine() {
        val buffer = WaveformBuffer(capacity = 8)
        (1..5).forEach { buffer.push(it / 10f) }

        assertArrayEquals(
            floatArrayOf(0.3f, 0.4f, 0.5f),
            buffer.window(3),
            1e-6f
        )
    }

    @Test
    fun ilBufferGiraQuandoSiRiempie() {
        val buffer = WaveformBuffer(capacity = 4)
        (1..6).forEach { buffer.push(it / 10f) }

        assertEquals(4, buffer.size)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f, 0.5f, 0.6f), buffer.window(4), 1e-6f)
    }

    /** All'inizio della sessione ci sono meno valori di quelli richiesti. */
    @Test
    fun laFinestraNonInventaValori() {
        val buffer = WaveformBuffer(capacity = 10)
        buffer.push(0.5f)

        assertEquals(1, buffer.window(100).size)
        assertEquals(0, WaveformBuffer(capacity = 10).window(100).size)
    }

    @Test
    fun azzerareSvuotaLaFinestra() {
        val buffer = WaveformBuffer(capacity = 10)
        repeat(5) { buffer.push(0.2f) }

        buffer.clear()

        assertEquals(0, buffer.size)
        assertEquals(0, buffer.window(10).size)
    }

    /** Un livello non finito o fuori scala non deve rompere il disegno. */
    @Test
    fun iValoriStortiVengonoRipuliti() {
        val buffer = WaveformBuffer(capacity = 4)
        buffer.push(Float.NaN)
        buffer.push(-3f)
        buffer.push(7f)

        val window = buffer.window(3)

        assertTrue(window.all { it in 0f..1f })
    }

    /** Zoom più alto = meno tempo a schermo, mai zero valori. */
    @Test
    fun loZoomRestringeLaFinestra() {
        val uno = WaveformBuffer.visibleCount(WaveformBuffer.MIN_ZOOM)
        val otto = WaveformBuffer.visibleCount(WaveformBuffer.MAX_ZOOM)

        assertEquals(WaveformBuffer.BASE_VISIBLE, uno)
        assertTrue("a 8× devono entrare meno blocchi: $otto", otto < uno)
        assertTrue(otto >= 16)
    }

    @Test
    fun loZoomFuoriScalaVieneRiportatoNeiLimiti() {
        assertEquals(
            WaveformBuffer.visibleCount(WaveformBuffer.MIN_ZOOM),
            WaveformBuffer.visibleCount(0f)
        )
        assertEquals(
            WaveformBuffer.visibleCount(WaveformBuffer.MAX_ZOOM),
            WaveformBuffer.visibleCount(99f)
        )
    }
}
