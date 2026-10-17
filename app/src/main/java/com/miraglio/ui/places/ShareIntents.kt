package com.miraglio.ui.places

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.miraglio.R

/**
 * Intent di condivisione "universale": testo con link Google Maps (tappabile
 * da Android e iPhone) ed eventuale foto JPEG.
 */
object ShareIntents {

    /**
     * Condivide solo testo (misura + link, o l'invito a installare l'app).
     * Il titolo del chooser si puo' cambiare: e' l'unica cosa che distingue
     * una condivisione da un'altra. Senza titolo si usa quello generico
     * tradotto (`Condividi luogo` / `Share place`).
     */
    fun shareText(
        context: Context,
        text: String,
        chooserTitle: String? = null
    ) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val title = chooserTitle ?: context.getString(R.string.action_share_place_title)
        context.startActivity(Intent.createChooser(intent, title))
    }

    /**
     * Condivide foto + testo. Nota: alcune app ignorano EXTRA_TEXT con le
     * immagini; il messaggio resta comunque nel posto dove si salva la foto.
     */
    fun shareImageAndText(context: Context, imageUri: Uri, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.action_share_place_title)
            )
        )
    }

    /** Apre Google Maps (o qualunque navigatore) sul pin. */
    fun navigateTo(context: Context, lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        runCatching { context.startActivity(intent) }
    }
}
