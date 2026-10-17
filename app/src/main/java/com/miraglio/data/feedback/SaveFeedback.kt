package com.miraglio.data.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conferma aptica dei salvataggi: un tocco breve quando il punto di
 * calibrazione o il luogo sono **davvero** salvati.
 *
 * Serve a chi sta guardando il bersaglio e non lo schermo: senza, l'unica
 * conferma e' il messaggio che compare sul pannello, che si legge dopo. Per
 * questo si chiama solo sull'esito positivo: un ronzio su un salvataggio
 * fallito sarebbe una bugia che si sente con le mani.
 *
 * Il permesso `VIBRATE` e' gia' dichiarato nel manifest (normale, non richiede
 * nessuna autorizzazione a runtime).
 */
@Singleton
class SaveFeedback @Inject constructor(
    @ApplicationContext context: Context
) {

    /**
     * Il vibratore di sistema. Da Android 12 si chiede al `VibratorManager`
     * (un telefono puo' averne piu' d'uno: vale quello predefinito); prima si
     * prendeva il servizio storico. Null su un dispositivo che non ne ha.
     */
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * Un tocco breve. Non fa niente se il telefono non ha un vibratore e, sui
     * sistemi che lo permettono, rispetta l'interruttore di sistema del
     * feedback tattile: l'attributo d'uso e' quello dei tocchi, non una
     * vibrazione di notifica che passa sopra le impostazioni dell'utente.
     */
    @Suppress("DEPRECATION")
    fun saved() {
        val v = vibrator ?: return
        // hasVibrator() e' deprecato da Android 12, ma e' l'unica forma che
        // risponde su tutte le versioni che l'app supporta; sul 12 in su il
        // VibratorManager di sopra restituisce gia' il vibratore predefinito.
        if (!v.hasVibrator()) return

        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Tocco "clic" predefinito del sistema: e' quello che l'utente gia'
            // conosce dagli altri programmi, invece di un ronzio inventato qui.
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(SAVE_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }

    private companion object {
        /** Durata del tocco di conferma: si sente attraverso la custodia, non stordisce. */
        const val SAVE_VIBRATION_MS = 25L
    }
}
