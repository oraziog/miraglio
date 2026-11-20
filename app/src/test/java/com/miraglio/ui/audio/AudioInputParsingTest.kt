package com.miraglio.ui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le distanze e le temperature si digitano su tastiere che in italiano e in
 * spagnolo mettono la virgola. Rifiutare "27,5" sarebbe un difetto dell'app,
 * non dell'arciere.
 */
class AudioInputParsingTest {

    @Test
    fun laVirgolaELaIlPuntoValgonoLoStesso() {
        assertEquals(27.5f, parseDecimal("27,5")!!, 1e-6f)
        assertEquals(27.5f, parseDecimal("27.5")!!, 1e-6f)
        assertEquals(27f, parseDecimal(" 27 ")!!, 1e-6f)
    }

    @Test
    fun ilTestoCheNonENumeroNonDiventaUnNumero() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal("1,2,3"))
        assertNull(parseDecimal("NaN"))
    }

    /** Il campo dell'offset non deve scrivere "25.0". */
    @Test
    fun iNumeriInteriNonPortanoIDecimali() {
        assertEquals("25", plainNumber(25f))
        assertEquals("0", plainNumber(0f))
        assertEquals("12.5", plainNumber(12.5f))
    }
}
