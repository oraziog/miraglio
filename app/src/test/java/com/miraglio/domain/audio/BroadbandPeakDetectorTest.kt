package com.miraglio.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il rilevatore di picchi dell'inviluppo lo+mid, provato su segnali costruiti
 * a mano: qui conta la regola, non l'audio.
 */
class BroadbandPeakDetectorTest {

    private val rate = 48000

    /**
     * Passa [durataS] secondi di due rami campione per campione e restituisce
     * i picchi emessi. [livello] dà l'ampiezza dei due rami per ogni istante.
     */
    private fun rileva(
        durataS: Double = 0.4,
        livello: (Int) -> Pair<Double, Double>
    ): List<AcousticEvent> {
        val detector = BroadbandPeakDetector(rate)
        val eventi = mutableListOf<AcousticEvent>()
        repeat((durataS * rate).toInt()) { i ->
            val (lo, mid) = livello(i)
            detector.process(lo, mid, i.toLong(), eventi)
        }
        return eventi
    }

    private fun intervallo(inizioS: Double, fineS: Double) =
        (inizioS * rate).toInt() until (fineS * rate).toInt()

    /**
     * Due massimi a 15 ms l'uno dall'altro sono la stessa vibrazione: resta il
     * **più forte**, non il primo. Il bug che questo test difende mandava il
     * motore a scegliere un urto debole 45 ms prima dell'impatto vero.
     */
    @Test
    fun fraDueMassimiViciniRestaIlPiuForte() {
        val debole = intervallo(0.100, 0.110)
        val forte = intervallo(0.115, 0.125)
        val eventi = rileva { i ->
            when (i) {
                in debole -> 0.02 to 0.02
                in forte -> 0.06 to 0.06
                else -> 0.0 to 0.0
            }
        }
        assertEquals("deve uscire un solo picco", 1, eventi.size)
        val picco = eventi.single()
        assertEquals(ShotBranch.IMPACT_PEAK, picco.branch)
        assertTrue(
            "il picco deve cadere nel colpo forte (0,115–0,126 s), non in quello debole a 0,105",
            picco.peakIndex in (0.115 * rate).toLong()..(0.126 * rate).toLong()
        )
        assertEquals(0.12f, picco.peakAmplitude, 0.02f)
    }

    @Test
    fun dueUrtiDistantiSonoDuePicchi() {
        val primo = intervallo(0.050, 0.060)
        val secondo = intervallo(0.150, 0.160)
        val eventi = rileva { i ->
            when (i) {
                in primo, in secondo -> 0.05 to 0.05
                else -> 0.0 to 0.0
            }
        }
        assertEquals(2, eventi.size)
        assertTrue(eventi[0].peakIndex < eventi[1].peakIndex)
    }

    /** Una sibilante è media senza grave: non è un urto. */
    @Test
    fun ilRamoMedioDaSoloNonEUnPicco() {
        val eventi = rileva { i ->
            if (i in intervallo(0.100, 0.140)) 0.0 to 0.08 else 0.0 to 0.0
        }
        assertTrue("una sibilante non deve produrre picchi", eventi.isEmpty())
    }

    /** Una vocale è grave senza media: nemmeno lei è un urto. */
    @Test
    fun ilRamoGraveDaSoloNonEUnPicco() {
        val eventi = rileva { i ->
            if (i in intervallo(0.100, 0.140)) 0.08 to 0.0 else 0.0 to 0.0
        }
        assertTrue("una vocale non deve produrre picchi", eventi.isEmpty())
    }

    /** Il pavimento scarta il silenzio digitale, non gli urti veri. */
    @Test
    fun sottoIlPavimentoNonSiEmetteNulla() {
        val eventi = rileva { i ->
            if (i in intervallo(0.100, 0.140)) 0.002 to 0.002 else 0.0 to 0.0
        }
        assertTrue(eventi.isEmpty())
    }
}
