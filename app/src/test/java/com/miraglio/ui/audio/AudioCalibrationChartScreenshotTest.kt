package com.miraglio.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.miraglio.data.profile.CalibrationShot
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.theme.MiraglioTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il grafico dei tiri di calibrazione **disegnato**, come sul telefono.
 *
 * Perché un test d'immagine: le tacche, le etichette e i pallini possono essere
 * tutti corretti e comunque sovrapporsi (un numero sopra un punto è un numero
 * giusto che nessuno riesce a leggere). `AudioCalibrationCurveTest` verifica i
 * conti; questo verifica che il risultato si veda — curva, tiro fuori curva con
 * il cerchio rosso, e i pulsanti "Misura di nuovo".
 *
 * Il PNG atteso sta in `app/src/test/screenshots/` e `verifyRoborazziDebug`
 * fallisce se il disegno cambia.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-rIT-w411dp-h891dp-mdpi")
class AudioCalibrationChartScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Stesso margine degli altri screenshot: 0,1% di pixel diversi al massimo. */
    private val confronto = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f)
    )

    private val modello = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.015f)

    /** Un tiro con il tempo che la fisica dà a quella distanza, più [errorS]. */
    private fun tiro(id: Long, distanceM: Float, errorS: Float = 0f): CalibrationShot =
        CalibrationShot(
            id = id,
            distanceM = distanceM,
            totalTimeS = BallisticCalculator.flightTime(distanceM, modello, 20f) + errorS,
            airTempC = 20f,
            pitchDeg = 0f,
            timestampMs = id
        )

    /** Quattro tiri buoni e uno a 40 m sbagliato di 120 ms (~3,9 m). */
    private val esempio = listOf(
        tiro(1, 18f),
        tiro(2, 25f),
        tiro(3, 30f),
        tiro(4, 40f, errorS = 0.12f),
        tiro(5, 50f)
    )

    private fun disegna(unitSystem: UnitSystem, file: String) {
        val format = DisplayFormat(Locale.ITALY, unitSystem)
        val curve = requireNotNull(audioCalibrationCurve(esempio))

        composeRule.setContent {
            val formato = remember { format }
            CompositionLocalProvider(LocalDisplayFormat provides formato) {
                MiraglioTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            AudioCalibrationChart(curve = curve, onRedoDistance = {})
                        }
                    }
                }
            }
        }

        composeRule.onRoot().captureRoboImage(file, roborazziOptions = confronto)
    }

    /**
     * Il caso tipico: metri, curva che scende, il tiro a 40 m rosso e un solo
     * pulsante "Misura di nuovo 40 m".
     */
    @Test
    fun graficoInMetri() {
        disegna(UnitSystem.METRIC, "audio_calibration_chart.png")
    }

    /**
     * Stesso grafico in iarde: cambia l'asse delle distanze e la sua unità, non
     * la velocità (m/s restano m/s).
     */
    @Test
    fun graficoInIarde() {
        disegna(UnitSystem.IMPERIAL, "audio_calibration_chart_imperial.png")
    }
}
