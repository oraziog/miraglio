package com.miraglio.ui.theme

import androidx.compose.ui.graphics.Color

val Green80 = Color(0xFF4CAF50)
val GreenDark = Color(0xFF2E7D32)
val GreenLight = Color(0xFF81C784)
val Orange80 = Color(0xFFFF9800)
val OrangeDark = Color(0xFFE65100)
val Brown80 = Color(0xFF795548)
val BrownDark = Color(0xFF3E2723)
// Quasi-nero caldo: su OrangeDark (#E65100) il rapporto di contrasto e' 4,85:1
// (WCAG AA superato); la Cream resta sotto soglia (3,6:1).
val Espresso = Color(0xFF1C1300)
val Cream = Color(0xFFFFF8E1)
val BackgroundLight = Color(0xFFF5F5F0)

// ---------------------------------------------------------------------------
// Modalita' outdoor: sole battente e passaggi rapido luce/ombra nel bosco.
// Nero puro + giallo fluorescente: il nero riduce la luce emessa dal pannello
// e il giallo su nero e' la coppia a contrasto massimo leggibile (~19,6:1),
// che regge anche guardando lo schermo attraverso occhiali da sole.
// ---------------------------------------------------------------------------
val OutdoorBlack = Color(0xFF000000)
val OutdoorYellow = Color(0xFFFFFF00)
val OutdoorWhite = Color(0xFFFFFFFF)

/** Verde accesso per i fix GPS credibili: il verde scuro normale non regge sul nero. */
val OutdoorGreen = Color(0xFF69F0AE)

/** Arancione di attenzione, piu' luminoso di quello usato su fondo chiaro. */
val OutdoorAmber = Color(0xFFFFC400)

/** Rosso brillante per gli errori. */
val OutdoorRed = Color(0xFFFF5252)
