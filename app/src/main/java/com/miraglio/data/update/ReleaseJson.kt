package com.miraglio.data.update

import com.miraglio.R
import com.miraglio.domain.update.AppVersion
import com.miraglio.domain.update.ReleaseInfo
import com.miraglio.domain.update.UpdateFailure
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lettura della risposta di GitHub per l'ultima release.
 *
 * E' separata dal client di rete perche' i nomi dei campi sono un contratto
 * con GitHub: se cambiassero, l'app smetterebbe di trovare l'APK e non si
 * aggiornerebbe piu' **senza mostrare nessun errore**. Isolata cosi', la
 * lettura si puo' provare contro una risposta vera (vedi ReleaseJsonTest),
 * senza rete e senza telefono.
 */
internal object ReleaseJson {

    /**
     * @throws IllegalStateException se la risposta non e' utilizzabile: senza
     * etichetta numerica o senza APK allegato non c'e' niente da installare, e
     * l'errore resta nei log invece di installare qualcosa di sbagliato.
     */
    fun releaseFrom(json: String): ReleaseInfo {
        val release = JSONObject(json)
        val tag = release.getString("tag_name")
        val version = AppVersion.parse(tag)
            ?: throw UpdateFailure(R.string.update_release_tag_unknown, listOf(tag))
        val apk = apkAsset(release.optJSONArray("assets"))
            ?: throw UpdateFailure(R.string.update_release_no_apk, listOf(tag))
        return ReleaseInfo(
            tagName = tag,
            version = version,
            // Le note possono essere lunghe (GitHub ci mette l'elenco dei
            // commit): vengono mostrate come testo semplice nel dialog.
            notes = release.optString("body").trim(),
            apkUrl = apk.url,
            apkSizeBytes = apk.sizeBytes
        )
    }

    /**
     * Primo allegato con estensione .apk. Si salta tutto il resto: negli
     * archivi del sorgente GitHub mette tarball e zipball, che nell'elenco
     * non compaiono, ma un allegato come le note di rilascio si'.
     */
    private fun apkAsset(assets: JSONArray?): ApkAsset? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            return ApkAsset(url, asset.optLong("size", 0L))
        }
        return null
    }

    private data class ApkAsset(val url: String, val sizeBytes: Long)
}
