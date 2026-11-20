package com.miraglio.ui.audio

import com.miraglio.data.location.LocationTracker
import com.miraglio.data.weather.TemperatureReading
import com.miraglio.data.weather.TemperatureSource
import com.miraglio.data.weather.WeatherCachePolicy
import com.miraglio.data.weather.WeatherRepository

/**
 * Legge un numero digitato dall'utente.
 *
 * La virgola decimale è quella che si scrive su una tastiera italiana e
 * spagnola: rifiutare "27,5" perché il codice si aspetta un punto sarebbe un
 * errore dell'app, non dell'arciere.
 */
internal fun parseDecimal(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

/** Numero senza decimali superflui: 25 invece di 25.0. */
internal fun plainNumber(value: Float): String =
    if (value.isFinite() && value % 1f == 0f) value.toInt().toString() else value.toString()

/**
 * La temperatura da usare per la misura, con le tre sorgenti in ordine di
 * priorità: manuale, previsione del luogo, ripiego a 20 °C.
 *
 * Senza un fix GPS non si chiama la rete: la previsione sarebbe di un altro
 * posto, e un numero sbagliato presentato come "meteo" è peggio del ripiego
 * dichiarato.
 */
internal suspend fun resolveTemperature(
    weather: WeatherRepository,
    location: LocationTracker
): TemperatureReading {
    weather.manualCelsius()?.let { return TemperatureReading(it, TemperatureSource.MANUAL) }
    val fix = location.currentLocation.value
    return if (fix != null) {
        weather.temperature(fix.latitude, fix.longitude)
    } else {
        TemperatureReading(WeatherCachePolicy.FALLBACK_CELSIUS, TemperatureSource.FALLBACK)
    }
}
