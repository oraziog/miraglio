package com.miraglio.ui.calibration

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
import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.theme.MiraglioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Screenshot del grafico della calibrazione: il PNG atteso sta in
 * `app/src/test/screenshots/` ed e' versionato.
 *
 * Perche' un test d'immagine e non un controllo sui numeri: le tacche, le
 * etichette e i punti possono essere tutti corretti e comunque sovrapporsi
 * (un'etichetta che finisce sopra un pallino e' un numero giusto che nessuno
 * riesce a leggere). I test puri (`CalibrationScatterTest`, `AxisTicksTest`)
 * verificano il calcolo; questo verifica che il risultato si veda.
 *
 * Gira sul JVM con Robolectric in modalita' grafica nativa: nessun telefono e
 * nessun emulatore, quindi puo' stare in CI. `verifyRoborazziDebug` rifa' il
 * disegno e fallisce se il PNG non e' identico a quello salvato.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-rIT-w411dp-h891dp-mdpi")
class CalibrationChartScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Come si confrontano i PNG. Non e' un confronto a zero differenze: lo
     * stesso disegno rasterizzato da macchine diverse puo' cambiare di qualche
     * pixel sui bordi dei caratteri (antialiasing), e un rosso per quello
     * insegnerebbe a ignorare i rossi. Il margine e' pero' piccolo in modo
     * utile: l'immagine e' 411×306, quindi lo 0,1% sono ~125 pixel, meno di
     * **una** etichetta dell'asse (un numero 400/1400 ne occupa ~400).
     */
    private val confronto = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f)
    )

    /**
     * Un punto che implica [focalPx] di focale a [distanceM] metri, costruito
     * a ritroso dal modello pinhole (come in `CalibrationScatterTest`).
     */
    private fun punto(
        id: Long,
        distanceM: Float,
        focalPx: Float,
        knownSizeM: Float = 1.22f
    ): FocalPoint = FocalPoint(
        id = id,
        distanceM = distanceM,
        pixels = focalPx * knownSizeM / distanceM,
        knownSizeM = knownSizeM,
        axis = "H",
        timestamp = 0L,
        zoom = 1f,
        frameLongSide = 1280
    )

    /** Quattro misure sulla stessa focale e una sbagliata che ne implica il doppio. */
    private val esempio = listOf(
        punto(id = 1, distanceM = 5f, focalPx = 700f),
        punto(id = 2, distanceM = 10f, focalPx = 705f),
        punto(id = 3, distanceM = 15f, focalPx = 695f),
        punto(id = 4, distanceM = 20f, focalPx = 700f),
        punto(id = 5, distanceM = 50f, focalPx = 1400f)
    )

    private fun disegnaGrafico(unitSystem: UnitSystem, file: String) {
        val format = DisplayFormat(Locale.ITALY, unitSystem)
        val scatter = requireNotNull(calibrationScatter(esempio))

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
                            CalibrationScatterChart(scatter = scatter, onRedoDistance = {})
                        }
                    }
                }
            }
        }

        composeRule.onRoot().captureRoboImage(file, roborazziOptions = confronto)
    }

    /**
     * Il caso tipico: metri, quattro punti buoni e un indiziato. Nel PNG si
     * devono leggere i numeri della focale a sinistra (1000, 1200, 1400), la
     * retta a 700 px, il punto rosso a 1400 e i pulsanti "Rifai ...".
     */
    @Test
    fun graficoInMetri() {
        disegnaGrafico(UnitSystem.METRIC, "calibration_chart.png")
    }

    /**
     * Stesso grafico in iarde: cambia l'asse delle distanze e la sua unita',
     * non la focale (px restano px). Serve a fermare una regressione in cui
     * l'asse verticale venisse convertito per errore insieme a quello
     * orizzontale.
     */
    @Test
    fun graficoInIarde() {
        disegnaGrafico(UnitSystem.IMPERIAL, "calibration_chart_imperial.png")
    }
}
