package com.miraglio.domain.focal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocalVerificationTest {

    @Test
    fun `focale identica e' in linea`() {
        assertEquals(0f, FocalVerification.relativeError(1200f, 1200f)!!, 0.0001f)
        assertTrue(FocalVerification.matches(1200f, 1200f) == true)
    }

    @Test
    fun `scarto entro la tolleranza e' accettato`() {
        // 10%: dentro il 15% ammesso per il tratto a mano delle linee.
        val expected = 1000f
        val measured = 1100f
        assertEquals(0.10f, FocalVerification.relativeError(expected, measured)!!, 0.0001f)
        assertTrue(FocalVerification.matches(expected, measured) == true)
    }

    @Test
    fun `scarto oltre la tolleranza viene segnalato`() {
        // Una lente diversa, o un punto misurato male: 30% non e' "lo stesso
        // telefono".
        val expected = 1000f
        val measured = 700f
        assertEquals(0.30f, FocalVerification.relativeError(expected, measured)!!, 0.0001f)
        assertFalse(FocalVerification.matches(expected, measured) == true)
    }

    @Test
    fun `valori non validi non producono un confronto`() {
        assertNull(FocalVerification.relativeError(0f, 1000f))
        assertNull(FocalVerification.relativeError(1000f, 0f))
        assertNull(FocalVerification.relativeError(Float.NaN, 1000f))
        assertNull(FocalVerification.matches(-1f, 1000f))
    }
}
