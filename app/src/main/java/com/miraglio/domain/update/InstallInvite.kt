package com.miraglio.domain.update

/**
 * Invito a installare Miraglio su un altro telefono: il link da inquadrare o da
 * mandare, e il messaggio che lo accompagna.
 *
 * Sta fuori dalla UI perche' e' logica pura: si prova con un test JVM, senza
 * telefono, come il confronto fra versioni.
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
    fun message(repo: String): String = buildString {
        appendLine("🏹 Prova Miraglio: stima la distanza del bersaglio con la " +
            "fotocamera del telefono.")
        appendLine()
        appendLine("Apri questo link dal telefono Android e installala:")
        appendLine(releasePageUrl(repo))
        appendLine()
        appendLine("Android chiede due conferme — autorizza «installa app " +
            "sconosciute» e poi premi Installa. È normale: Miraglio non è sul " +
            "Play Store.")
        appendLine("Al primo avvio fai la calibrazione, altrimenti la distanza " +
            "può sbagliare anche del 30%.")
    }
}
