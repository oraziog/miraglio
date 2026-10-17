package com.miraglio.ui.focal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import com.miraglio.ui.capture.LineOrientation
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.layout.AimLayout
import com.miraglio.ui.theme.MiraglioTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot del pannello della calibrazione quando lo spazio e' quello che
 * resta **con la tastiera aperta**. I PNG attesi stanno in
 * `app/src/test/screenshots/` e sono versionati.
 *
 * Perche' un'immagine e non un test sui numeri: il difetto che questi test
 * fermano non era un conto sbagliato, era **invisibilita'**. Con l'altezza
 * fissa a 200 dp e il padding della tastiera applicato dentro quella altezza,
 * ai campi restava zero spazio: il pannello c'era, i campi no, e nessun test
 * di logica poteva accorgersene. Qui si disegna il pannello **vero**
 * ([FocalInputPanel]) dentro la disposizione **vera** ([AimLayout]) su un
 * telefono alto quanto basta per la tastiera, e si guarda se i campi si vedono.
 *
 * Come si finge la tastiera: sul telefono il padding lo mette `imePadding`,
 * leggendo l'altezza dell'IME dal sistema. Qui quell'altezza non esiste
 * (Robolectric non ha una tastiera), quindi si applica a mano lo stesso
 * spazio — `windowInsetsPadding(WindowInsets(bottom = X))`, che e' esattamente
 * cio' che `imePadding` fa con X misurato — e si disegna una **striscia nera**
 * di X dp in fondo: quello che nel PNG resta sotto la striscia non lo
 * vedrebbe l'utente.
 *
 * Quello che questo test NON copre: la foto (nell'area immagine c'e' un
 * rettangolo grigio al posto della fotocamera) e il tocco reale sulla tastiera
 * di sistema, che resta da provare su un telefono.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalibrationPanelScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Stesso margine degli altri screenshot: 0,1% di pixel diversi al massimo. */
    private val confronto = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f)
    )

    /** Un fermo immagine credibile: paglione a 30 m, con due punti gia' salvati. */
    private val stato = FocalCaptureUiState(
        frozen = true,
        frameWidth = 1280,
        frameHeight = 960,
        orientation = LineOrientation.HORIZONTAL,
        lineA = 0.35f,
        lineB = 0.62f,
        sizeInput = "130",
        distanceInput = "30",
        savedCount = 2
    )

    /**
     * Disegna la schermata a [landscape] con [ime] di tastiera aperta.
     *
     * La striscia nera e' alta esattamente quanto l'inset: cosi' il PNG dice a
     * colpo d'occhio se il pannello e i suoi campi stanno **sopra** la tastiera.
     */
    private fun disegna(
        file: String,
        landscape: Boolean,
        ime: Dp
    ) {
        val format = DisplayFormat(Locale.ITALY, UnitSystem.METRIC)
        composeRule.setContent {
            val formato = remember { format }
            CompositionLocalProvider(LocalDisplayFormat provides formato) {
                MiraglioTheme(darkTheme = false) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF424242))) {
                        AimLayout(
                            landscape = landscape,
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets(0.dp, 0.dp, 0.dp, ime)),
                            image = { imageModifier ->
                            // Al posto della fotocamera: qui interessa lo spazio
                            // che l'immagine lascia al pannello, non la foto.
                            Box(modifier = imageModifier.background(Color(0xFF546E7A)))
                        },
                            panels = { panelsModifier ->
                                FocalInputPanel(
                                    state = stato,
                                    impliedFocal = 1400f,
                                    savedTotal = 5,
                                    zoomRatio = 2f,
                                    lensLabel = null,
                                    onSizeChanged = {},
                                    onDistanceChanged = {},
                                    onCapture = {},
                                    onRetake = {},
                                    onSave = {},
                                    onZoomReset = {},
                                    onCycleLens = {},
                                    onToggleOrientation = {},
                                    onMarkTarget = {},
                                    onGpsDistance = {},
                                    verificationExpectedPx = null,
                                    modifier = panelsModifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 8.dp
                                    )
                                )
                            }
                        )
                        // Dove sta la tastiera aperta.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(ime)
                                .background(Color(0xFF111111))
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage(file, roborazziOptions = confronto)
    }

    /**
     * I due campi devono stare **sopra** la tastiera, non solo esistere.
     *
     * Il PNG da solo direbbe "diverso", non "invisibile": chi rigenera le
     * immagini di riferimento vedrebbe un pannello qualunque e lo approverebbe.
     * Qui si controlla la geometria vera: il bordo inferiore di ciascun campo
     * deve restare sopra la striscia della tastiera, ed entrambi devono avere
     * un'area visibile (un campo spinto fuori dal padre ha altezza zero).
     */
    private fun assertCampiSopraLaTastiera(ime: Dp) {
        val radice = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val tastiera = with(composeRule.density) { ime.toPx() }
        val limite = radice.bottom - tastiera
        listOf("Distanza (m)", "Altezza (cm)").forEach { etichetta ->
            val area = composeRule.onNodeWithText(etichetta, substring = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$etichetta non ha area visibile: ${area.width} x ${area.height}",
                area.width > 0f && area.height > 0f
            )
            assertTrue(
                "$etichetta finisce a ${area.bottom} px, sotto la tastiera che " +
                    "comincia a $limite px",
                area.bottom <= limite + 1f
            )
        }
    }

    /**
     * Telefono in verticale con 300 dp di tastiera: il caso del difetto
     * originale. Nel PNG si devono leggere i due campi — "Distanza (m)" con 30
     * e "Altezza (cm)" con 130 — sopra la striscia nera.
     */
    @Test
    @Config(qualifiers = "it-rIT-w411dp-h891dp-mdpi")
    fun pannelloInVerticaleConTastiera() {
        disegna("calibration_panel_ime_portrait.png", landscape = false, ime = 300.dp)
        assertCampiSopraLaTastiera(ime = 300.dp)
    }

    /**
     * Telefono in orizzontale: qui l'altezza totale e' 411 dp, la tastiera ne
     * prende 250 e il pannello non ha piu' l'altezza fissa. Serve a fermare il
     * ritorno di un'altezza fissa che in orizzontale non ci sta: nel PNG il
     * pannello deve starci dentro, ridotto, senza finire sotto la striscia.
     */
    @Test
    @Config(qualifiers = "it-rIT-w891dp-h411dp-land-mdpi")
    fun pannelloInOrizzontaleConTastiera() {
        disegna("calibration_panel_ime_landscape.png", landscape = true, ime = 250.dp)
        assertCampiSopraLaTastiera(ime = 250.dp)
    }
}
