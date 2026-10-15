package com.miraglio.data.update

import com.miraglio.BuildConfig
import com.miraglio.domain.update.AppVersion
import com.miraglio.domain.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legge l'ultima release pubblicata su GitHub Releases.
 *
 * Nessuna chiave d'accesso: il repository e' pubblico, e l'API senza
 * autenticazione basta e avanza per una lettura per avvio di app (il limite
 * di GitHub e' di 60 richieste all'ora per indirizzo IP). Un repository
 * privato richiederebbe un token, che finirebbe dentro l'APK e quindi
 * sarebbe leggibile da chiunque: motivo per cui l'aggiornamento automatico
 * e' pensato per un repository pubblico.
 *
 * L'endpoint /releases/latest salta da solo le release in bozza e quelle
 * marcate come preliminari, quindi si aggiorna solo a qualcosa di pubblicato.
 */
@Singleton
class GitHubReleasesClient @Inject constructor() {

    /**
     * Ultima release, `null` se non ce n'e' ancora nessuna: un risultato
     * fallito distingue "non c'e' niente da aggiornare" da "non sono
     * riuscito a chiedere" (niente rete, GitHub irraggiungibile).
     */
    suspend fun fetchLatest(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(latestReleaseUrl()).openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    // GitHub rifiuta le richieste senza User-Agent.
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
            try {
                when (val code = connection.responseCode) {
                    // Repository senza nessuna release pubblicata: non e' un errore.
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    HttpURLConnection.HTTP_OK ->
                        parse(connection.inputStream.bufferedReader().use { it.readText() })
                    else -> error("GitHub ha risposto $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parse(json: String): ReleaseInfo {
        val release = JSONObject(json)
        val tag = release.getString("tag_name")
        val version = AppVersion.parse(tag)
            ?: error("Etichetta di release non riconosciuta: $tag")
        val apk = apkAsset(release.optJSONArray("assets"))
            ?: error("La release $tag non ha nessun APK allegato")
        return ReleaseInfo(
            tagName = tag,
            version = version,
            notes = release.optString("body").trim(),
            apkUrl = apk.url,
            apkSizeBytes = apk.sizeBytes
        )
    }

    /** Primo allegato che e' un APK: e' quello che il flusso di release pubblica. */
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

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "Miraglio-Android/${BuildConfig.VERSION_NAME}"

        fun latestReleaseUrl(): String =
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    }
}
