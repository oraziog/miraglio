package com.miraglio.data.focal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * Un punto costruito a ritroso dalla focale che deve implicare, sul frame
     * in cui e' stato misurato: cosi' il test dice "questo punto e' giusto" e
     * non dipende dalla formula del pinhole.
     */
    private fun puntoDaFocale(
        id: Long,
        distanceM: Float,
        focalPx: Float,
        frameLongSide: Int
    ) = punto(
        id = id,
        distanceM = distanceM,
        pixels = focalPx * 1.3f / distanceM,
        frameLongSide = frameLongSide
    )

    /**
     * Il caso vero del guasto: tre misure a 640 e una aggiunta dopo
     * l'aggiornamento a 1280. La media dei pixel non confrontabili da' una
     * focale del 25% piu' alta, e chi ha in mano quel telefono non lo sa. La
     * riparazione deve rimettere i punti sullo stesso frame e far tornare la
     * focale vera, senza buttare via nessuna misura.
     */
    @Test
    fun `una calibrazione a 640 con un punto a 1280 si ripara da sola`() {
        val veraFocaleA640 = 700f
        val punti = listOf(
            puntoDaFocale(id = 1, distanceM = 10f, focalPx = veraFocaleA640, frameLongSide = 640),
            puntoDaFocale(id = 2, distanceM = 20f, focalPx = veraFocaleA640, frameLongSide = 640),
            puntoDaFocale(id = 3, distanceM = 30f, focalPx = veraFocaleA640, frameLongSide = 640),
            puntoDaFocale(id = 4, distanceM = 40f, focalPx = veraFocaleA640 * 2f, frameLongSide = 1280)
        )

        assertEquals(
            "prima della riparazione la media e' proprio quella sbagliata",
            (veraFocaleA640 * 1.25f).toDouble(),
            punti.map { it.impliedFocalPx }.average(),
            0.5
        )

        val riparati = requireNotNull(repairedFrames(punti))

        assertEquals(List(4) { CURRENT_FRAME_LONG_SIDE }, riparati.map { it.frameLongSide })
        assertEquals(
            "la focale che ne esce e' quella vera, al frame nuovo",
            (veraFocaleA640 * 2f).toDouble(),
            riparati.map { it.impliedFocalPx }.average(),
            0.01
        )
    }

    /** Chi ha tutte le misure a 640 non ha niente da riparare: il suo dato e' sano. */
    @Test
    fun `una calibrazione tutta a 640 resta com'e`() {
        val punti = listOf(
            puntoDaFocale(id = 1, distanceM = 10f, focalPx = 700f, frameLongSide = 640),
            puntoDaFocale(id = 2, distanceM = 30f, focalPx = 700f, frameLongSide = 640)
        )

        assertNull(repairedFrames(punti))
    }

    @Test
    fun `un punto solo non e' un guasto da riparare`() {
        assertNull(repairedFrames(listOf(punto(distanceM = 20f, pixels = 91f, frameLongSide = 640))))
        assertNull(repairedFrames(emptyList()))
    }

    @Test
    fun `un frame dichiarato a zero non fa dividere per zero`() {
        val rotto = punto(pixels = 100f, frameLongSide = 0)

        val normalizzato = normalizeToFrame(listOf(rotto), CURRENT_FRAME_LONG_SIDE).single()

        assertEquals(CURRENT_FRAME_LONG_SIDE, normalizzato.frameLongSide)
        assertEquals("senza base non si riscala: si adotta il frame e basta", 100f, normalizzato.pixels, 0.001f)
    }
}
