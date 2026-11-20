package com.miraglio.data.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Il microfono del telefono, aperto a 48 kHz in mono e senza elaborazioni.
 *
 * Tre scelte che valgono più di quanto sembri:
 *
 * 1. **`UNPROCESSED` quando c'è.** AGC, riduzione di rumore e cancellazione
 *    dell'eco sono pensate per la voce: alzano il guadagno sui suoni deboli
 *    (cioè sul battifreccia a 70 m), comprimono i transienti (cioè spianano
 *    proprio i picchi che si devono datare) e aggiungono un ritardo che cambia
 *    con il livello del segnale. Le sorgenti "unprocessed" sono l'unico modo
 *    pubblico di chiedere il segnale grezzo: se il telefono le dichiara, si usa
 *    quella. Non serve disattivare gli effetti uno per uno — con `UNPROCESSED`
 *    non vengono applicati.
 * 2. **`PCM_FLOAT` con ripiego a 16 bit.** Il float evita di dover pensare
 *    alla scala, ma non tutti i dispositivi lo accettano a 48 kHz; il ripiego
 *    converte in float subito, così il DSP vede un solo formato.
 * 3. **`READ_BLOCKING`.** La lettura aspetta di avere i campioni invece di
 *    restituire zero: un ciclo che gira a vuoto scalderebbe il telefono e
 *    renderebbe imprevedibile il conteggio dei campioni.
 */
class AudioCaptureSource(
    private val context: Context,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE
) {

    private var record: AudioRecord? = null
    private var pcm16 = false
    private var shortBuffer = ShortArray(0)

    val isOpen: Boolean get() = record != null

    /**
     * Apre il microfono. False se nessuna configurazione funziona (permesso
     * negato, microfono occupato, dispositivo senza ADC a 48 kHz): chi chiama
     * deve dirlo, non aspettare in silenzio un evento che non arriverà.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun open(): Boolean {
        close()

        val source = if (supportsUnprocessed()) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        for (encoding in ENCODINGS) {
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                encoding
            )
            if (minBytes <= 0) continue

            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBytes * 2)
                    .build()
            }.getOrNull() ?: continue

            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                continue
            }

            candidate.startRecording()
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                candidate.release()
                continue
            }

            record = candidate
            pcm16 = encoding == AudioFormat.ENCODING_PCM_16BIT
            return true
        }

        Log.w(TAG, "Nessuna configurazione di registrazione disponibile")
        return false
    }

    /**
     * Legge fino a `into.size` campioni. Ritorna quanti ne ha letti, o un
     * valore <= 0 se il flusso è finito (microfono chiuso, errore del driver).
     */
    fun read(into: FloatArray): Int {
        val active = record ?: return -1
        if (!pcm16) {
            return active.read(into, 0, into.size, AudioRecord.READ_BLOCKING)
        }
        if (shortBuffer.size < into.size) shortBuffer = ShortArray(into.size)
        val count = active.read(shortBuffer, 0, into.size, AudioRecord.READ_BLOCKING)
        if (count > 0) {
            for (i in 0 until count) into[i] = shortBuffer[i] / 32768f
        }
        return count
    }

    fun close() {
        record?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        record = null
        pcm16 = false
    }

    private fun supportsUnprocessed(): Boolean {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ) == "true"
    }

    companion object {
        private const val TAG = "AudioCaptureSource"

        /** La frequenza della pipeline: 48 kHz dà 0,02 ms per campione. */
        const val DEFAULT_SAMPLE_RATE = 48000

        private val ENCODINGS = intArrayOf(
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_16BIT
        )
    }
}
