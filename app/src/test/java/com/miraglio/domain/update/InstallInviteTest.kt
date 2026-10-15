package com.miraglio.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link e messaggio d'invito sono quello che un altro arciere riceve: se il link
 * punta al posto sbagliato, o a un file di una versione vecchia, l'invito
 * smette di funzionare senza che si veda niente di rotto.
 */
class InstallInviteTest {

    private val repo = "oraziog/miraglio"

    @Test
    fun ilLinkPortaAllaReleasePiuRecente() {
        assertEquals(
            "https://github.com/oraziog/miraglio/releases/latest",
            InstallInvite.releasePageUrl(repo)
        )
    }

    @Test
    fun ilMessaggioContieneIlLink() {
        val messaggio = InstallInvite.message(repo)
        assertTrue(
            "manca il link nel messaggio: $messaggio",
            messaggio.contains(InstallInvite.releasePageUrl(repo))
        )
    }

    /**
     * Il messaggio non deve nominare una versione: il link punta all'ultima
     * release, quindi il testo resta valido anche dopo le versioni successive.
     */
    @Test
    fun ilMessaggioNonInvecchia() {
        val messaggio = InstallInvite.message(repo)
        assertFalse("il messaggio nomina una versione", messaggio.contains("v1."))
        assertFalse(
            "il messaggio linka l'APK di una versione precisa",
            messaggio.contains("releases/download")
        )
    }

    /** Cambiando repository (una riga in build.gradle.kts) cambia tutto il resto. */
    @Test
    fun ilLinkSegueIlRepository() {
        assertEquals(
            "https://github.com/altro/progetto/releases/latest",
            InstallInvite.releasePageUrl("altro/progetto")
        )
        assertTrue(
            InstallInvite.message("altro/progetto").contains("altro/progetto")
        )
    }
}
