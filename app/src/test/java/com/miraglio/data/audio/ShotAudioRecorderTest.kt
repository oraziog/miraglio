package com.miraglio.data.audio

import com.miraglio.domain.audio.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

/**
 * La registrazione di debug si prova sul file, non sull'array: quello che
 * arriva all'analisi è un WAV con la sua intestazione, e un'intestazione
 * sbagliata (durata, frequenza, formato) renderebbe il file inutilizzabile
 * anche se i campioni fossero giusti.
 */
class ShotAudioRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun salvaUnWavMono16ConLaSolaFinestraChiesta() {
        val rate = 48000
        val recorder = ShotAudioRecorder(rate, temp.root)
        recorder.start()

        // Dieci secondi: un blocco forte a 5,0 s, il resto silenzio.
        val block = FloatArray(rate / 10)
        for (index in 0 until 100) {
            for (i in block.indices) block[i] = if (index == 50) 0.5f else 0f
            recorder.append(block, block.size)
        }

        val file = requireNotNull(
            recorder.save(
                "prova.wav",
                fromSample = (4.9f * rate).toLong(),
                toSample = (5.2f * rate).toLong()
            )
        )
        val (sampleRate, samples) = Wav.read(file)

        assertEquals(rate, sampleRate)
        // 0,3 s richiesti, con la tolleranza di un campione sui float.
        assertTrue("durata ${samples.size} campioni", abs(samples.size - 14400) <= 2)
        // Il primo campione è ancora silenzio (prima del blocco forte), il
        // massimo dentro la finestra è quello del blocco.
        assertTrue("il taglio non parte da 4,9 s", abs(samples.first()) < 0.001f)
        assertTrue("il blocco forte non è nella finestra", samples.maxOf { abs(it) } > 0.4f)
    }

    /**
     * Con l'ascolto continuo il buffer non può crescere: dopo venti secondi
     * deve contenere gli ultimi dodici, non tutti e venti (altrimenti una
     * sessione lunga arriverebbe al crash per memoria).
     */
    @Test
    fun ilBufferCircolareTieneSoloGliUltimiSecondi() {
        val rate = 48000
        val recorder = ShotAudioRecorder(rate, temp.root)
        recorder.start()

        val block = FloatArray(rate / 10)
        repeat(200) { index ->
            // Un blocco forte ogni secondo, così si riconosce la parte tenuta.
            for (i in block.indices) block[i] = if (index % 10 == 0) 0.5f else 0f
            recorder.append(block, block.size)
        }

        assertEquals(20 * rate.toLong(), recorder.totalSamples)
        val clip = requireNotNull(recorder.clip(fromSample = null, toSample = null, "coda.wav"))
        // Intestazione WAV a parte: dodici secondi di PCM 16 bit mono.
        assertTrue(
            "tenuti ${clip.sizeBytes} byte",
            abs(clip.sizeBytes - (44 + 12 * rate * 2)) <= 4
        )
        val file = requireNotNull(recorder.save("coda.wav"))
        val (sampleRate, samples) = Wav.read(file)
        assertEquals(rate, sampleRate)
        assertTrue("durata ${samples.size} campioni", abs(samples.size - 12 * rate) <= 2)
    }

    @Test
    fun senzaCampioniNonScriveNiente() {
        val recorder = ShotAudioRecorder(48000, temp.root)
        recorder.start()
        assertNull(recorder.save("vuoto.wav"))
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun tieneSoloLeUltimeRegistrazioni() {
        val recorder = ShotAudioRecorder(48000, temp.root, keepFiles = 2)
        val block = FloatArray(480)

        repeat(5) { index ->
            recorder.start()
            recorder.append(block, block.size)
            val file = requireNotNull(recorder.save("prova_$index.wav"))
            // Le date del filesystem possono coincidere: si forza l'ordine.
            file.setLastModified(1_000L + index)
        }

        val rimasti = temp.root.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("prova_3.wav", "prova_4.wav"), rimasti)
    }
}
