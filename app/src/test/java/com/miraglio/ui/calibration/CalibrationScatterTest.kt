package com.miraglio.ui.calibration

import com.miraglio.data.focal.FocalPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il grafico serve a una cosa sola: far vedere subito il punto misurato male.
 * Se l'outlier non viene segnalato (o se un punto buono viene segnalato), il
 * grafico e' peggio di niente — spinge a cancellare la misura giusta.
 * Da qui i controlli: la retta deve restare dove sta la maggioranza anche con
 * un punto storto in mezzo, e la normalizzazione (dimensione e zoom) deve
 * togliere le differenze che non sono errori.
 */
class CalibrationScatterTest {

    /**
     * Un punto che implica [focalPx] di focale a [distanceM] metri.
     *
     * Si costruisce a ritroso dal modello pinhole (`pixel = f × s × zoom / d`)
     * perche' il test deve dire "questo punto e' corretto" e non "questo punto
     * produce quei numeri": la verifica di fondo e' che il calcolo ne ricavi
     * indietro la stessa focale.
     */
    private fun punto(
        id: Long = 1L,
        distanceM: Float,
        focalPx: Float,
        knownSizeM: Float = 1.22f,
        zoom: Float = 1f
    ): FocalPoint = FocalPoint(
        id = id,
        distanceM = distanceM,
        pixels = focalPx * knownSizeM * zoom / distanceM,
        knownSizeM = knownSizeM,
        axis = "H",
        timestamp = 0L,
        zoom = zoom,
        frameLongSide = 1280
    )

    private val puntiGiusti = listOf(
        punto(id = 1, distanceM = 10f, focalPx = 700f),
        punto(id = 2, distanceM = 20f, focalPx = 700f),
        punto(id = 3, distanceM = 30f, focalPx = 700f),
        punto(id = 4, distanceM = 40f, focalPx = 700f)
    )

    /** Quattro misure pulite: nessun indiziato, retta orizzontale a 700 px. */
    @Test
    fun PuntiGiustiNonVengonoSegnalati() {
        val scatter = requireNotNull(calibrationScatter(puntiGiusti))

        assertEquals(emptyList<ScatterPoint>(), scatter.outliers)
        assertEquals("punti tutti uguali: scarto massimo nullo", 0f, scatter.maxDeviation, 0.0001f)
        assertEquals("i punti vanno in ordine di distanza", 4, scatter.points.size)
        assertTrue(
            "la retta deve restare piatta su 700 px, non su un valore inventato",
            kotlin.math.abs(scatter.expectedFocalAt(25f) - 700f) <= 700f * scatter.tolerance
        )
    }

    /**
     * Il caso vero: quattro misure a 700 px e una sbagliata che ne implicherebbe
     * il doppio. Il punto storto deve essere l'unico indiziato, e la retta non
     * deve seguirlo (e' il motivo per cui si fa in due passi: con un fit
     * diretto il punto grosso la trascinerebbe e nessuno si accorgerebbe).
     */
    @Test
    fun UnPuntoSbagliatoSaltaAllOcchio() {
        val conOutlier = puntiGiusti + punto(id = 99, distanceM = 50f, focalPx = 1400f)

        val scatter = requireNotNull(calibrationScatter(conOutlier))

        assertEquals("un solo indiziato", 1, scatter.outliers.size)
        assertEquals(99L, scatter.outliers.first().id)
        assertTrue(
            "lo scarto massimo deve raccontare il punto storto",
            scatter.maxDeviation > scatter.tolerance
        )
        assertTrue("la retta non deve essere trascinata dall'outlier",
            kotlin.math.abs(scatter.expectedFocalAt(25f) - 700f) <= 700f * 0.05f
        )
    }

    /**
     * L'indiziato non deve dire solo "sei sbagliato": deve portare con se'
     * distanza, dimensione del bersaglio e asse, che sono quello che il
     * pulsante "Rifai ..." ricompila nella schermata di calibrazione.
     */
    @Test
    fun LIndiziatoSaComeSiRifaLaMisura() {
        val conOutlier = puntiGiusti + punto(
            id = 99,
            distanceM = 50f,
            focalPx = 1400f,
            knownSizeM = 0.5f
        ).copy(axis = "V")

        val scatter = requireNotNull(calibrationScatter(conOutlier))
        val indiziato = scatter.outliers.single()

        assertEquals(50f, indiziato.distanceM, 0f)
        assertEquals(0.5f, indiziato.knownSizeM, 0f)
        assertEquals("V", indiziato.axis)
    }

    /**
     * Con due punti la retta passa per tutti e due: non esiste uno scostamento,
     * quindi non si puo' accusare nessuno. Segnalarne uno sarebbe un giudizio
     * senza dati.
     */
    @Test
    fun ConDuePuntiNessunoEResponsabile() {
        val scatter = requireNotNull(
            calibrationScatter(
                listOf(
                    punto(id = 1, distanceM = 10f, focalPx = 700f),
                    punto(id = 2, distanceM = 40f, focalPx = 900f)
                )
            )
        )

        assertEquals(emptyList<ScatterPoint>(), scatter.outliers)
    }

    @Test
    fun UnSoloPuntoNonEUnGraficoMaNonMente() {
        val scatter = requireNotNull(calibrationScatter(listOf(punto(id = 1, distanceM = 20f, focalPx = 650f))))

        assertEquals(emptyList<ScatterPoint>(), scatter.outliers)
        assertEquals("retta piatta sul solo punto", 650f, scatter.expectedFocalAt(10f), 650f * 0.01f)
    }

    /** Niente punti (o tutti inutilizzabili): niente grafico, invece di fingere. */
    @Test
    fun SenzaPuntiNonCENessunGrafico() {
        assertNull(calibrationScatter(emptyList()))
        assertNull(
            calibrationScatter(
                listOf(punto(id = 1, distanceM = 20f, focalPx = 650f, knownSizeM = 0f))
            )
        )
    }

    /**
     * Un punto fatto con lo zoom 2x non deve sembrare strano: e' la stessa
     * focale di un punto a 1x, e il calcolo lo sa (e' il motivo per cui il
     * grafico non e' in pixel grezzi).
     */
    @Test
    fun LoZoomNonFaDiventareStranoUnPuntoGiusto() {
        val misto = puntiGiusti + punto(id = 5, distanceM = 25f, focalPx = 700f, zoom = 2f)

        val scatter = requireNotNull(calibrationScatter(misto))

        assertEquals(emptyList<ScatterPoint>(), scatter.outliers)
    }

    /**
     * Distanze identiche (varianza zero): la pendenza non e' calcolabile, e la
     * retta deve cadere sulla media invece di diventare infinita.
     */
    @Test
    fun DistanzeUgualiNonInventanoLaPendenza() {
        val stessaDistanza = listOf(
            punto(id = 1, distanceM = 30f, focalPx = 700f),
            punto(id = 2, distanceM = 30f, focalPx = 705f)
        )

        val scatter = requireNotNull(calibrationScatter(stessaDistanza))

        assertEquals(0f, scatter.slopePxPerMeter, 0.0001f)
        assertTrue(
            "la retta deve stare in mezzo ai due punti",
            kotlin.math.abs(scatter.expectedFocalAt(30f) - 702.5f) < 3f
        )
    }
}
