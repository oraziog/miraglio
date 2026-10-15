package com.miraglio.data.update

import com.miraglio.domain.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La risposta di GitHub e' un contratto: se cambiano i nomi dei campi, l'app
 * smette di trovare l'APK e non si aggiorna piu' **senza mostrare nessun
 * errore** (il controllo fallirebbe in silenzio). Qui la lettura viene provata
 * contro la risposta vera della release v1.0.2, ridotta ai campi che servono.
 *
 * Dove una prova usa una risposta modificata, si controlla **prima** che la
 * modifica sia avvenuta: altrimenti un testo che non combacia piu' lascerebbe
 * il test verde senza provare niente.
 */
class ReleaseJsonTest {

    /** Risposta reale di /repos/oraziog/miraglio/releases/latest per la v1.0.2. */
    private val rispostaVera = """
        {
          "tag_name": "v1.0.2",
          "name": "Miraglio 1.0.2",
          "draft": false,
          "prerelease": false,
          "body": "**Full Changelog**: https://github.com/oraziog/miraglio/commits/v1.0.2",
          "tarball_url": "https://api.github.com/repos/oraziog/miraglio/tarball/v1.0.2",
          "zipball_url": "https://api.github.com/repos/oraziog/miraglio/zipball/v1.0.2",
          "assets": [
            {
              "id": 643624321,
              "name": "miraglio-1.0.2.apk",
              "content_type": "application/vnd.android.package-archive",
              "state": "uploaded",
              "size": 129333870,
              "browser_download_url": "https://github.com/oraziog/miraglio/releases/download/v1.0.2/miraglio-1.0.2.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun leggeLaReleasePubblicata() {
        val release = ReleaseJson.releaseFrom(rispostaVera)

        assertEquals("v1.0.2", release.tagName)
        assertEquals("1.0.2", release.displayVersion)
        assertEquals("1.0.2", release.version.toString())
        assertEquals(129333870L, release.apkSizeBytes)
        assertTrue(release.apkUrl.endsWith("/v1.0.2/miraglio-1.0.2.apk"))
        assertTrue(release.notes.startsWith("**Full Changelog**"))

        // Il caso che conta: la 1.0.2 pubblicata e' piu' recente della 1.0.1
        // installata, e non di se stessa (altrimenti l'app se la riproporrebbe
        // a ogni avvio).
        assertTrue(release.isNewerThan(AppVersion.parse("1.0.1")))
        assertFalse(release.isNewerThan(AppVersion.parse("1.0.2")))
    }

    @Test
    fun saltaGliAllegatiCheNonSonoApk() {
        val nota = """
            {
              "name": "note-di-rilascio.txt",
              "size": 42,
              "browser_download_url": "https://example.invalid/note.txt"
            },
        """.trimIndent()
        val conNotaPrima = rispostaVera.replaceFirst("\"assets\": [", "\"assets\": [\n$nota")

        // La modifica deve essere avvenuta davvero, altrimenti questa prova
        // sarebbe verde senza aver messo alla prova lo scarto degli allegati.
        assertTrue("l'allegato di prova non e' entrato", conNotaPrima.contains("note-di-rilascio.txt"))

        val release = ReleaseJson.releaseFrom(conNotaPrima)
        assertTrue(release.apkUrl.endsWith("miraglio-1.0.2.apk"))
    }

    @Test
    fun releaseSenzaApkRifiutata() {
        val senzaApk = """
            {
              "tag_name": "v1.0.3",
              "body": "solo note, nessun APK",
              "assets": [
                {
                  "name": "note-di-rilascio.txt",
                  "size": 42,
                  "browser_download_url": "https://example.invalid/note.txt"
                }
              ]
            }
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) {
            ReleaseJson.releaseFrom(senzaApk)
        }
    }

    @Test
    fun etichettaNonNumericaRifiutata() {
        val senzaVersione = rispostaVera.replaceFirst("\"tag_name\": \"v1.0.2\"", "\"tag_name\": \"release-finale\"")
        assertTrue("la sostituzione non e' avvenuta", senzaVersione.contains("release-finale"))

        assertThrows(IllegalStateException::class.java) {
            ReleaseJson.releaseFrom(senzaVersione)
        }
    }
}
