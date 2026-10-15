package com.miraglio.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Il corpo di una release e' due cose insieme: la guida di installazione (per
 * chi apre la pagina su GitHub) e il changelog. Nel dialog di aggiornamento
 * dell'app va solo il changelog: se il taglio sbaglia, chi sta aggiornando si
 * ritrova quattro schermate di istruzioni al posto delle novita'.
 */
class ReleaseNotesTest {

    /** Corpo della release come lo pubblica il workflow: guida, marcatore, changelog. */
    private val corpoDellaRelease = """
        # Miraglio — come si installa sul telefono

        ## 1. Apri il link della release (dal telefono)

        👉 <https://github.com/oraziog/miraglio/releases/latest>

        ---

        ${ReleaseNotes.GUIDE_END_MARKER}

        ---

        **Full Changelog**: https://github.com/oraziog/miraglio/commits/v1.0.3
    """.trimIndent()

    @Test
    fun senzaMarcatoreLeNoteRestanoIntatte() {
        // E' il corpo pubblicato davvero nella v1.0.2, prima della guida.
        val soloChangelog =
            "**Full Changelog**: https://github.com/oraziog/miraglio/commits/v1.0.2"
        assertEquals(soloChangelog, ReleaseNotes.forUpdateDialog(soloChangelog))
    }

    @Test
    fun toglieLaGuidaELasciaLeNovita() {
        val novita = ReleaseNotes.forUpdateDialog(corpoDellaRelease)

        assertEquals(
            "**Full Changelog**: https://github.com/oraziog/miraglio/commits/v1.0.3",
            novita
        )
        assertFalse("nella parte mostrata e' rimasta la guida", novita.contains("installazione"))
    }

    @Test
    fun guidaSenzaChangelogNonMostraLaGuida() {
        val soloGuida = corpoDellaRelease
            .substringBefore("**Full Changelog**")
            .trimEnd()
        assertTrue("la prova non ha piu' la guida", soloGuida.contains("# Miraglio"))

        assertEquals("", ReleaseNotes.forUpdateDialog(soloGuida))
    }

    /**
     * Il taglio dipende dalla riga-marcatore scritta in `INSTALLAZIONE.md`: se
     * qualcuno la toglie o la modifica, il marcatore in Kotlin smette di
     * combaciare e la guida ricompare nel dialog. Questo test lega le due cose.
     */
    @Test
    fun ilMarcatoreStaNelFilePubblicato() {
        val guida = trovaGuida()
        assertTrue(
            "INSTALLAZIONE.md deve chiudersi con ${ReleaseNotes.GUIDE_END_MARKER}",
            guida.readText().trimEnd().endsWith(ReleaseNotes.GUIDE_END_MARKER)
        )
    }

    /**
     * Il file sta nella radice del progetto, ma la cartella da cui girano i test
     * dipende da come li lancia Gradle: si risale finche' non lo si trova.
     */
    private fun trovaGuida(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidato = File(dir, "INSTALLAZIONE.md")
            if (candidato.isFile) return candidato
            dir = dir.parentFile
        }
        throw AssertionError("INSTALLAZIONE.md non trovato partendo da ${File(".").absolutePath}")
    }
}
