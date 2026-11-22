package com.miraglio.ui.audio

import com.miraglio.data.profile.CalibrationShot
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.ballistics.SpeedOfSound
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La curva del grafico di calibrazione acustica, provata senza telefono.
 *
 * L'app misura con `v(d) = v0 · e^(-k·d)`: i tiri di questi test sono costruiti
 * **da quella fisica** (tempo di volo + ritorno del suono), quindi un tiro
 * "giusto" è giusto per definizione e uno sbagliato è sbagliato di una quantità
 * nota — se il grafico li confonde, il difetto è nel grafico.
 */
class AudioCalibrationCurveTest {

    private val modello = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.015f)

    /**
     * Un tiro a [distanceM] con un errore di [errorS] sul tempo totale
     * (positivo: impatto sentito più tardi).
     */
    private fun tiro(
        id: Long,
        distanceM: Float,
        errorS: Float = 0f,
        airTempC: Float = 20f,
        discarded: Boolean = false
    ): CalibrationShot = CalibrationShot(
        id = id,
        distanceM = distanceM,
        totalTimeS = BallisticCalculator.flightTime(distanceM, modello, airTempC) + errorS,
        airTempC = airTempC,
        pitchDeg = 0f,
        discarded = discarded,
        timestampMs = id
    )

    /** Quattro tiri buoni e uno sbagliato di 100 ms: il grafico deve accusare lui. */
    @Test
    fun ilTiroSbagliatoRestaFuoriEIlModelloNonSiMuove() {
        val shots = listOf(
            tiro(1, 18f),
            tiro(2, 30f),
            // 100 ms a 30 m sono circa 3,7 m: oltre la soglia di 3 m.
            tiro(3, 30f, errorS = 0.10f),
            tiro(4, 40f),
            tiro(5, 50f)
        )

        val curve = requireNotNull(audioCalibrationCurve(shots))

        assertEquals(5, curve.points.size)
        val sbagliato = curve.outliers.single()
        assertEquals(3L, sbagliato.id)
        assertTrue(abs(sbagliato.deviationM) > 3f)
        // Il modello robusto non si fa trascinare: la curva vera, non quella
        // che passa a metà fra i tiri buoni e quello sbagliato.
        assertEquals(65f, curve.model.muzzleSpeedMs, 1.5f)
        assertEquals(0.015f, curve.model.decayPerM, 0.002f)
    }

    /** I tiri buoni non sono indiziati, e lo scarto massimo è piccolo. */
    @Test
    fun iTiriBuoniStannoSullaCurva() {
        val shots = listOf(tiro(1, 18f), tiro(2, 25f), tiro(3, 40f), tiro(4, 55f))

        val curve = requireNotNull(audioCalibrationCurve(shots))

        assertTrue(curve.outliers.isEmpty())
        assertTrue("scarto ${curve.maxDeviationM}", curve.maxDeviationM < 0.2f)
    }

    /**
     * Un tiro scartato resta sul grafico (in grigio) ma non entra nella curva:
     * altrimenti scartarlo non cambierebbe niente, che è il contrario di
     * quello che l'utente ha appena chiesto.
     */
    @Test
    fun ilTiroScartatoRestaVisibileMaFuoriDalModello() {
        val shots = listOf(
            tiro(1, 20f),
            tiro(2, 35f),
            tiro(3, 50f, errorS = 0.25f, discarded = true)
        )

        val curve = requireNotNull(audioCalibrationCurve(shots))

        val scartato = curve.points.single { it.id == 3L }
        assertTrue(scartato.discarded)
        assertFalse(scartato.outlier)
        assertEquals(65f, curve.model.muzzleSpeedMs, 1f)
        // Lo scarto massimo guarda solo i tiri che contano: quello scartato non
        // deve sporcare il numero che si legge sotto il grafico.
        assertTrue(curve.maxDeviationM < 0.2f)
    }

    /** Con un tiro solo non c'è nessuna curva da disegnare. */
    @Test
    fun servonoAlmenoDueTiri() {
        assertNull(audioCalibrationCurve(listOf(tiro(1, 30f))))
        assertNull(audioCalibrationCurve(emptyList()))
    }

    /** Tutti alla stessa distanza: nessuna pendenza, nessuna curva. */
    @Test
    fun tiriTuttiAllaStessaDistanzaNonFannoUnaCurva() {
        assertNull(
            audioCalibrationCurve(listOf(tiro(1, 30f), tiro(2, 30f), tiro(3, 30f)))
        )
    }

    /** Impatto più corto del solo suono: il tiro non esiste, non si disegna. */
    @Test
    fun unTempoImpossibileNonEntraNelGrafico() {
        val impossibile = CalibrationShot(
            id = 2,
            distanceM = 20f,
            // Il rumore non può tornare indietro prima di 20 / v_s.
            totalTimeS = 20f / SpeedOfSound.of(20f),
            airTempC = 20f,
            pitchDeg = 0f
        )

        assertNull(audioCalibrationCurve(listOf(tiro(1, 20f), impossibile)))
    }

    /** I tiri senza impatto non hanno un tempo, quindi non hanno una velocità. */
    @Test
    fun iTiriSenzaImpattoNonEntranoNelGrafico() {
        val senzaImpatto = CalibrationShot(
            id = 2,
            distanceM = 30f,
            totalTimeS = null,
            airTempC = 20f,
            pitchDeg = 0f
        )

        assertNull(audioCalibrationCurve(listOf(tiro(1, 20f), senzaImpatto)))
    }

    /** La velocità implicita è quella che la fisica mette nel tempo di volo. */
    @Test
    fun laVelocitaImplicitaTornaQuellaDelModello() {
        val tempo = BallisticCalculator.flightTime(40f, modello, 20f)
        val implicita = requireNotNull(impliedSpeedMs(40f, tempo, 20f))

        assertEquals(modello.speedAt(40f), implicita, 0.01f)
    }
}
