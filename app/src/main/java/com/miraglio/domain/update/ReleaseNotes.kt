package com.miraglio.domain.update

/**
 * Le note di rilascio su GitHub non contengono solo le novita': dalla 1.0.3 il
 * corpo della release comincia con la guida di installazione
 * (`INSTALLAZIONE.md`), che serve a chi apre la pagina della release ma non a
 * chi sta aggiornando da dentro l'app — li' finirebbe per mostrare quattro
 * schermate di istruzioni al posto del changelog.
 *
 * La guida si chiude con una riga-marcatore (un commento HTML, quindi
 * invisibile nella pagina): qui si taglia tutto fino a fine di quella riga e si
 * tiene quello che segue. Senza marcatore le note restano intatte, così questo
 * codice funziona anche con le release pubblicate prima della guida.
 */
object ReleaseNotes {

    /** Riga che chiude la guida in `INSTALLAZIONE.md` (non va cambiata in un posto solo). */
    const val GUIDE_END_MARKER = "<!-- fine-guida-installazione -->"

    /**
     * Solo le novita' della versione, da mostrare nel dialog di aggiornamento.
     * Se dopo il marcatore non c'e' niente, il risultato e' vuoto: meglio niente
     * che la guida di installazione.
     */
    fun forUpdateDialog(notes: String): String {
        val marker = notes.indexOf(GUIDE_END_MARKER)
        if (marker < 0) return notes.trim()
        // Si taglia fino alla fine della riga del marcatore: così un testo
        // aggiunto dopo il marcatore, sulla stessa riga, non finisce a schermo.
        val nextLine = notes.indexOf('\n', marker)
        val after = if (nextLine < 0) "" else notes.substring(nextLine + 1)
        return after
            .lineSequence()
            .dropWhile { it.isBlank() || it.trim() == "---" }
            .joinToString("\n")
            .trim()
    }
}
