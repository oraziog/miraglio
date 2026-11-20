package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.tools.ShotAudioProbe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Un tiro vero, con la voce sopra: il caso più difficile che il motore
 * sbagliava, ritagliato da una registrazione di dodici minuti al campo.
 *
 * La finestra va da 140,0 a 146,0 s della registrazione originale (40 tiri a un
 * bersaglio a ~70 m, con il conduttore che parla sopra). Dentro c'è il click a
 * 1,50 s, lo scocco a 1,654 s e il battifreccia 1,442 s dopo. Il riferimento
 * del tiro è 141,65 s.
 *
 * **Perché questo.** È il caso che smascherava la scelta dell'impatto a
 * ampiezza: a 0,58 s dalla corda parte una parola forte 0,081 contro i 0,009
 * del battifreccia, e il motore la sceglieva — Δ 0,58 s, distanza 30 m invece
 * di 70. Adesso l'impatto si sceglie fra i transienti **a banda larga** (un
 * urto eccita anche l'acuto, una voce no), e questo tiro torna a 70 m.
 *
 * Il file è WAV mono 16 bit a 48 kHz, esattamente quello che esce dal telefono
 * a parte il rumore del convertitore: niente segnale sintetico.
 */
class RealRecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun ilTiroVeroConLaVoceSopraTornaASettantaMetri() {
        val file = temp.newFile("tiro_141_65.wav")
        javaClass.getResourceAsStream("/audio/tiro_141_65.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val tiri = ShotAudioProbe.analizzaContinuo(file, useClicker = true, offsetMs = 0f)

        assertEquals("la finestra contiene un solo tiro", 1, tiri.size)
        val tiro = tiri.single()
        assertEquals(
            "lo scocco deve cadere dove l'ha visto chi ha misurato a mano",
            1.654,
            tiro.releaseS,
            0.060
        )
        val tempo = requireNotNull(tiro.deltaS) {
            "il battifreccia è dentro la finestra: il delta deve esserci"
        }
        assertEquals(
            "scocco -> impatto: è qui che la voce vinceva sull'impatto",
            1.442,
            tempo.toDouble(),
            0.020
        )

        val distanza = requireNotNull(
            BallisticCalculator.distance(tempo, ShotAudioProbe.MODELLO, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata dal tiro", 70.0, distanza.toDouble(), 1.5)
    }
}
