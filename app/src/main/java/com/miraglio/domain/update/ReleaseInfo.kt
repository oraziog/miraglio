package com.miraglio.domain.update

/**
 * Una release pubblicata su GitHub Releases, ridotta a cio' che serve per
 * aggiornare l'app: versione, note di rilascio e l'APK da scaricare.
 */
data class ReleaseInfo(
    val tagName: String,
    val version: AppVersion,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long
) {

    /**
     * Etichetta leggibile per testi e dialog: "v1.0.2" -> "1.0.2". Si mostra
     * l'etichetta della release e non [version], che ha gli zeri finali
     * tolti ("1.0.0" diventerebbe "1").
     */
    val displayVersion: String get() = tagName.removePrefix("v").removePrefix("V")

    /**
     * Vero solo se la release e' piu' recente di quella installata.
     *
     * Con una versione installata illeggibile risponde falso: senza un
     * termine di paragone non si propone l'aggiornamento, perche' altrimenti
     * si rischierebbe di far installare un APK piu' vecchio (Android lo
     * rifiuterebbe come downgrade) o comunque diverso da quello in uso.
     */
    fun isNewerThan(installed: AppVersion?): Boolean =
        installed != null && version > installed
}
