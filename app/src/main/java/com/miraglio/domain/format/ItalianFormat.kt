package com.miraglio.domain.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Come si scrivono i numeri e le date a schermo: separatore decimale la
 * virgola, date nel formato gg/mm/aaaa, con Roma come riferimento
 * (fuso Europe/Rome) e indipendentemente dalla lingua del telefono.
 *
 * Restano col punto decimale solo le coordinate e i link alle mappe: sono
 * dati da incollare in altre app, non valori da leggere a schermo.
 */
object ItalianFormat {

    /** Fuso di riferimento: l'ora mostrata non cambia col telefono. */
    private val ROME: ZoneId = ZoneId.of("Europe/Rome")

    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALY)

    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALY)

    /** Numero con [decimals] decimali e virgola: "20,50". */
    fun number(value: Number, decimals: Int): String =
        String.format(Locale.ITALY, "%.${decimals}f", value.toDouble())

    /** Misura in metri: "20,5 m" (arrotondata a [decimals] decimali). */
    fun meters(value: Number, decimals: Int = 1): String = "${number(value, decimals)} m"

    /** Numero con il segno anche quando e' positivo: "+1,2" / "-0,4". */
    fun signed(value: Number, decimals: Int = 1): String =
        if (value.toDouble() >= 0.0) "+${number(value, decimals)}" else number(value, decimals)

    /** Data e ora di Roma nel formato gg/mm/aaaa hh:mm. */
    fun dateTime(epochMs: Long): String =
        DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ROME))

    /** Solo data, gg/mm/aaaa. */
    fun date(epochMs: Long): String =
        DATE.format(Instant.ofEpochMilli(epochMs).atZone(ROME))
}
