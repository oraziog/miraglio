package com.miraglio.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.miraglio.data.settings.AppSettingsStore
import com.miraglio.domain.format.DisplayFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lingua dell'app, scelta dall'utente nelle Impostazioni.
 *
 * Quattro possibilita': [SYSTEM] ("come il telefono") e le lingue che l'app
 * parla davvero (`values/` in inglese come ripiego, piu' una cartella per ogni
 * traduzione: `values-it/`, `values-es/`).
 * [tag] e' l'etichetta BCP 47 che finisce nelle preferenze: `null` significa
 * "nessuna scelta, segui il telefono".
 *
 * L'elenco non e' libero: ogni tag qui dentro deve essere dichiarato in
 * `res/xml/locales_config.xml` ed esistere come cartella di risorse, altrimenti
 * la voce si potrebbe scegliere e non cambierebbe niente. Lo controlla
 * `AppLanguageTest`.
 */
enum class AppLanguageChoice(val tag: String?) {

    /** Segue la lingua del telefono (o quella scelta per Miraglio in Android). */
    SYSTEM(null),
    ITALIAN("it"),
    SPANISH("es"),
    ENGLISH("en");

    companion object {

        /**
         * Scelta corrispondente a un'etichetta salvata.
         *
         * Si confronta la **lingua** e non l'etichetta intera: il sistema puo'
         * conservare `it-IT` dove noi scriviamo `it`, e sono la stessa lingua.
         * Un'etichetta sconosciuta o assente vale [SYSTEM]: meglio la lingua
         * del telefono di una scelta che non si vede.
         */
        fun of(tag: String?): AppLanguageChoice {
            val language = tag?.let { Locale.forLanguageTag(it).language } ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}

/**
 * Etichetta della lingua scelta, oppure `null` quando la lingua dell'app e'
 * quella del telefono.
 *
 * Da Android 13 la lingua e' una preferenza **di sistema per singola app**
 * (`Impostazioni -> App -> Miraglio -> Lingua`, vedi `locales_config.xml`): si
 * legge e si scrive la', cosi' la scelta fatta qui e quella fatta dal sistema
 * sono la stessa cosa. Sotto Android 13 quel meccanismo non esiste, quindi la
 * scelta la custodisce l'app (`AppSettingsStore` usa lo stesso file di
 * preferenze, con una chiave diversa).
 *
 * E' una funzione e non un metodo di [AppLanguage] perche' serve **prima** che
 * Hilt esista: `MainActivity.attachBaseContext` la chiama per dare all'activity
 * le risorse della lingua giusta.
 */
fun appLanguageTag(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = localeManagerOf(context)
        if (manager != null) {
            val locales = manager.getApplicationLocales()
            // Elenco vuoto = nessuna scelta: si segue il telefono.
            return if (locales.isEmpty) null else locales[0]?.toLanguageTag()
        }
    }
    return languagePrefs(context).getString(KEY_LANGUAGE, null)
}

/**
 * Registra la scelta. Da Android 13 la applica il sistema (e con essa ricrea da
 * solo le activity); sotto, la scelta resta nelle preferenze e tocca a chi
 * chiama ricreare l'activity (vedi [AppLanguage.needsRestart]).
 */
fun setAppLanguage(context: Context, choice: AppLanguageChoice) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = localeManagerOf(context)
        if (manager != null) {
            val locales = choice.tag
                ?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
            manager.setApplicationLocales(locales)
            return
        }
    }
    languagePrefs(context).edit().putString(KEY_LANGUAGE, choice.tag).apply()
}

/**
 * Contesto con le risorse della lingua scelta, per il codice che sta **fuori**
 * da Compose (le ViewModel e le composizioni di testo passano da `Texts`).
 *
 * Serve perche' le stringhe di un `@Composable` seguono la configurazione
 * dell'activity, mentre `AndroidTexts` parte dal contesto dell'applicazione:
 * senza questo involucro un messaggio nato in una ViewModel resterebbe nella
 * lingua precedente mentre il resto della schermata cambia.
 *
 * Senza nessuna scelta si usa il contesto cosi' com'e', che e' gia' quello
 * giusto: le sue risorse sono quelle dell'app.
 */
fun localizedContext(context: Context): Context {
    val tag = appLanguageTag(context) ?: return context
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList.forLanguageTags(tag))
    return context.createConfigurationContext(configuration)
}

/**
 * Contesto di partenza dell'activity.
 *
 * Sotto Android 13 la lingua dell'activity si decide qui, avvolgendo il
 * contesto di base con la lingua scelta ([localizedContext]); da Android 13 in
 * poi ci pensa il sistema, che riscrive la configurazione dell'app, quindi
 * avvolgere di nuovo sarebbe solo un doppione.
 */
fun languageAwareBaseContext(base: Context): Context =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) base else localizedContext(base)

/**
 * La lingua dell'app mentre e' in esecuzione: la scelta dell'utente, o quella
 * delle risorse quando non c'e' nessuna scelta.
 *
 * E' un oggetto con stato e non due funzioni sciolte perche' i testi che
 * arrivano a schermo (stringhe e numeri) nascono anche da oggetti che vivono
 * quanto il processo: se la lingua la leggessero una volta sola alla creazione,
 * un cambio di lingua lascerebbe in giro pezzi nella lingua vecchia.
 */
@Singleton
class AppLanguage @Inject constructor(
    @ApplicationContext private val context: Context,
    // Il sistema di unita' non e' la lingua, ma il formattatore li tiene
    // insieme: chi compone un testo (una ViewModel via `Texts`) deve ricevere
    // numeri gia' scritti bene, senza sapere ne' dell'una ne' dell'altro.
    private val settings: AppSettingsStore
) {

    private val _selected = MutableStateFlow(AppLanguageChoice.of(appLanguageTag(context)))

    /** Scelta in corso, per il selettore nelle Impostazioni. */
    val selected: StateFlow<AppLanguageChoice> = _selected.asStateFlow()

    private val _needsRestart = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Si alza quando il cambio di lingua non lo sa applicare il sistema, cioe'
     * sotto Android 13: li' l'activity va ricreata a mano (l'ascolta
     * `MainActivity`). Da Android 13 in poi il flusso resta muto, perche' le
     * activity le ricrea il sistema e ricrearle una seconda volta sarebbe solo
     * uno sfarfallio.
     */
    val needsRestart: SharedFlow<Unit> = _needsRestart.asSharedFlow()

    /** Cambia lingua subito, senza passare dal prossimo avvio. */
    fun select(choice: AppLanguageChoice) {
        if (choice == _selected.value) return
        setAppLanguage(context, choice)
        _selected.value = choice
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) _needsRestart.tryEmit(Unit)
    }

    /** La lingua in vigore, quella da cui dipendono numeri e date. */
    fun current(): Locale {
        appLanguageTag(context)?.let { return Locale.forLanguageTag(it) }
        // Nessuna scelta: vale la lingua delle risorse dell'app, cioe' quella
        // del telefono (o quella scelta per Miraglio in Android, da Android 13).
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    /** Risorse nella lingua in vigore, per le stringhe fuori da Compose. */
    fun localizedContext(): Context = localizedContext(context)

    /** Un formattatore per lingua: vedi [format]. */
    private val formats = mutableMapOf<String, DisplayFormat>()

    /**
     * Formattatore in vigore: lingua **e** sistema di unita' scelti nelle
     * Impostazioni. Si tiene da parte per combinazione delle due cose: chi
     * compone testo lo chiede a ogni messaggio, e ricostruire due
     * `DateTimeFormatter` ogni volta sarebbe lavoro sprecato.
     *
     * La chiave comprende il sistema di unita': se fosse solo la lingua, chi
     * passa in iarde continuerebbe a ricevere il formattatore in metri costruito
     * prima, e la scelta non si vedrebbe fino al riavvio dell'app.
     */
    fun format(): DisplayFormat {
        val locale = current()
        val unit = settings.unitSystem.value
        val key = "${locale.toLanguageTag()}|${unit.stored}"
        synchronized(formats) {
            return formats.getOrPut(key) { DisplayFormat(locale, unit) }
        }
    }
}

/** Il servizio delle lingue per app: esiste solo da Android 13 in poi. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun localeManagerOf(context: Context): LocaleManager? =
    context.getSystemService(LocaleManager::class.java)

/**
 * Stesso file di preferenze di [com.miraglio.data.settings.AppSettingsStore],
 * con una chiave sua: la lingua si legge prima che Hilt esista.
 */
private fun languagePrefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private const val PREFS = "miraglio_settings"
private const val KEY_LANGUAGE = "app_language"
