package com.miraglio.domain.update

import androidx.annotation.StringRes
import com.miraglio.i18n.Texts

/**
 * Guasto dell'aggiornamento con un messaggio **da tradurre**.
 *
 * La catena che aggiorna l'app (client GitHub, lettura del JSON, downloader) e'
 * codice di dati: non ha un `Context` e non puo' comporre testo tradotto. Il
 * guasto porta quindi l'id della stringa e i suoi argomenti, e il testo nasce
 * dove c'e' chi sa la lingua (l'`UpdateManager`, tramite [Texts]).
 *
 * Estende [IllegalStateException] apposta: prima questi guasti si lanciavano
 * con `error(...)`, che lancia proprio quella, e i test che se ne aspettano il
 * tipo continuano a valere.
 */
class UpdateFailure(
    @StringRes val messageId: Int,
    private val args: List<Any> = emptyList(),
    cause: Throwable? = null
) : IllegalStateException("update failure, string id $messageId", cause) {

    /** Il messaggio nella lingua corrente. */
    fun messageText(texts: Texts): String =
        if (args.isEmpty()) texts.get(messageId)
        else texts.get(messageId, *args.toTypedArray())
}
