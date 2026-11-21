package com.miraglio.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.miraglio.domain.audio.ImpactEvidence
import com.miraglio.domain.audio.ShotReport
import com.miraglio.domain.audio.ShotReportBuilder
import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.theme.MiraglioTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La scheda Misura **vera** disegnata sul JVM, con i tre stati del giudizio e
 * nei tre temi dell'app.
 *
 * Perché serve: i test di logica dicono che `ShotQuality` restituisce HIGH,
 * MEDIUM o LOW, ma non che quei tre stati arrivino a schermo, che la distanza
 * sia davvero colorata, che il motivo stia sotto e che il testo non esca dalla
 * card. Qui si disegna `MeasurementCard` — la stessa del telefono — con un
 * report costruito dal dominio, e si guarda il PNG.
 *
 * Copre anche quello che i numeri non vedono: il verde scuro sul tema scuro
 * sarebbe illeggibile, e il testo ingrandito dell'outdoor potrebbe spingere
 * l'interruttore o il motivo fuori dalla card. I PNG attesi stanno in
 * `app/src/test/screenshots/` e sono versionati.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-rIT-w411dp-h1200dp-mdpi")
class AudioRangefinderScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Stesso margine degli altri screenshot: 0,1% di pixel diversi al massimo. */
    private val confronto = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f)
    )

    private val model = ArrowSpeedModel(muzzleSpeedMs = 65f, decayPerM = 0.002f)

    /**
     * Un report con la distanza voluta: il tempo è quello che la fisica
     * dell'arco dà a quella distanza, non un numero scelto a mano.
     */
    private fun report(
        distanceM: Float,
        evidence: ImpactEvidence,
        fieldRangeM: ClosedFloatingPointRange<Float>?,
        clicker: Boolean
    ): ShotReport = requireNotNull(
        ShotReportBuilder.build(
            totalTimeS = BallisticCalculator.flightTime(distanceM, model, 20f),
            model = model,
            airTempC = 20f,
            pitchDeg = 0f,
            evidence = evidence,
            fieldRangeM = fieldRangeM,
            clickerDetected = clicker
        )
    )

    private fun disegna(
        file: String,
        report: ShotReport?,
        fieldMode: Boolean = true,
        dark: Boolean = false,
        outdoor: Boolean = false,
        stageText: String = "Misurato"
    ) {
        val format = DisplayFormat(Locale.ITALY, UnitSystem.METRIC)
        composeRule.setContent {
            val formato = remember { format }
            CompositionLocalProvider(LocalDisplayFormat provides formato) {
                MiraglioTheme(darkTheme = dark, outdoor = outdoor) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            MeasurementCard(
                                stageText = stageText,
                                listening = false,
                                captureFailed = false,
                                report = report,
                                hasProfile = true,
                                canMeasure = true,
                                hasMicrophone = true,
                                fieldMode = fieldMode,
                                waveform = emptyList(),
                                zoom = 1f,
                                onStart = {},
                                onStop = {},
                                onZoom = {},
                                onFieldMode = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(file, roborazziOptions = confronto)
    }

    /**
     * Il testo esiste, ha area e non esce dal fondo: un nodo a cui il layout ha
     * dato altezza zero sarebbe "presente" nei test e invisibile all'utente.
     */
    private fun assertVisibile(testo: String) {
        val nodo = composeRule.onNodeWithText(testo, substring = true)
            .fetchSemanticsNode()
        val area = nodo.boundsInRoot
        val radice = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "\"$testo\" non ha area visibile: ${area.width} x ${area.height}",
            area.width > 0f && area.height > 0f
        )
        assertTrue(
            "\"$testo\" finisce a ${area.bottom}, sotto il fondo della scheda a ${radice.bottom}",
            area.bottom <= radice.bottom + 1f
        )
    }

    /** Verde: banda larga, distanza nel campo, clicker riconosciuto. */
    @Test
    fun schedaVerdeChiaro() {
        disegna(
            "audio_rangefinder_green.png",
            report(30f, ImpactEvidence.PEAK_IN_BAND, 5f..50f, clicker = true)
        )
        composeRule.onNode(isToggleable()).assertIsOn()
        assertVisibile("Campo H&S 5–50 m")
        assertVisibile("Verde: impatto a banda larga dentro la finestra fisica.")
        assertVisibile("riconosciuto in ascolto")
    }

    /** Giallo: impatto grave senza conferma acuta. */
    @Test
    fun schedaGiallaSenzaBandaLarga() {
        disegna(
            "audio_rangefinder_amber.png",
            report(30f, ImpactEvidence.LOW_IN_BAND, 5f..50f, clicker = false)
        )
        composeRule.onNode(isToggleable()).assertIsOn()
        assertVisibile("Giallo: impatto grave senza conferma acuta")
        assertVisibile("nessun click sentito")
    }

    /** Giallo per estrapolazione: 55 m con il campo dichiarato 5–50. */
    @Test
    fun schedaGiallaFuoriCampo() {
        disegna(
            "audio_rangefinder_out_of_field.png",
            report(55f, ImpactEvidence.PEAK_IN_BAND, 5f..50f, clicker = true)
        )
        assertVisibile("Giallo: distanza fuori dal campo 5–50 m")
    }

    /** Rosso: nessun impatto nella finestra fisica, la misura è un ripiego. */
    @Test
    fun schedaRossaRipiego() {
        disegna(
            "audio_rangefinder_red.png",
            report(30f, ImpactEvidence.OUT_OF_BAND, 5f..50f, clicker = false)
        )
        assertVisibile("Rosso: nessun impatto nella finestra fisica")
    }

    /** Tema scuro: gli stessi tre colori devono restare leggibili. */
    @Test
    fun schedaVerdeTemaScuro() {
        disegna(
            "audio_rangefinder_green_dark.png",
            report(30f, ImpactEvidence.PEAK_IN_BAND, 5f..50f, clicker = true),
            dark = true
        )
        assertVisibile("Verde: impatto a banda larga dentro la finestra fisica.")
    }

    /** Outdoor: testi ingranditi del 30% e fondo nero, niente fuori bordo. */
    @Test
    fun schedaOutdoorTestiGrandi() {
        disegna(
            "audio_rangefinder_outdoor.png",
            report(30f, ImpactEvidence.PEAK_IN_BAND, 5f..50f, clicker = true),
            outdoor = true
        )
        assertVisibile("Campo H&S 5–50 m")
        assertVisibile("Verde: impatto a banda larga dentro la finestra fisica.")
    }

    /** Interruttore spento: nessun colore inventato, il motivo non c'è. */
    @Test
    fun schedaCampoSpento() {
        disegna(
            "audio_rangefinder_field_off.png",
            report(30f, ImpactEvidence.PEAK_IN_BAND, null, clicker = false),
            fieldMode = false
        )
        composeRule.onNode(isToggleable()).assertIsOff()
        assertVisibile("Distanza (in linea d'aria)")
    }

    /** Nessun impatto: la scheda resta leggibile e non mostra una distanza. */
    @Test
    fun schedaSenzaImpatto() {
        disegna(
            "audio_rangefinder_no_impact.png",
            report = null,
            stageText = "Impatto non sentito"
        )
        assertVisibile("Impatto non sentito")
        composeRule.onNodeWithText("Distanza (in linea d'aria)").assertDoesNotExist()
    }
}
