package com.miraglio.i18n

import java.io.File

/**
 * Legge i sorgenti Kotlin come testo, senza compilatore e senza Android: serve
 * al test che cerca testi italiani rimasti dentro il codice.
 *
 * Non e' un parser: bastano due cose.
 * 1. **Trovare i letterali di stringa**, saltando commenti, stringhe grezze
 *    (`"""…"""`) e letterali di carattere (`'x'`). Gli apostrofi e le virgolette
 *    sfuggite non devono far uscire fuori strada.
 * 2. **Saltare gli argomenti di `Log.…(…)`**: i log sono per chi sviluppa e in
 *    questo progetto sono in italiano apposta; quello che non deve passare e'
 *    il testo che finisce a schermo.
 */
internal object ItalianSourceScan {

    /** Un letterale di stringa con la riga in cui comincia (per il messaggio d'errore). */
    data class Literal(val text: String, val line: Int)

    private const val RAW = "\"\"\""

    /**
     * Parole che in inglese non compaiono (o compaiono solo dentro parole piu'
     * lunghe) e che quindi tradiscono un testo italiano. Elenco chiuso e
     * conservativo: le preposizioni e gli articoli fanno il lavoro grosso,
     * qualche parola di senso compiuto copre le frasi brevi ("Annulla",
     * "Indietro").
     */
    private val PAROLE = listOf(
        "di", "del", "dello", "della", "dei", "degli", "delle",
        "il", "lo", "la", "le", "gli", "che", "per", "con", "non",
        "nel", "nello", "nella", "nei", "negli", "nelle",
        "alla", "allo", "ai", "agli", "alle", "sul", "sullo", "sulla", "sui", "sugli", "sulle",
        "più", "può", "già", "sono", "serve", "deve", "adesso", "quindi", "senza",
        "anche", "quando", "dove", "questo", "questa", "queste", "nostro", "ogni", "basta",
        "lente", "luogo", "luoghi", "distanza", "misura", "misure", "errore", "errori",
        "precisione", "partenza", "arrivo", "scatto", "bersaglio", "salvato", "salvata",
        "condividi", "annulla", "elimina", "rinomina", "naviga", "indietro",
        "nessun", "nessuna", "troppi", "troppe", "attendi", "inserisci",
        "apri", "chiudi", "mostra", "imposta", "scegli", "tocca", "premi", "scrivi",
        "cammina", "resta"
    )

    private val ITALIANO = Regex(
        "\\b(" + PAROLE.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** Parole italiane dentro un testo, in ordine di comparsa. */
    fun italianWords(text: String): List<String> =
        ITALIANO.findAll(text).map { it.value }.toList()

    /**
     * I letterali di stringa del sorgente, con la riga.
     *
     * I letterali dentro una chiamata di log non compaiono: sono diagnostica,
     * non testo per l'utente.
     */
    fun literals(source: String): List<Literal> {
        val log = logArguments(source)
        val out = mutableListOf<Literal>()
        var i = 0
        var line = 1
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                source.startsWith("/*", i) -> {
                    i += 2
                    while (i < source.length && !source.startsWith("*/", i)) {
                        if (source[i] == '\n') line++
                        i++
                    }
                    i += 2
                }

                source.startsWith(RAW, i) -> {
                    val end = source.indexOf(RAW, i + RAW.length)
                    val stop = if (end < 0) source.length else end + RAW.length
                    line += source.substring(i, stop).count { it == '\n' }
                    i = stop
                }

                source[i] == '"' -> {
                    val start = i
                    val text = StringBuilder()
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            text.append(source[i + 1])
                            i += 2
                        } else {
                            text.append(source[i])
                            i++
                        }
                    }
                    i++
                    if (log.none { start in it }) out += Literal(text.toString(), line)
                }

                source[i] == '\'' -> {
                    i++
                    while (i < source.length && source[i] != '\'') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                }

                source[i] == '\n' -> {
                    line++
                    i++
                }

                else -> i++
            }
        }
        return out
    }

    /**
     * Tutti i `.kt` di `src/main/java`, cercando la cartella risalendo dalla
     * cartella corrente: Gradle lancia i test dal modulo (`app/`), ma da riga
     * di comando o dall'IDE la cartella corrente puo' essere la radice.
     */
    fun mainSources(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val root = File(dir, "${prefix}src/main/java")
                if (root.isDirectory) return root.walkTopDown().filter { it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main/java non trovata partendo da ${File(".").absolutePath}"
        )
    }

    /**
     * Intervalli (in caratteri) degli argomenti delle chiamate di log.
     *
     * `Log.i(TAG, "…")` puo' contenere stringhe concatenate su piu' righe, e
     * allora conta l'estensione dell'intera chiamata: si individua la parentesi
     * di apertura dopo `Log.` e si bilancia fino alla sua chiusura, saltando le
     * stringhe (altrimenti una parentesi dentro un testo sposterebbe la fine).
     */
    private fun logArguments(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var search = 0
        while (true) {
            val at = source.indexOf("Log.", search)
            if (at < 0) return ranges
            val open = source.indexOf('(', at)
            if (open < 0) return ranges
            val close = matchParenthesis(source, open)
            ranges += (open + 1) until close
            search = close + 1
        }
    }

    /** Indice della parentesi di chiusura di quella aperta in [openIndex]. */
    private fun matchParenthesis(source: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < source.length) {
            when {
                source.startsWith(RAW, i) -> {
                    val end = source.indexOf(RAW, i + RAW.length)
                    i = if (end < 0) source.length else end + RAW.length
                }

                source[i] == '"' -> i = endOfString(source, i)
                source[i] == '\'' -> i = endOfChar(source, i)
                source[i] == '(' -> {
                    depth++
                    i++
                }

                source[i] == ')' -> {
                    depth--
                    i++
                    if (depth == 0) return i
                }

                else -> i++
            }
        }
        return source.length
    }

    private fun endOfString(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '"') return i + 1
            i++
        }
        return source.length
    }

    private fun endOfChar(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '\'') return i + 1
            i++
        }
        return source.length
    }
}
