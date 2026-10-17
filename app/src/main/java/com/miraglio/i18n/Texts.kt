package com.miraglio.i18n

import androidx.annotation.StringRes
import com.miraglio.domain.format.DisplayFormat

/**
 * Accesso alle stringhe tradotte da codice che **non** e' un `@Composable`.
 *
 * Dentro le schermate Compose si usa `stringResource(R.string.…)`, che e' la
 * forma idiomatica. Ma il testo per l'utente nasce anche fuori da un contesto
 * Compose: le ViewModel compongono messaggi di stato e di errore, e
 * l'aggiornamento costruisce il messaggio d'invito. Li' `stringResource` non
 * esiste (e' una funzione `@Composable`), quindi quei pezzi ricevono questa
 * interfaccia per iniezione.
 *
 * I segnaposto si scrivono in forma posizionale (`%1$s`, `%2$s`) perche' le
 * traduzioni possono cambiarne l'ordine. I numeri vanno passati gia' formattati
 * come stringa (vedi `DisplayFormat`), non come `Float`: cosi' la formattazione
 * resta in un posto solo e il traduttore non deve sapere nulla dei tipi.
 */
interface Texts {

    /** Stringa [id], con gli argomenti al posto dei segnaposto `%1$s`, `%2$s`, … */
    fun get(@StringRes id: Int, vararg args: Any): String

    /**
     * Numeri e date nella stessa lingua delle stringhe.
     *
     * Sta qui e non in una seconda iniezione apposta: chi compone un testo per
     * l'utente ha bisogno delle due cose insieme, e la lingua e' una sola. Un
     * numero scritto "20.5" dentro una frase italiana sarebbe lo stesso difetto
     * di una parola non tradotta.
     */
    val format: DisplayFormat
}
