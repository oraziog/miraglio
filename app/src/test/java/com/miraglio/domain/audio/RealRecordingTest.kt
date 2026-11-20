package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.tools.ShotAudioProbe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Un tiro vero, con il parlato sopra: il caso più difficile trovato sul campo.
 *
 * La finestra è ritagliata da una registrazione di dodici minuti girata al
 * campo (40 tiri a un bersaglio a ~70 m, con la voce del conduttore sopra).
 * Il pezzo va da 158,0 a 164,0 s della registrazione originale: dentro c'è il
 * click del clicker a 2,53 s, lo scocco a 2,675 s e il suono dell'impatto
 * 1,441 s dopo. Il riferimento del tiro è 160,68 s.
 *
 * Questo caso è stato scelto perché è uno di quelli che il motore sbagliava
 * prima delle correzioni: mentre aspettava l'impatto restava sordo al tiro
 * successivo, e con il parlato sopra il motore si agganciava a una frase e non
 * sentiva più la corda.
 *
 * Il file è un WAV mono 16 bit a 48 kHz, esattamente quello che esce dal
 * telefono (a parte il rumore del convertitore): niente segnale sintetico.
 */
class RealRecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun ilTiroVeroConIlParlatoSopraTornaASettantaMetri() {
        val file = temp.newFile("tiro_160_68.wav")
        javaClass.getResourceAsStream("/audio/tiro_160_68.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val tiri = ShotAudioProbe.analizzaContinuo(file, useClicker = true, offsetMs = 0f)

        assertEquals("la finestra contiene un solo tiro", 1, tiri.size)
        val tiro = tiri.single()
        assertEquals(
            "lo scocco deve cadere dove l'ha visto chi ha misurato a mano",
            2.675,
            tiro.releaseS,
            0.060
        )
        val tempo = requireNotNull(tiro.deltaS) {
            "il battifreccia è dentro la finestra: il delta deve esserci"
        }
        assertEquals("scocco -> impatto", 1.441, tempo.toDouble(), 0.020)

        val distanza = requireNotNull(
            BallisticCalculator.distance(tempo, ShotAudioProbe.MODELLO, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata dal tiro", 70.0, distanza.toDouble(), 1.5)
    }
}
