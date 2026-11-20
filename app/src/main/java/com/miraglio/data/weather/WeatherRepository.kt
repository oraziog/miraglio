package com.miraglio.data.weather

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Da dove arriva la temperatura con cui si calcola la velocità del suono. */
enum class TemperatureSource { MANUAL, FORECAST, FALLBACK }

/** Una temperatura pronta per la misura, con la sua provenienza. */
data class TemperatureReading(
    val celsius: Float,
    val source: TemperatureSource
)

/**
 * Temperatura dell'aria per il telemetro acustico.
 *
 * Ordine di priorità (dalla specifica): valore manuale, previsione oraria del
 * luogo in cui si sta tirando, e in ultima istanza 20 °C dichiarati come tali.
 *
 * La temperatura non è un dettaglio: la velocità del suono cambia dello 0,6%
 * per grado, e a 70 m il tempo del suono è un quinto del totale. Passare da
 * 5 °C a 30 °C sposta la distanza calcolata di circa un metro e mezzo, cioè
 * più dell'errore totale che il telemetro si propone.
 *
 * Il valore manuale vince sempre: chi sta tirando sa se il campo è in ombra,
 * se c'è vento o se ha misurato la temperatura con un termometro.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private companion object {
        const val TAG = "WeatherRepository"
        const val PREFS = "weather_cache"
        const val KEY_MANUAL = "manual_c"
        const val KEY_CACHED = "cached_c"
        const val KEY_CACHED_AT = "cached_at"
        const val KEY_CACHED_LAT = "cached_lat"
        const val KEY_CACHED_LON = "cached_lon"
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val CONNECT_TIMEOUT_MS = 4000
        const val READ_TIMEOUT_MS = 4000
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Temperatura inserita a mano, o null se non c'è (o è stata cancellata). */
    fun manualCelsius(): Float? {
        if (!prefs.contains(KEY_MANUAL)) return null
        val value = prefs.getFloat(KEY_MANUAL, Float.NaN)
        return value.takeIf { it.isFinite() }
    }

    /**
     * Fissa la temperatura manuale. `null` la cancella e riporta l'app alla
     * previsione: non è un valore "zero gradi", è l'assenza di un valore.
     */
    fun setManualCelsius(value: Float?) {
        prefs.edit().apply {
            if (value == null || !value.isFinite()) {
                remove(KEY_MANUAL)
            } else {
                putFloat(KEY_MANUAL, value.coerceIn(-40f, 60f))
            }
            apply()
        }
    }

    /**
     * La temperatura da usare adesso nel luogo [latitude], [longitude].
     *
     * Non lancia eccezioni: senza rete, senza GPS o con un servizio che non
     * risponde, si torna 20 °C marcati [TemperatureSource.FALLBACK], e
     * l'interfaccia lo dice. Una misura fatta con una temperatura dichiarata è
     * meglio di nessuna misura, purché l'arciere sappia che quel numero è un
     * ripiego.
     */
    suspend fun temperature(
        latitude: Double,
        longitude: Double,
        nowMs: Long = System.currentTimeMillis()
    ): TemperatureReading {
        manualCelsius()?.let { return TemperatureReading(it, TemperatureSource.MANUAL) }

        cachedFresh(nowMs, latitude, longitude)?.let {
            return TemperatureReading(it, TemperatureSource.FORECAST)
        }

        val fetched = fetch(latitude, longitude)
        if (fetched != null) {
            storeCache(fetched, latitude, longitude, nowMs)
            return TemperatureReading(fetched, TemperatureSource.FORECAST)
        }

        return TemperatureReading(WeatherCachePolicy.FALLBACK_CELSIUS, TemperatureSource.FALLBACK)
    }

    private fun cachedFresh(nowMs: Long, latitude: Double, longitude: Double): Float? {
        if (!prefs.contains(KEY_CACHED) || !prefs.contains(KEY_CACHED_AT)) return null
        val fresh = WeatherCachePolicy.isFresh(
            cachedAtMs = prefs.getLong(KEY_CACHED_AT, 0L),
            nowMs = nowMs,
            cachedLat = prefs.getFloat(KEY_CACHED_LAT, 0f).toDouble(),
            cachedLon = prefs.getFloat(KEY_CACHED_LON, 0f).toDouble(),
            latitude = latitude,
            longitude = longitude
        )
        if (!fresh) return null
        return prefs.getFloat(KEY_CACHED, Float.NaN).takeIf { it.isFinite() }
    }

    private fun storeCache(celsius: Float, latitude: Double, longitude: Double, nowMs: Long) {
        prefs.edit().apply {
            putFloat(KEY_CACHED, celsius)
            putLong(KEY_CACHED_AT, nowMs)
            putFloat(KEY_CACHED_LAT, latitude.toFloat())
            putFloat(KEY_CACHED_LON, longitude.toFloat())
            apply()
        }
    }

    private suspend fun fetch(latitude: Double, longitude: Double): Float? =
        withContext(Dispatchers.IO) {
            val url = URL(
                String.format(
                    Locale.US,
                    "%s?latitude=%.5f&longitude=%.5f&current=temperature_2m",
                    ENDPOINT,
                    latitude,
                    longitude
                )
            )
            runCatching {
                (url.openConnection() as HttpURLConnection).run {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    try {
                        if (responseCode !in 200..299) return@run null
                        inputStream.bufferedReader().use { parseTemperature(it.readText()) }
                    } finally {
                        disconnect()
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Lettura temperatura meteo fallita", error)
            }.getOrNull()
        }
}

/**
 * Regole della cache meteo, separate dal codice Android per poterle provare.
 *
 * Trenta minuti è il compromesso fra "la temperatura del campo non cambia di
 * minuto in minuto" e "non voglio una chiamata a ogni tiro". Il controllo sulle
 * coordinate evita il caso peggiore: trenta minuti sono anche il tempo di
 * spostarsi di venti chilometri, e usare la temperatura della valle dove si è
 * fatto colazione per una misura in quota sarebbe un errore silenzioso.
 */
internal object WeatherCachePolicy {

    const val CACHE_MINUTES = 30L
    const val MAX_DISTANCE_DEG = 0.05
    const val FALLBACK_CELSIUS = 20f

    private const val MINUTES_TO_MS = 60_000L

    fun isFresh(
        cachedAtMs: Long,
        nowMs: Long,
        cachedLat: Double,
        cachedLon: Double,
        latitude: Double,
        longitude: Double
    ): Boolean {
        if (cachedAtMs <= 0L || nowMs < cachedAtMs) return false
        if (nowMs - cachedAtMs > CACHE_MINUTES * MINUTES_TO_MS) return false
        return abs(cachedLat - latitude) <= MAX_DISTANCE_DEG &&
            abs(cachedLon - longitude) <= MAX_DISTANCE_DEG
    }
}

/**
 * Legge la temperatura corrente dalla risposta di Open-Meteo
 * (`{"current":{"temperature_2m":17.4,...}}`).
 *
 * `null` per qualunque cosa che non sia un numero plausibile: un HTML di errore,
 * un JSON cambiato, un valore fuori scala. Chi chiama distingue il ripiego dal
 * dato vero, e l'interfaccia lo mostra.
 */
internal fun parseTemperature(json: String): Float? = runCatching {
    val current = JSONObject(json).optJSONObject("current") ?: return@runCatching null
    val value = current.optDouble("temperature_2m", Double.NaN)
    if (value.isNaN() || !value.isFinite()) return@runCatching null
    val celsius = value.toFloat()
    celsius.takeIf { it >= -60f && it <= 70f }
}.getOrNull()
