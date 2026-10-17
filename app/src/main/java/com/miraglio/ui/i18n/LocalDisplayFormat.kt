package com.miraglio.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.miraglio.domain.format.DisplayFormat
import com.miraglio.domain.format.UnitSystem
import java.util.Locale

/**
 * [DisplayFormat] in vigore — lingua e sistema di unita' — per le schermate
 * Compose.
 *
 * Compose non passa da Hilt: la lingua gliela dice la `Configuration` corrente,
 * che cambia da sola (e fa ricomporre) quando l'utente sceglie un'altra lingua
 * per l'app; il sistema di unita' glielo passa chi fornisce il valore, che lo
 * legge dalle preferenze (vedi [ProvideDisplayFormat]). Le schermate lo leggono
 * con `val format = LocalDisplayFormat.current` e formattano con quello.
 *
 * Il valore di ripiego serve solo a chi non passa dal provider (anteprime,
 * test): e' la lingua del telefono e le unita' di partenza, non quelle scelte.
 */
val LocalDisplayFormat = compositionLocalOf { DisplayFormat(Locale.getDefault()) }

/**
 * Il formattatore in vigore, ricordato finche' lingua e unita' non cambiano.
 *
 * [unitSystem] entra nella `remember`: senza, cambiare sistema di unita'
 * riuserebbe il formattatore costruito prima e le distanze resterebbero in
 * metri fino alla prossima ricreazione dell'activity.
 */
@Composable
fun rememberDisplayFormat(unitSystem: UnitSystem): DisplayFormat {
    val configuration = LocalConfiguration.current
    return remember(configuration, unitSystem) {
        DisplayFormat(configuration.locales[0] ?: Locale.getDefault(), unitSystem)
    }
}

/** Rende [LocalDisplayFormat] disponibile a tutto il contenuto. */
@Composable
fun ProvideDisplayFormat(
    unitSystem: UnitSystem,
    content: @Composable () -> Unit
) {
    val format = rememberDisplayFormat(unitSystem)
    CompositionLocalProvider(LocalDisplayFormat provides format, content = content)
}
