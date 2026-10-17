package com.miraglio.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La calibrazione precompilata vive tutta nella rotta: se un argomento non
 * combacia col modello, il pulsante "Rifai 5 m" apre la schermata vuota e
 * sembra rotto. Qui si controllano entrambe le cose che possono sbagliare:
 * la forma della rotta e i valori che ci finiscono dentro.
 */
class FocalCaptureRouteTest {

    /** Senza prefill la schermata e' quella di sempre: campi vuoti. */
    @Test
    fun SenzaPrefillLaRottaNonPortaValori() {
        assertEquals(
            "focal_capture?distance=&size=&axis=",
            Screen.FocalCapture.route()
        )
    }

    /** Dal grafico: distanza, dimensione del bersaglio e asse del punto. */
    @Test
    fun LaRottaPortaLaMisuraDaRifare() {
        assertEquals(
            "focal_capture?distance=5.0&size=1.22&axis=H",
            Screen.FocalCapture.route(5f, 1.22f, "H")
        )
    }

    /** I nomi degli argomenti devono essere quelli del modello di rotta. */
    @Test
    fun IlModelloDellaRottaUsaGliStessiNomiDegliArgomenti() {
        val route = Screen.FocalCapture.route
        listOf(
            Screen.FocalCapture.ARG_DISTANCE,
            Screen.FocalCapture.ARG_SIZE,
            Screen.FocalCapture.ARG_AXIS
        ).forEach { nome ->
            assertTrue(
                "l'argomento '$nome' non compare nel modello '$route'",
                route.contains("$nome={$nome}")
            )
        }
    }
}
