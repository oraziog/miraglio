package com.miraglio.ui.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.miraglio.data.history.HistoryEntry
import com.miraglio.data.places.PlaceKind
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import com.miraglio.i18n.AppLanguageChoice
import com.miraglio.ui.capture.FrozenControlsHeight
import com.miraglio.ui.capture.LineOrientation
import com.miraglio.ui.capture.MeasureDirection
import com.miraglio.ui.capture.MeasureModePanel
import com.miraglio.ui.capture.MeasureResult
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.places.HistorySection
import com.miraglio.ui.settings.LanguageCard
import com.miraglio.ui.settings.MeasurementUnitsCard
import com.miraglio.ui.settings.RecognitionCard
import com.miraglio.ui.theme.MiraglioTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot dei **pannelli** delle schermate principali — misura, luoghi,
 * impostazioni — con i PNG attesi versionati in `app/src/test/screenshots/`.
 *
 * Cosa coprono e cosa no: qui si disegnano i pezzi che si vedono e che cambiano
 * davvero con il lavoro di layout, con i valori veri dell'app (unità e lingua
 * scelte, altezza riservata ai controlli del fermo immagine). La **schermata
 * intera** non si puo' disegnare sul JVM: Misura e Luoghi aprono la fotocamera
 * e il database dei luoghi, e i loro ViewModel sono costruiti da Hilt, che in
 * un test del genere non c'e'. Quindi: escluso cio' che sta sopra il pannello
 * (foto, anteprima, elenco completo dei luoghi), incluso tutto il resto. Se un
 * giorno una di queste schermate si rompe nel pezzo non coperto, il PNG non
 * diventa rosso: quel pezzo va provato sul telefono.
 *
 * I valori sono fissi apposta: nessuna data "di adesso" e nessun numero
 * casuale, altrimenti il confronto col PNG salterebbe da un giorno all'altro.
 * Le date passano dal formattatore dell'app, che e' gia' fissato su Europe/Rome
 * (vedi `DisplayFormat`), quindi il PNG non dipende dal fuso della macchina.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-rIT-w411dp-h891dp-mdpi")
class MainScreensScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Stesso margine degli altri screenshot: 0,1% di pixel diversi al massimo. */
    private val confronto = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f)
    )

    private fun disegna(file: String, content: @Composable () -> Unit) {
        val format = DisplayFormat(Locale.ITALY, UnitSystem.METRIC)
        composeRule.setContent {
            val formato = remember { format }
            CompositionLocalProvider(LocalDisplayFormat provides formato) {
                MiraglioTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(file, roborazziOptions = confronto)
    }

    /**
     * Il pannello del fermo immagine della misura, con un risultato pronto: e'
     * il pezzo che si guarda per primo dopo lo scatto. L'altezza e' quella vera
     * riservata ai controlli ([FrozenControlsHeight]), non una inventata qui.
     */
    private fun pannelloMisura(direction: MeasureDirection, file: String) {
        disegna(file) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MeasureModePanel(
                    direction = direction,
                    orientation = LineOrientation.HORIZONTAL,
                    heightInput = "130",
                    widthInput = "120",
                    distanceInput = "30",
                    measuredSize = 1.42f,
                    distResult = MeasureResult(distanceMeters = 30.4f, pitchAngleDegrees = 4.2f),
                    pitchDegrees = 4.2f,
                    shotMoved = false,
                    hasLines = true,
                    onHeightTextChanged = {},
                    onWidthTextChanged = {},
                    onDistanceChanged = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FrozenControlsHeight)
                )
            }
        }
    }

    /** Misura LINEE, fermo immagine, direzione "distanza": il caso piu' usato. */
    @Test
    fun misuraDistanzaInFermoImmagine() {
        pannelloMisura(MeasureDirection.DISTANZA, "measure_panel_distance.png")
    }

    /**
     * Stessa schermata con "altezza": il pannello deve stare nella **stessa**
     * altezza del caso precedente. Era il motivo per cui l'altezza dei controlli
     * e' fissa: cambiando modalita' l'immagine sopra si ridimensionava e sembrava
     * aver zoomato.
     */
    @Test
    fun misuraAltezzaInFermoImmagine() {
        pannelloMisura(MeasureDirection.ALTEZZA, "measure_panel_height.png")
    }

    /**
     * Luoghi: la sezione della cronologia con una misura GPS e uno scatto. Le
     * date sono fisse (14/11/2023), quindi il PNG resta lo stesso ovunque giri.
     */
    @Test
    fun luoghiCronologia() {
        val quando = 1_700_000_000_000L
        disegna("places_history.png") {
            HistorySection(
                entries = listOf(
                    HistoryEntry(
                        id = "1",
                        atMs = quando,
                        kind = PlaceKind.GPS,
                        distanceM = 42.7f,
                        errorM = 1.4f,
                        latitudeDeg = 45.07f,
                        longitudeDeg = 7.68f
                    ),
                    HistoryEntry(
                        id = "2",
                        atMs = quando - 3_600_000L,
                        kind = PlaceKind.CAMERA,
                        distanceM = 30.4f
                    )
                ),
                onClear = {}
            )
        }
    }

    /** Impostazioni: le tre card delle scelte, lingua e unita' comprese. */
    @Test
    fun impostazioniCard() {
        disegna("settings_cards.png") {
            RecognitionCard(enabled = true, onToggle = {})
            MeasurementUnitsCard(selected = UnitSystem.METRIC, onSelect = {})
            LanguageCard(selected = AppLanguageChoice.ITALIAN, onSelect = {})
        }
    }
}
