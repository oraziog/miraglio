package com.miraglio.i18n

import com.miraglio.R
import com.miraglio.domain.format.DisplayFormat
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Legge i file `strings.xml` come semplici file XML, senza Android: i test JVM
 * non hanno risorse (nessun Robolectric in questo progetto), quindi l'unico modo
 * per confrontare le traduzioni e' leggerle dal disco.
 *
 * Serve a due cose:
 * 1. il test di parita' fra `values/` (inglese, lingua di ripiego) e **ogni**
 *    cartella di traduzione (`values-it/`, `values-es/`, …): chiavi uguali,
 *    segnaposto uguali, niente valori vuoti e nessuna traduzione copiata;
 * 2. dare ai test del dominio stringhe **vere** invece di un finto con dentro
 *    due stringhe inventate: [XmlTexts] risolve un `R.string.…` per nome e
 *    pesca il valore dal file.
 */
object StringCatalog {

    /** Una voce di `strings.xml`. */
    data class Entry(val name: String, val value: String) {

        /**
         * Segnaposto posizionali presenti, es. `%1$s`, `%2$d`, in ordine.
         * Si confrontano fra le lingue: un segnaposto perso in traduzione fa
         * uscire un `%1$s` a schermo.
         */
        val placeholders: List<String> =
            PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

        companion object {
            private val PLACEHOLDER = Regex("""%\d+\$[a-zA-Z]""")
        }
    }

    /** Lingua di ripiego: `values/`, che per scelta di progetto e' **inglese**. */
    const val FALLBACK = "values"

    /** Italiano: la traduzione in `values-it/`. */
    const val ITALIAN = "values-it"

    /** Spagnolo: la traduzione in `values-es/`. */
    const val SPANISH = "values-es"

    /**
     * Le cartelle di traduzione presenti in `src/main/res`, lette dal disco e
     * non da un elenco scritto a mano: cosi' una lingua aggiunta a meta' (la
     * cartella c'e', i test no) viene comunque confrontata col ripiego.
     *
     * Le cartelle con un qualificatore di regione (`values-b+es+419`) non si
     * distinguono qui: il nome intero e' la chiave, le voci si confrontano una
     * per una.
     */
    fun translationFolders(): List<String> {
        val res = resourceFile(FALLBACK, "strings.xml").parentFile!!.parentFile!!
        val cartelle = res.listFiles { file -> file.isDirectory && file.name.startsWith("values-") }

        if (cartelle == null || cartelle.isEmpty()) {
            throw AssertionError("nessuna cartella di traduzione in ${res.path}")
        }
        return cartelle.map { it.name }.sorted()
    }

    /**
     * Le voci (`<string>`) di una cartella di risorse.
     *
     * [folder] e' il nome della cartella, non un percorso: "values" o
     * "values-it". Le `<plurals>` non si leggono: se un giorno servissero,
     * vanno aggiunte qui e al confronto.
     */
    fun strings(folder: String): Map<String, Entry> {
        val file = resourceFile(folder, "strings.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)

        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                put(name, Entry(name, unescape(element.textContent)))
            }
        }
    }

    /** Solo nome -> valore, per comodita' nei test. */
    fun values(folder: String): Map<String, String> =
        strings(folder).mapValues { it.value.value }

    /**
     * I `R.string.…` di [locale], risolti per nome.
     *
     * Per gli oggetti di dominio che compongono testo ma devono restare
     * provabili senza Android (`ShareMessageComposer`, `InstallInvite`).
     *
     * Il formattatore di numeri e date e' quello della **stessa** lingua delle
     * stringhe ([DisplayFormat.ofResourceFolder]): cosi' un test puo' provare
     * che "20,5 m" non diventi "20.5 m" quando cambia la lingua.
     */
    fun texts(locale: String): Texts =
        XmlTexts(values(locale), DisplayFormat.ofResourceFolder(locale))

    /**
     * Individua `app/src/main/res/<folder>/<file>` risalendo dalla cartella
     * corrente. Serve anche a chi legge risorse che non sono `strings.xml`
     * (per esempio `xml/locales_config.xml`).
     */
    fun resourceFile(folder: String, file: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            // Gradle lancia i test dalla cartella del modulo (`app/`), ma da
            // riga di comando o dall'IDE la cartella corrente puo' essere la
            // radice del progetto: si provano entrambe le posizioni.
            for (prefix in listOf("", "app")) {
                val candidate = File(dir, if (prefix.isEmpty())
                    "src/main/res/$folder/$file"
                else
                    "$prefix/src/main/res/$folder/$file")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main/res/$folder/$file non trovato partendo da ${File(".").absolutePath}"
        )
    }

    /**
     * Riporta le sequenze di escape di Android come le intende AAPT: nei file
     * gli apostrofi si scrivono `\'` (altrimenti la compilazione delle risorse
     * si ferma), ma per confrontarli e per usarli in un test valgono `'`.
     */
    private fun unescape(raw: String): String = buildString(raw.length) {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\\' && index + 1 < raw.length) {
                when (val next = raw[index + 1]) {
                    '\'', '"', '\\' -> {
                        append(next)
                        index += 2
                    }

                    'n' -> {
                        append('\n')
                        index += 2
                    }

                    else -> {
                        append(char)
                        index++
                    }
                }
            } else {
                append(char)
                index++
            }
        }
    }
}

/**
 * [Texts] sui valori letti da un `strings.xml`, per i test JVM.
 *
 * L'id intero di `R.string.…` viene tradotto nel suo **nome** per riflessione,
 * poi cercato nel file: cosi' i test del dominio asseriscono le stringhe vere
 * (tradotte e con i segnaposto giusti) senza un emulatore.
 */
private class XmlTexts(
    private val byName: Map<String, String>,
    override val format: DisplayFormat
) : Texts {

    private val nameOfId: Map<Int, String> =
        R.string::class.java.fields.associate { it.getInt(null) to it.name }

    override fun get(id: Int, vararg args: Any): String {
        val name = nameOfId[id] ?: throw AssertionError("nessun R.string con id $id")
        val format = byName[name] ?: throw AssertionError("stringa mancante nel file: $name")
        return if (args.isEmpty()) format
        else String.format(Locale.ROOT, format, *args)
    }
}
