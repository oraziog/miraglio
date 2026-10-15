package com.miraglio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = GreenDark,
    onPrimary = Cream,
    primaryContainer = GreenLight,
    secondary = OrangeDark,
    // Espresso su OrangeDark: contrasto ~4,85 (WCAG AA); Cream era ~3,6.
    onSecondary = Espresso,
    secondaryContainer = Orange80,
    background = BackgroundLight,
    surface = Cream,
    onBackground = BrownDark,
    onSurface = BrownDark
)

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = BrownDark,
    primaryContainer = GreenDark,
    secondary = Orange80,
    onSecondary = BrownDark,
    secondaryContainer = OrangeDark
)

/**
 * Modalita' outdoor: nero puro per fondo e superfici, giallo fluorescente per
 * testo, icone e accenti. Il nero riduce la luce emessa dal pannello (sotto il
 * sole e' la luminanza dello schermo a rendere il testo illeggibile) e il
 * giallo su nero puro e' la coppia a rapporto di contrasto massimo (19,56:1,
 * calcolato secondo le formule WCAG).
 *
 * I ruoli "container" (card e bottoni tonali) restano GIALLI con testo nero,
 * non neri: su un fondo nero un bottone nero sparirebbe e resterebbe solo la
 * sua scritta. Il nero e' riservato a fondo, superfici e ai pannelli sopra
 * l'immagine, dove serve far vedere l'immagine e non abbagliare.
 *
 * Nota: il comportamento con occhiali polarizzati non e' misurato, e' una
 * ragione di progetto (contrasto alto e luminanza emessa bassa) da validare
 * sul campo.
 */
private val OutdoorColorScheme = darkColorScheme(
    primary = OutdoorYellow,
    onPrimary = OutdoorBlack,
    // Container pieni (giallo con testo nero) e NON neri: su un fondo nero un
    // bottone o una card nera sparirebbe nel fondo, e resterebbe solo il testo.
    primaryContainer = OutdoorYellow,
    onPrimaryContainer = OutdoorBlack,
    secondary = OutdoorYellow,
    onSecondary = OutdoorBlack,
    secondaryContainer = OutdoorYellow,
    onSecondaryContainer = OutdoorBlack,
    tertiary = OutdoorYellow,
    onTertiary = OutdoorBlack,
    tertiaryContainer = OutdoorYellow,
    onTertiaryContainer = OutdoorBlack,
    background = OutdoorBlack,
    onBackground = OutdoorYellow,
    surface = OutdoorBlack,
    onSurface = OutdoorYellow,
    // Superfici "di servizio" (righe di dettaglio): nero pieno con testo
    // giallo, cosi' la card resta leggibile senza aggiungere un'altra tinta.
    surfaceVariant = OutdoorBlack,
    onSurfaceVariant = OutdoorYellow,
    outline = OutdoorYellow,
    error = OutdoorRed,
    onError = OutdoorBlack
)

/**
 * Vera quando e' attiva la modalita' outdoor.
 *
 * Serve ai componenti che non possono seguire il tema perche' disegnano su
 * pannelli propri o sopra l'immagine della fotocamera (colori fissi, non
 * presi dal colorScheme): quelli leggono questo valore e scelgono di
 * conseguenza.
 */
val LocalOutdoorMode = compositionLocalOf { false }

/**
 * Sfondo dei pannelli sovrapposti all'immagine: chiaro traslucido nell'uso
 * normale, nero pieno in outdoor (il bianco sotto il sole riflette).
 */
val PanelContainer: Color
    @Composable get() = if (LocalOutdoorMode.current) OutdoorBlack else Color(0xB3FFFFFF)

/**
 * Testo e cursori sui pannelli sovrapposti: nero nell'uso normale, giallo
 * fluorescente in outdoor.
 */
val OnPanel: Color
    @Composable get() = if (LocalOutdoorMode.current) OutdoorYellow else Color(0xFF000000)

/**
 * Fattore di ingrandimento dei testi in modalita' outdoor: sotto il sole serve
 * leggere a colpo d'occhio, senza avvicinare il telefono.
 */
private const val OUTDOOR_TEXT_SCALE = 1.3f

/** Tipografia di base, esplicita: e' l'unica che non cambia con il tema. */
private val BaseTypography = Typography()

/** Tutti gli stili Material ingranditi: nessuno resta a misura normale. */
private val OutdoorTypography: Typography = with(BaseTypography) {
    Typography(
        displayLarge = displayLarge.scaled(OUTDOOR_TEXT_SCALE),
        displayMedium = displayMedium.scaled(OUTDOOR_TEXT_SCALE),
        displaySmall = displaySmall.scaled(OUTDOOR_TEXT_SCALE),
        headlineLarge = headlineLarge.scaled(OUTDOOR_TEXT_SCALE),
        headlineMedium = headlineMedium.scaled(OUTDOOR_TEXT_SCALE),
        headlineSmall = headlineSmall.scaled(OUTDOOR_TEXT_SCALE),
        titleLarge = titleLarge.scaled(OUTDOOR_TEXT_SCALE),
        titleMedium = titleMedium.scaled(OUTDOOR_TEXT_SCALE),
        titleSmall = titleSmall.scaled(OUTDOOR_TEXT_SCALE),
        bodyLarge = bodyLarge.scaled(OUTDOOR_TEXT_SCALE),
        bodyMedium = bodyMedium.scaled(OUTDOOR_TEXT_SCALE),
        bodySmall = bodySmall.scaled(OUTDOOR_TEXT_SCALE),
        labelLarge = labelLarge.scaled(OUTDOOR_TEXT_SCALE),
        labelMedium = labelMedium.scaled(OUTDOOR_TEXT_SCALE),
        labelSmall = labelSmall.scaled(OUTDOOR_TEXT_SCALE)
    )
}

/** Ingrandisce un testo. `lineHeight` non specificato resta non specificato. */
private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor)
)

private fun TextUnit.scaled(factor: Float): TextUnit =
    if (isUnspecified) this else this * factor

@Composable
fun MiraglioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Modalita' outdoor: alto contrasto e testi ingranditi. */
    outdoor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        outdoor -> OutdoorColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val typography = if (outdoor) OutdoorTypography else BaseTypography

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Barre di stato scure quando il fondo e' scuro: tema scuro e
            // outdoor. Il fondo outdoor e' nero puro, quindi icone chiare.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                !(darkTheme || outdoor)
        }
    }

    CompositionLocalProvider(LocalOutdoorMode provides outdoor) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
