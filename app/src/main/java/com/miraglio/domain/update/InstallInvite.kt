package com.miraglio.domain.update

import com.miraglio.R
import com.miraglio.i18n.Texts

/**
 * Invito a installare Miraglio su un altro telefono: il link da inquadrare o da
 * mandare, e il messaggio che lo accompagna.
 *
 * Sta fuori dalla UI perche' e' logica pura: si prova con un test JVM, senza
 * telefono, come il confronto fra versioni. Il messaggio arriva dalle risorse
 * tradotte tramite [Texts], cosi' chi lo riceve lo legge nella propria lingua.
 */
object InstallInvite {

    /**
     * Link pubblico della release piu' recente.
     *
     * Punta alla pagina e non all'APK diretto apposta: sulla pagina c'e' anche
     * la guida di installazione (le due conferme di Android, la calibrazione),
     * e "latest" resta valido a ogni versione — un link al file allegato
     * invecchierebbe a ogni release.
     */
    fun releasePageUrl(repo: String): String = "https://github.com/$repo/releases/latest"

    /**
     * Messaggio da mandare a un altro arciere: cosa fa l'app, dove si prende e
     * le due cose che lo aspetterebbero comunque (Android chiede conferma due
     * volte, e senza calibrazione la distanza puo' sbagliare di molto).
     *
     * Non contiene il numero di versione: il link punta all'ultima release,
     * quindi il testo resta giusto anche fra sei mesi.
     */
    fun message(texts: Texts, repo: String): String = buildString {
        appendLine(texts.get(R.string.install_invite_intro))
        appendLine()
        appendLine(texts.get(R.string.install_invite_open_link))
        appendLine(releasePageUrl(repo))
        appendLine()
        appendLine(texts.get(R.string.install_invite_android_confirms))
        appendLine(texts.get(R.string.install_invite_calibrate))
    }
}
