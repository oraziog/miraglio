package com.miraglio.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La temperatura entra nel conto della velocità del suono, e un valore
 * sbagliato sposta la distanza di metri. Qui si prova che il testo che arriva
 * da Open-Meteo venga letto solo quando è davvero un numero plausibile, e che
 * la cache scada e si "sposti" con l'arciere.
 */
class WeatherCacheTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun leggeLaTemperaturaCorrente() {
        val json = """{"current":{"temperature_2m":17.4,"time":"2026-11-20T10:00"}}"""
        assertEquals(17.4f, parseTemperature(json)!!, 0.001f)
    }

    @Test
    fun unaRispostaSenzaCurrentNonEUnTemperatura() {
        assertNull(parseTemperature("""{"error":true,"reason":"Invalid latitude"}"""))
        assertNull(parseTemperature("""{"current":{"humidity":50}}"""))
        assertNull(parseTemperature("<html>503</html>"))
        assertNull(parseTemperature(""))
    }

    @Test
    fun unValoreFuoriScalaNonEntra() {
        assertNull(parseTemperature("""{"current":{"temperature_2m":999}}"""))
        assertNull(parseTemperature("""{"current":{"temperature_2m":-120}}"""))
        assertNull(parseTemperature("""{"current":{"temperature_2m":null}}"""))
    }

    @Test
    fun laCacheEVAlidaPerTrentaMinuti() {
        assertTrue(fresh(cachedAt = now - 10 * minute))
        assertTrue(fresh(cachedAt = now - 29 * minute))
        assertFalse(fresh(cachedAt = now - 31 * minute))
    }

    /** Trenta minuti sono anche il tempo di spostarsi di venti chilometri. */
    @Test
    fun laCacheNonValePerUnAltroLuogo() {
        assertFalse(
            WeatherCachePolicy.isFresh(
                cachedAtMs = now - 5 * minute,
                nowMs = now,
                cachedLat = 45.0,
                cachedLon = 9.0,
                latitude = 45.5,
                longitude = 9.0
            )
        )
    }

    @Test
    fun unOrologioIndietroNonRendeFrescaLaCache() {
        assertFalse(fresh(cachedAt = now + 5 * minute))
        assertFalse(fresh(cachedAt = 0L))
    }

    @Test
    fun senzaNessunDatoSiUsaIlRipiego() {
        assertEquals(20f, WeatherCachePolicy.FALLBACK_CELSIUS)
    }

    private fun fresh(cachedAt: Long): Boolean = WeatherCachePolicy.isFresh(
        cachedAtMs = cachedAt,
        nowMs = now,
        cachedLat = 45.0,
        cachedLon = 9.0,
        latitude = 45.0,
        longitude = 9.0
    )
}
