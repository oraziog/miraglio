package com.miraglio.ui.calibration

import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.calibration.CalibrationBundle
import com.miraglio.domain.calibration.CalibrationTransfer
import com.miraglio.domain.calibration.TransferLens
import com.miraglio.domain.calibration.TransferPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cosa succede quando la calibrazione passa da un telefono all'altro: sono
 * accoppiamenti di lenti, e un accoppiamento sbagliato non fa cadere niente —
 * sposta le frecce. Qui si prova che i punti arrivino sulla lente giusta, che le
 * lenti che qui non esistono restino fuori, e che i punti facciano il giro
 * completo (punti salvati → file → punti salvati) senza perdere numeri.
 */
class CalibrationTransferMappingTest {

    private fun punto(distanceM: Float = 30f, pixels: Float = 250f, axis: String = "H") =
        FocalPoint(
            id = distanceM.toLong(),
            distanceM = distanceM,
            pixels = pixels,
            knownSizeM = 1.22f,
            axis = axis,
            timestamp = 1_000L,
            zoom = 1f,
            frameLongSide = 1280
        )

    /** Lenti numerate come sul mirino: la prima e' la posizione 1. */
    @Test
    fun laNumerazioneDelleLentiEUnaPosizione() {
        val bundle = CalibrationTransferMapping.bundle(
            device = "Pixel 8 Pro",
            lensIds = listOf("0", "2", "3"),
            pointsByLens = mapOf(
                "2" to listOf(punto(distanceM = 20f)),
                "0" to listOf(punto(distanceM = 30f), punto(distanceM = 15f))
            )
        )

        assertEquals("Pixel 8 Pro", bundle.device)
        assertEquals("tre lenti posteriori", 3, bundle.lensCount)
        assertEquals(listOf(1, 2), bundle.lenses.map { it.position })
        assertEquals(3, bundle.totalPoints)
        assertEquals("i punti della lente 2 sono a 20 m", 20f, bundle.lenses[1].points[0].distanceM, 0f)
    }

    /** Una lente senza punti non entra nel file: chi importa non deve mapparla. */
    @Test
    fun leLentiSenzaPuntiNonSiEsportano() {
        val bundle = CalibrationTransferMapping.bundle(
            device = "Pixel 8 Pro",
            lensIds = listOf("0", "2"),
            pointsByLens = mapOf("0" to listOf(punto()))
        )

        assertEquals(1, bundle.lenses.size)
        assertEquals(1, bundle.lenses[0].position)
        assertEquals("la lente 2 resta dichiarata nel conteggio", 2, bundle.lensCount)
    }

    @Test
    fun senzaPuntiNonCEEsportazione() {
        val bundle = CalibrationTransferMapping.bundle(
            device = "Pixel 8 Pro",
            lensIds = listOf("0", "2"),
            pointsByLens = emptyMap()
        )

        assertTrue(bundle.lenses.isEmpty())
        assertEquals(0, bundle.totalPoints)
    }

    /** Stesse lenti: ogni punto torna sulla sua. */
    @Test
    fun iPuntiTornanoSullaStessaLente() {
        val punti = CalibrationTransferMapping.localPoints(
            bundle = bundleDiProva(),
            lensIds = listOf("0", "2", "3"),
            nowMillis = 5_000L
        )

        assertEquals(setOf("0", "3"), punti.keys)
        assertEquals(1, punti.getValue("0").size)
        assertEquals(1, punti.getValue("3").size)

        val punto = punti.getValue("0")[0]
        assertEquals(30f, punto.distanceM, 0f)
        assertEquals(250f, punto.pixels, 0f)
        assertEquals("H", punto.axis)
        assertEquals(1.22f, punto.knownSizeM, 0f)
        assertEquals(1280, punto.frameLongSide)
        // Gli id sono nuovi (di questa importazione) e non si ripetono.
        assertEquals(5_000L, punto.id)
        assertEquals(5_000L, punto.timestamp)
    }

    /**
     * Telefono con UNA lente in meno: la lente 3 del file non esiste qui e i
     * suoi punti si saltano. Appoggiarla alla seconda sarebbe una calibrazione
     * giusta sulla lente sbagliata.
     */
    @Test
    fun leLentiCheQuiNonEsistonoSiSaltano() {
        val punti = CalibrationTransferMapping.localPoints(
            bundle = bundleDiProva(),
            lensIds = listOf("0", "2"),
            nowMillis = 1L
        )

        assertEquals(setOf("0"), punti.keys)
    }

    /** Nessuna lente riconosciuta: non si scrive niente, e senza inventare id. */
    @Test
    fun senzaLentiLocaliNonSiScriveNiente() {
        val punti = CalibrationTransferMapping.localPoints(
            bundle = bundleDiProva(),
            lensIds = emptyList(),
            nowMillis = 1L
        )

        assertTrue(punti.isEmpty())
    }

    /** Gli id dei punti di una stessa importazione non si sovrappongono. */
    @Test
    fun gliIdDeiPuntiImportatiSonoUnivoci() {
        val punti = CalibrationTransferMapping.localPoints(
            bundle = bundleDiProva(puntiPerLente = 3),
            lensIds = listOf("0", "2", "3"),
            nowMillis = 7_000L
        )

        val tutti = punti.values.flatten().map { it.id }
        assertEquals("sei punti, sei id", 6, tutti.size)
        assertEquals(tutti.size, tutti.toSet().size)
    }

    /**
     * Il giro completo: punti salvati → file → punti salvati. E' il percorso
     * vero (il testo passa da un messaggio o da un QR), quindi si prova
     * passando dal testo codificato, non solo dalla mappa.
     */
    @Test
    fun ilGiroCompletoNonPerdeNumeri() {
        val salvati = listOf(punto(30f, 250f), punto(15f, 505f, axis = "V"))
        val bundle = CalibrationTransferMapping.bundle(
            device = "Pixel 8 Pro",
            lensIds = listOf("0"),
            pointsByLens = mapOf("0" to salvati)
        )

        val ricevuto = CalibrationTransfer.decode(CalibrationTransfer.encode(bundle))
        assertNotNull(ricevuto)
        requireNotNull(ricevuto)

        val punti = CalibrationTransferMapping.localPoints(ricevuto, listOf("0", "2"), 9_000L)
        val riletti = punti.getValue("0")

        assertEquals(2, riletti.size)
        assertEquals(salvati.map { it.distanceM }, riletti.map { it.distanceM })
        assertEquals(salvati.map { it.pixels }, riletti.map { it.pixels })
        assertEquals(salvati.map { it.axis }, riletti.map { it.axis })
        assertEquals(salvati.map { it.frameLongSide }, riletti.map { it.frameLongSide })
    }

    /** Un file di prova: due lenti (posizioni 1 e 3), un punto ciascuna. */
    private fun bundleDiProva(puntiPerLente: Int = 1): CalibrationBundle = CalibrationBundle(
        device = "Samsung SM-G991B",
        lensCount = 3,
        lenses = listOf(
            TransferLens(
                position = 1,
                points = (0 until puntiPerLente).map { punto(distanceM = 30f + it) }
                    .map { TransferPoint(it.distanceM, it.pixels, it.knownSizeM, it.axis, 1f, 1280) }
            ),
            TransferLens(
                position = 3,
                points = (0 until puntiPerLente).map { punto(distanceM = 40f + it) }
                    .map { TransferPoint(it.distanceM, it.pixels, it.knownSizeM, it.axis, 1f, 1280) }
            )
        )
    )
}
