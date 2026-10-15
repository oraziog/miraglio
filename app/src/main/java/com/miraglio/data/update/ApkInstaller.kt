package com.miraglio.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consegna l'APK scaricato all'installatore di sistema.
 *
 * L'app non installa niente da sola e non puo' farlo: apre la schermata di
 * conferma del sistema, che mostra i permessi richiesti e lascia l'ultima
 * parola all'utente. Il file viene passato come content:// (FileProvider),
 * quindi non serve nessun permesso sull'archiviazione.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Vero se il sistema accetta installazioni da questa app. Da Android 8 in
     * poi e' un'autorizzazione per applicazione ("installa app sconosciute"),
     * che l'utente concede una volta.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Apre la schermata di sistema dove si concede l'autorizzazione a questa
     * app. Falso se il dispositivo non ha quella schermata (alcune
     * personalizzazioni dei produttori): in quel caso resta la strada di
     * Impostazioni > App > Miraglio.
     */
    fun openInstallPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /** Apre l'installatore sull'APK. Falso se nessuna app puo' gestirlo. */
    fun install(apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    private companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
