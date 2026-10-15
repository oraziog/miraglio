package com.miraglio.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il confronto fra versioni e' la logica che decide se l'app si aggiorna: se
 * sbaglia, o non propone mai niente o propone un APK piu' vecchio di quello
 * installato (che Android rifiuta). Per questo e' l'unico pezzo coperto da
 * test automatici.
 */
class AppVersionTest {

    private fun version(raw: String): AppVersion =
        requireNotNull(AppVersion.parse(raw)) { "non leggibile: $raw" }

    @Test
    fun confrontaNumeriENonTesti() {
        // In ordine alfabetico "1.0.10" verrebbe prima di "1.0.9".
        assertTrue(version("1.0.10") > version("1.0.9"))
        assertTrue(version("1.10.0") > version("1.9.0"))
        assertTrue(version("2.0.0") > version("1.99.99"))
    }

    @Test
    fun leggeEtichetteConPrefissoESuffisso() {
        assertEquals(version("1.2.3"), version("v1.2.3"))
        assertEquals(version("1.2.3"), version("V1.2.3"))
        assertEquals(version("1.2.3"), version("v1.2.3-beta.1"))
    }

    @Test
    fun partiMancantiValgonoZero() {
        assertEquals(version("1.1"), version("1.1.0"))
        assertEquals(0, version("1.1.0.0").compareTo(version("1.1")))
        assertTrue(version("1.1.1") > version("1.1"))
    }

    @Test
    fun versioneIllegibileNonConfrontabile() {
        assertNull(AppVersion.parse("release-finale"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
    }

    @Test
    fun proponeaggiornamentoSoloSePiuRecente() {
        val release = ReleaseInfo(
            tagName = "v1.0.3",
            version = version("1.0.3"),
            notes = "",
            apkUrl = "https://example.invalid/miraglio.apk",
            apkSizeBytes = 1L
        )
        assertTrue(release.isNewerThan(version("1.0.2")))
        assertFalse(release.isNewerThan(version("1.0.3")))
        assertFalse(release.isNewerThan(version("1.0.4")))
        // Versione installata illeggibile: non si propone niente, per non
        // rischiare di far installare un APK piu' vecchio.
        assertFalse(release.isNewerThan(null))
    }
}
