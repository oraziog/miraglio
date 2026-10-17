package com.miraglio.data.focal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La calibrazione porta punti di generazioni diverse: i vecchi a 640x480, i
 * nuovi a 1280x960. Se la media li mescola senza riscalarli, la focale che ne
 * esce e' un valore che non vale per nessuno dei due frame — e le distanze
 * sbagliano di oltre il 20% senza che niente lo dica.
 */
class FocalFrameNormalizationTest {

    private fun punto(
        id: Long = 1L,
        distanceM: Float = 10f,
        pixels: Float = 100f,
        knownSizeM: Float = 1.3f,
        zoom: Float = 1f,
        frameLongSide: Int = LEGACY_FRAME_LONG_SIDE
    ) = FocalPoint(
        id = id,
        distanceM = distanceM,
        pixels = pixels,
        knownSizeM = knownSizeM,
        axis = "H",
        timestamp = 0L,
        zoom = zoom,
        frameLongSide = frameLongSide
    )

    @Test
    fun `portare un punto vecchio al frame nuovo conserva la focale fisica`() {
        val vecchio = punto(pixels = 281f, zoom = 4f, frameLongSide = 640)
        val focalePrima = vecchio.impliedFocalPx

        val normalizzato = normalizeToFrame(listOf(vecchio), CURRENT_FRAME_LONG_SIDE).single()

        assertEquals(1280, normalizzato.frameLongSide)
        assertEquals("i pixel raddoppiano passando a 1280", 562f, normalizzato.pixels, 0.01f)
        // La focale salvata e' in pixel del frame: cambiando frame raddoppia
        // anche lei, ed e' giusto cosi' — a riscalarla per l'uso ci pensa
        // FocalCalibrationStore.focalForFrame.
        assertEquals(
            "la focale in px segue il frame",
            focalePrima * 2f,
            normalizzato.impliedFocalPx,
            0.01f
        )
        assertEquals(
            "la focale fisica e' la stessa: stesso rapporto px per unita' di frame",
            focalePrima / 640f,
            normalizzato.impliedFocalPx / 1280f,
            0.0001f
        )
    }

    @Test
    fun `un punto gia' al frame giusto non viene toccato`() {
        val punto = punto(pixels = 120f, frameLongSide = CURRENT_FRAME_LONG_SIDE)

        val normalizzato = normalizeToFrame(listOf(punto), CURRENT_FRAME_LONG_SIDE).single()

        assertEquals(punto.pixels, normalizzato.pixels, 0.001f)
        assertEquals(CURRENT_FRAME_LONG_SIDE, normalizzato.frameLongSide)
    }

    @Test
    fun `mescolare punti di frame diversi li porta tutti alla stessa base`() {
        val vecchio = punto(id = 1, distanceM = 10f, pixels = 100f, frameLongSide = 640)
        val nuovo = punto(id = 2, distanceM = 20f, pixels = 400f, frameLongSide = 1280)

        val normalizzati = normalizeToFrame(listOf(vecchio, nuovo), CURRENT_FRAME_LONG_SIDE)

        assertEquals(listOf(1280, 1280), normalizzati.map { it.frameLongSide })
        assertEquals("il vecchio raddoppia, il nuovo resta com'era", 200f, normalizzati[0].pixels, 0.01f)
        assertEquals(400f, normalizzati[1].pixels, 0.01f)
    }

    @Test
    fun `un frame dichiarato a zero non fa dividere per zero`() {
        val rotto = punto(pixels = 100f, frameLongSide = 0)

        val normalizzato = normalizeToFrame(listOf(rotto), CURRENT_FRAME_LONG_SIDE).single()

        assertEquals(CURRENT_FRAME_LONG_SIDE, normalizzato.frameLongSide)
        assertEquals("senza base non si riscala: si adotta il frame e basta", 100f, normalizzato.pixels, 0.001f)
    }
}
