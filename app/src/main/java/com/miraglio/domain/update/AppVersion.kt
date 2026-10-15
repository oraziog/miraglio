package com.miraglio.domain.update

/**
 * Versione numerica confrontabile, letta da una stringa come "v1.0.2".
 *
 * Il confronto fra testi non funziona: in ordine alfabetico "1.0.10" viene
 * prima di "1.0.9", quindi l'app crederebbe di essere piu' recente della
 * release appena pubblicata e non si aggiornerebbe mai. Qui ogni parte viene
 * letta come numero intero e gli zeri finali vengono tolti leggendo, cosi'
 * "1.1" e "1.1.0" sono esattamente la stessa versione: uguaglianza e
 * ordinamento concordano (con i soli zeri tagliati nel confronto, due
 * versioni equivalenti non sarebbero mai `equals`, e una mappa indicizzata
 * per versione si comporterebbe in modo imprevedibile).
 */
data class AppVersion(private val numbers: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until size) {
            val mine = numbers.getOrElse(i) { 0 }
            val theirs = other.numbers.getOrElse(i) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = numbers.joinToString(".")

    companion object {

        /**
         * Legge la parte numerica di un'etichetta di release: "v1.2.3",
         * "1.2", "v1.2.3-beta.1" (il suffisso non conta per l'ordine, il
         * criterio di GitHub e' la data di pubblicazione).
         *
         * Null se non c'e' nessun numero: un'etichetta come "release-finale"
         * non e' confrontabile, e senza versione e' meglio non proporre
         * niente piuttosto che proporre un aggiornamento a caso.
         */
        fun parse(raw: String): AppVersion? {
            val digits = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
            val numbers = digits.split('.').mapNotNull { it.trim().toIntOrNull() }
            if (numbers.isEmpty()) return null
            return AppVersion(numbers.dropLastWhile { it == 0 }.ifEmpty { listOf(0) })
        }
    }
}
