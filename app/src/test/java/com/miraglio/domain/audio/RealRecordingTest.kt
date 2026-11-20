package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.tools.ShotAudioProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Il tiro 18 della stessa registrazione (finestra 234,0–240,0 s), il caso in
     * cui la scelta dell'impatto produceva la distanza più assurda: **33,6 m**.
     *
     * Il candidato colpevole è una sillaba a 0,652 s dalla corda, che è larga di
     * banda quanto un urto; a 33 m però non c'è nessun bersaglio, e il tempo di
     * volo lo dice. Il veto di plausibilità ([BallisticCalculator.
     * PLAUSIBLE_TOTAL_TIME_S]) la scarta e l'impatto torna a 1,435 s, cioè 69,7 m.
     */
    @Test
    fun ilTiroVeroConLaDistanzaAssurdaTornaASettantaMetri() {
        val file = temp.newFile("tiro_235_95.wav")
        javaClass.getResourceAsStream("/audio/tiro_235_95.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val tiri = ShotAudioProbe.analizzaContinuo(file, useClicker = true, offsetMs = 0f)

        assertEquals("la finestra contiene un solo tiro", 1, tiri.size)
        val tiro = tiri.single()
        assertEquals("lo scocco", 1.963, tiro.releaseS, 0.060)
        val tempo = requireNotNull(tiro.deltaS) { "il battifreccia deve esserci" }
        assertEquals(
            "scocco -> impatto: la sillaba a 0,65 s non deve vincere",
            1.435,
            tempo.toDouble(),
            0.020
        )
        assertTrue(
            "la misura deve stare dentro la banda fisicamente possibile",
            tempo in BallisticCalculator.PLAUSIBLE_TOTAL_TIME_S
        )

        val distanza = requireNotNull(
            BallisticCalculator.distance(tempo, ShotAudioProbe.MODELLO, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata dal tiro", 70.0, distanza.toDouble(), 1.5)
    }

    /**
     * Il tiro 3 della registrazione (finestra 24,6–27,6 s), il caso in cui una
     * frase prende il posto del click.
     *
     * La registrazione, in quel punto, ha una sillaba a 24,802 s (0,006 di
     * acuto), il click del clicker a 25,161 (0,405) e lo scocco a 25,320
     * (0,320). Prendendo per click la sillaba, la finestra dei 450 ms si chiude
     * prima dello scocco e il click vero viene scelto come rilascio: il tiro
     * misura 1,597 s e lo scocco risulta 149 ms prima del vero.
     *
     * Con la riancoratura ([ShotTimingMachine.ANCORA_CLICK_FACTOR]) il click
     * vero, 67 volte più forte della sillaba, riapre la finestra e lo scocco
     * torna al suo posto: Δ 1,438 s, cioè 69,8 m.
     */
    @Test
    fun laFraseNonPrendeIlPostoDelClick() {
        val file = temp.newFile("tiro_25_31.wav")
        javaClass.getResourceAsStream("/audio/tiro_25_31.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val tiri = ShotAudioProbe.analizzaContinuo(file, useClicker = true, offsetMs = 0f)

        assertEquals("la finestra contiene un solo tiro", 1, tiri.size)
        val tiro = tiri.single()
        assertEquals(
            "lo scocco deve cadere dove l'ha visto chi ha misurato a mano",
            0.720,
            tiro.releaseS,
            0.060
        )
        val tempo = requireNotNull(tiro.deltaS) { "il battifreccia deve esserci" }
        assertEquals("scocco -> impatto, non click -> impatto", 1.438, tempo.toDouble(), 0.020)

        val distanza = requireNotNull(
            BallisticCalculator.distance(tempo, ShotAudioProbe.MODELLO, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata dal tiro", 70.0, distanza.toDouble(), 1.5)
    }

    /**
     * Il tiro 20 della registrazione (finestra 356,0–359,4 s), la sessione
     * fantasma che non si riapriva.
     *
     * Una frase a 356,428 s (0,005) viene presa per click e la sua finestra
     * sceglie come rilascio un'altra sillaba a 356,571 (0,012). Il tiro vero è
     * però lì: click a 356,899 (0,251, cinquanta volte la frase) e scocco a
     * 357,049 (0,733). Prima della riancoratura il motore restava agganciato
     * alla frase e misurava dal click sbagliato; adesso il click vero riapre la
     * finestra e lo scocco è quello giusto: Δ 1,437 s, 69,8 m.
     */
    @Test
    fun laSessioneFantasmaSiRiapreSulClickVero() {
        val file = temp.newFile("tiro_357_05.wav")
        javaClass.getResourceAsStream("/audio/tiro_357_05.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val tiri = ShotAudioProbe.analizzaContinuo(file, useClicker = true, offsetMs = 0f)

        assertEquals("la finestra contiene un solo tiro", 1, tiri.size)
        val tiro = tiri.single()
        assertEquals("lo scocco", 1.049, tiro.releaseS, 0.060)
        val tempo = requireNotNull(tiro.deltaS) { "il battifreccia deve esserci" }
        assertEquals("scocco -> impatto", 1.437, tempo.toDouble(), 0.020)

        val distanza = requireNotNull(
            BallisticCalculator.distance(tempo, ShotAudioProbe.MODELLO, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata dal tiro", 70.0, distanza.toDouble(), 1.5)
    }
}
