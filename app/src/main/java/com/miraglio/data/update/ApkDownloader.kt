package com.miraglio.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scarica l'APK di una release nella cache dell'app.
 *
 * La cache (e non i file permanenti) perche' l'APK serve solo al momento
 * dell'installazione: se il sistema deve liberare spazio, puo' cancellarlo
 * senza conseguenze. Un file vecchio viene rimosso prima di ogni nuovo
 * download, cosi' non si accumulano decine di MB per ogni tentativo.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Scarica [url] e restituisce il file. [onProgress] riceve l'avanzamento
     * da 0 a 1, oppure `null` quando il server non dichiara la dimensione
     * (in quel caso la UI mostra un'attesa senza percentuale).
     */
    suspend fun download(url: String, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, APK_NAME)

            try {
                val connection = openFollowingRedirects(url)
                try {
                    // -1 = dimensione non dichiarata: nessuna percentuale.
                    val total = connection.contentLengthLong.takeIf { it > 0L }
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total != null) {
                                    val percent = ((written * 100) / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(written.toFloat() / total)
                                    }
                                } else {
                                    onProgress(null)
                                }
                            }
                            output.flush()
                            // Download troncato: succede se cade la connessione a
                            // meta' file. Meglio accorgersene qui che lasciare al
                            // sistema un APK che non si apre.
                            if (total != null && written != total) {
                                error("Download incompleto ($written di $total byte)")
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            target
        }

    /**
     * Apre la connessione seguendo i reindirizzamenti a mano: l'URL pubblico
     * di un allegato di GitHub risponde 302 verso un altro host, e senza
     * questo salto si scaricherebbe la pagina di reindirizzamento invece
     * dell'APK.
     */
    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (next.isNullOrBlank()) error("Reindirizzamento senza destinazione (HTTP $code)")
                    current = next
                }
                else -> {
                    connection.disconnect()
                    error("Download rifiutato (HTTP $code)")
                }
            }
        }
        error("Troppi reindirizzamenti")
    }

    companion object {
        private const val TAG = "ApkDownloader"
        private const val DIR_NAME = "apk"
        private const val APK_NAME = "miraglio.apk"
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        // Alto di proposito: e' il tempo massimo fra due letture, non il
        // tempo totale del download.
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Miraglio-Android"
    }
}
