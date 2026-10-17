package com.miraglio.i18n

import androidx.annotation.StringRes
import com.miraglio.domain.format.DisplayFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Texts] sui file di risorse dell'app.
 *
 * Unico posto in cui si chiama `Context.getString`: il resto del codice riceve
 * l'interfaccia, quindi si puo' provare senza un Context (vedi il `Texts` di
 * prova nei test).
 *
 * Le stringhe e il formattatore si chiedono a [AppLanguage] **a ogni chiamata**
 * e non si tengono da parte: questo oggetto vive quanto il processo, e un
 * cambio di lingua (Impostazioni -> Lingua) non lo ricrea. Leggendo la lingua
 * qui dentro, un messaggio composto dopo il cambio nasce gia' nella lingua
 * nuova anche se la ViewModel che lo chiede e' la stessa di prima.
 */
@Singleton
class AndroidTexts @Inject constructor(
    private val language: AppLanguage
) : Texts {

    override fun get(@StringRes id: Int, vararg args: Any): String {
        val context = language.localizedContext()
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }

    override val format: DisplayFormat get() = language.format()
}
