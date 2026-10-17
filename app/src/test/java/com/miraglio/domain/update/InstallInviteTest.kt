package com.miraglio.domain.update

import com.miraglio.i18n.StringCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link e messaggio d'invito sono quello che un altro arciere riceve: se il link
 * punta al posto sbagliato, o a un file di una versione vecchia, l'invito
 * smette di funzionare senza che si veda niente di rotto.
 *
 * Le stringhe sono quelle vere dei file di traduzione ([StringCatalog]): cosi'
 * il test si accorge anche di un segnaposto perso o di una riga italiana
 * rimasta nel messaggio inglese.
 */
class InstallInviteTest {

    private val repo = "oraziog/miraglio"

    private val italiano = StringCatalog.texts(StringCatalog.ITALIAN)
    private val inglese = StringCatalog.texts(StringCatalog.FALLBACK)

    @Test
    fun ilLinkPortaAllaReleasePiuRecente() {
        assertEquals(
            "https://github.com/oraziog/miraglio/releases/latest",
            InstallInvite.releasePageUrl(repo)
        )
    }

    @Test
    fun ilMessaggioContieneIlLink() {
        val messaggio = InstallInvite.message(italiano, repo)
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
        val messaggio = InstallInvite.message(italiano, repo)
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
            InstallInvite.message(italiano, "altro/progetto").contains("altro/progetto")
        )
    }

    /**
     * Il messaggio invita un altro arciere: se parte in inglese deve essere
     * inglese tutto intero, senza righe italiane in mezzo.
     */
    @Test
    fun ilMessaggioSegueLaLingua() {
        val messaggio = InstallInvite.message(inglese, repo)

        assertTrue(
            "il messaggio inglese non contiene nessuna frase inglese: $messaggio",
            messaggio.contains("Try Miraglio")
        )
        for (parola in PAROLE_ITALIANE) {
            assertFalse(
                "parola italiana \"$parola\" nel messaggio inglese: $messaggio",
                messaggio.contains(parola)
            )
        }
        assertNotEquals(
            "il messaggio italiano e quello inglese sono identici",
            InstallInvite.message(italiano, repo),
            messaggio
        )
    }

    private companion object {
        /** Parole che possono comparire solo nel messaggio italiano. */
        val PAROLE_ITALIANE = listOf("Prova", "fotocamera", "calibrazione", "distanza")
    }
}
