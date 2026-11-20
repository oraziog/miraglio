package com.miraglio.domain.audio

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * WAV PCM 16 bit mono, il minimo che serve all'harness di test.
 *
 * Il DSP si prova su un file vero e non su un array in memoria apposta: un WAV
 * passa per la quantizzazione a 16 bit e per l'intestazione, quindi il test
 * vede lo stesso segnale che arriverebbe dal telefono (a meno del rumore del
 * convertitore) invece di un segnale ideale che nessun dispositivo produrrà.
 */
internal object Wav {

    fun write(file: File, sampleRateHz: Int, samples: FloatArray) {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(sampleRateHz)
        le32(sampleRateHz * 2) // byte al secondo
        le16(2) // allineamento
        le16(16) // bit per campione
        ascii("data")
        le32(dataBytes)

        samples.forEach { sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            le16(value and 0xFFFF)
        }

        file.writeBytes(out.toByteArray())
    }

    /** Ritorna (sampleRate, campioni normalizzati in [-1, 1]). */
    fun read(file: File): Pair<Int, FloatArray> {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV troppo corto: ${bytes.size} byte" }

        fun le32(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        fun le16(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "non è un RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "non è un WAVE" }
        require(le16(20) == 1) { "atteso PCM" }
        require(le16(22) == 1) { "atteso mono" }
        require(le16(34) == 16) { "attesi 16 bit" }

        val sampleRate = le32(24)
        val dataBytes = le32(40)
        val count = dataBytes / 2
        val samples = FloatArray(count)
        for (i in 0 until count) {
            samples[i] = le16(44 + i * 2).toShort() / 32768f
        }
        return sampleRate to samples
    }
}
