package com.miraglio.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Il selettore della lingua (Impostazioni -> Lingua) puo' sbagliare in silenzio:
 * una voce in piu' di quelle tradotte si sceglie e non cambia niente, una in
 * meno non si puo' scegliere anche se l'app la parla, e un tag non dichiarato ad
 * Android (vedi `res/xml/locales_config.xml`) resta scritto nelle preferenze
 * senza che il sistema lo applichi.
 *
 * Questi tre elenchi sono la stessa cosa vista da tre posti — il codice, il
 * manifest, le cartelle di risorse — e qui si controlla che restino uguali.
 */
class AppLanguageTest {

    /** Le lingue che si possono scegliere: i tag di [AppLanguageChoice], meno "segui il telefono". */
    private val scegliibili: List<String> =
        AppLanguageChoice.entries.mapNotNull { it.tag }.sorted()

    @Test
    fun laSceltaSiRiconosceAncheConLaRegione() {
        assertEquals(AppLanguageChoice.ITALIAN, AppLanguageChoice.of("it"))
        assertEquals(AppLanguageChoice.ITALIAN, AppLanguageChoice.of("it-IT"))
        assertEquals(AppLanguageChoice.ITALIAN, AppLanguageChoice.of("IT"))
        assertEquals(AppLanguageChoice.SPANISH, AppLanguageChoice.of("es"))
        assertEquals(AppLanguageChoice.SPANISH, AppLanguageChoice.of("es-ES"))
        assertEquals(AppLanguageChoice.SPANISH, AppLanguageChoice.of("es-419"))
        assertEquals(AppLanguageChoice.SPANISH, AppLanguageChoice.of("ES"))
        assertEquals(AppLanguageChoice.ENGLISH, AppLanguageChoice.of("en"))
        assertEquals(AppLanguageChoice.ENGLISH, AppLanguageChoice.of("en-GB"))
    }

    /**
     * Ogni lingua del selettore ha un'etichetta che non e' un'altra lingua:
     * due voci sulla stessa etichetta (un copia-incolla) renderebbero una
     * delle due irraggiungibile.
     */
    @Test
    fun leEtichetteDelleLingueSonoDistinte() {
        val etichette = scegliibili
        assertEquals("etichette ripetute nel selettore", etichette.distinct(), etichette)
    }

    /**
     * Nessuna scelta, o una che non conosciamo (una lingua tolta dall'app, una
     * preferenza scritta da una versione vecchia), non deve lasciare l'app senza
     * lingua: si torna a quella del telefono.
     */
    @Test
    fun senzaSceltaOSconosciutaSiSegueIlTelefono() {
        assertEquals(AppLanguageChoice.SYSTEM, AppLanguageChoice.of(null))
        assertEquals(AppLanguageChoice.SYSTEM, AppLanguageChoice.of(""))
        assertEquals(AppLanguageChoice.SYSTEM, AppLanguageChoice.of("fr"))
    }

    /** "Segui il telefono" non e' una lingua: e' l'assenza di una scelta. */
    @Test
    fun ilSeguiIlTelefonoNonHaTag() {
        assertEquals(null, AppLanguageChoice.SYSTEM.tag)
        assertTrue("nessuna lingua da scegliere", scegliibili.isNotEmpty())
        assertTrue("nel selettore non deve finire un tag vuoto", scegliibili.none { it.isBlank() })
    }

    /** Le lingue dichiarate ad Android, cioe' quelle che il sistema sa applicare. */
    @Test
    fun leLingueDelSelettoreSonoDichiarateAdAndroid() {
        val dichiarate = dichiarateNelLocaleConfig().sorted()

        assertEquals(
            "lingue scegliibili nell'app e lingue dichiarate in " +
                "res/xml/locales_config.xml non coincidono",
            dichiarate,
            scegliibili
        )
    }

    /**
     * Ogni lingua scegliibile deve avere la sua cartella di risorse: senza,
     * sceglierla mostrerebbe il ripiego (l'inglese) e sembrerebbe che il
     * selettore non funzioni. Vale anche al contrario: una cartella di
     * traduzione che non si puo' scegliere e' codice morto.
     */
    @Test
    fun ogniLinguaScegliibileHaLaSuaTraduzione() {
        val cartelle = cartelleDiLingua().sorted()

        assertEquals(
            "cartelle di risorse (values/, values-it/, …) e lingue scegliibili " +
                "non coincidono",
            cartelle,
            scegliibili
        )
    }

    /**
     * Legge `<locale android:name="…"/>` da `res/xml/locales_config.xml`. Il file
     * non passa da Android in un test JVM, quindi si legge dal disco come fanno
     * gli altri test delle risorse (vedi [StringCatalog]).
     */
    private fun dichiarateNelLocaleConfig(): List<String> {
        val file = StringCatalog.resourceFile("xml", "locales_config.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)

        val nodes = document.getElementsByTagName("locale")
        val lingue = (0 until nodes.length).map { index ->
            (nodes.item(index) as Element).getAttribute("android:name")
        }

        // Se il file smettesse di contenere le lingue, il confronto sopra
        // passerebbe solo perche' non guarda niente.
        assertTrue("nessuna lingua dichiarata in ${file.path}", lingue.isNotEmpty())
        return lingue
    }

    /**
     * Le cartelle di lingua presenti in `src/main/res`, come tag.
     *
     * `values/` non porta la lingua nel nome: per scelta di progetto e' il
     * ripiego, cioe' l'inglese (lo verifica il test che cerca l'italiano in
     * `values/`, vedi [StringsXmlTest]).
     */
    private fun cartelleDiLingua(): List<String> {
        val res = StringCatalog.resourceFile("values", "strings.xml").parentFile!!.parentFile!!
        val cartelle = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }

        assertTrue("nessuna cartella di lingua in ${res.path}", cartelle != null && cartelle.isNotEmpty())
        return cartelle!!.map { folder ->
            val qualificatore = folder.name.removePrefix("values").removePrefix("-")
            qualificatore.substringBefore("-").ifEmpty { "en" }
        }
    }
}
