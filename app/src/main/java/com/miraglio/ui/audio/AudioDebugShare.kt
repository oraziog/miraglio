package com.miraglio.ui.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.miraglio.R
import java.io.File

/**
 * Condivide un WAV come allegato audio.
 *
 * Il file sta nelle cartelle private dell'app (`filesDir/debug_audio`, oppure
 * la cache per il tiro appena misurato), che non sono raggiungibili dalle altre
 * app: passa da [FileProvider] come `content://` e chi riceve l'intent ha il
 * permesso di lettura solo su quell'URI. È lo stesso meccanismo delle foto dei
 * luoghi e del QR.
 */
object AudioDebugShare {

    fun share(context: Context, file: File): Boolean = runCatching {
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.audio_debug_audio_share_title)
            )
        )
        true
    }.getOrDefault(false)
}
