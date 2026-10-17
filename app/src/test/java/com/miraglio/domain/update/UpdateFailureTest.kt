package com.miraglio.domain.update

import com.miraglio.R
import com.miraglio.i18n.StringCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La catena dell'aggiornamento lancia guasti **tipizzati**: portano l'id della
 * stringa e i suoi argomenti, e il testo nasce solo dove c'e' un `Texts`.
 *
 * Qui si controlla che quell'id arrivi davvero alla frase giusta, con tutti gli
 * argomenti al posto giusto e in tutte e due le lingue: un id sbagliato
 * mostrerebbe "(HTTP %1$s)" con un segnaposto vuoto dentro.
 *
 * Le stringhe sono quelle vere dei file di traduzione ([StringCatalog]), quindi
 * il test si accorge anche di una traduzione mancante.
 */
class UpdateFailureTest {

    private val italiano = StringCatalog.texts(StringCatalog.ITALIAN)
    private val inglese = StringCatalog.texts(StringCatalog.FALLBACK)

    @Test
    fun ilGuastoPortaAllaFraseGiusta() {
        val guasto = UpdateFailure(R.string.update_github_http_error, listOf(403))

        assertEquals("GitHub ha risposto 403", guasto.messageText(italiano))
        assertEquals("GitHub replied 403", guasto.messageText(inglese))
    }

    @Test
    fun tuttiGliArgomentiArrivanoAlLoroPosto() {
        val guasto = UpdateFailure(R.string.update_download_incomplete, listOf(1024, 4096))

        assertEquals("Download incompleto (1024 di 4096 byte)", guasto.messageText(italiano))
        assertEquals("Incomplete download (1024 of 4096 bytes)", guasto.messageText(inglese))
    }

    /** Un guasto senza argomenti: la frase esce intera, senza segnaposto vuoti. */
    @Test
    fun iGuastiSenzaArgomentiEsconoInteri() {
        val guasto = UpdateFailure(R.string.update_too_many_redirects)

        assertEquals("Troppi reindirizzamenti", guasto.messageText(italiano))
        assertEquals("Too many redirects", guasto.messageText(inglese))
        assertTrue(
            "segnaposto rimasto nel testo: ${guasto.messageText(inglese)}",
            !guasto.messageText(inglese).contains("%")
        )
    }

    @Test
    fun leDueLingueDannoTestiDiversi() {
        for (id in listOf(
            R.string.update_release_tag_unknown,
            R.string.update_release_no_apk,
            R.string.update_github_http_error,
            R.string.update_download_incomplete,
            R.string.update_redirect_without_target,
            R.string.update_download_refused,
            R.string.update_too_many_redirects,
            R.string.update_apk_missing,
            R.string.update_no_installer,
            R.string.update_unknown_error
        )) {
            val guasto = UpdateFailure(id)
            assertNotEquals(
                "stessa frase nelle due lingue per la stringa $id",
                guasto.messageText(inglese),
                guasto.messageText(italiano)
            )
        }
    }

    /**
     * I lettori di JSON e i downloader lanciavano `error(...)`, cioe' una
     * `IllegalStateException`: chi se ne aspettava il tipo (test compresi)
     * continua a trovarlo.
     */
    @Test
    fun restaUnIllegalStateException() {
        val guasto: Throwable = UpdateFailure(R.string.update_download_refused, listOf(500))

        assertTrue("tipo inatteso: ${guasto.javaClass.name}", guasto is IllegalStateException)
    }
}
