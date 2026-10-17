package com.miraglio.domain.format

/**
 * Sistema di unita' con cui l'app **mostra** e **chiede** le lunghezze.
 *
 * Non e' la lingua, ed e' per questo che e' una scelta a parte: un inglese che
 * tira in Italia misura in metri, un italiano a un campo americano in iarde.
 * Se le unita' seguissero la lingua, cambiare l'interfaccia riscriverebbe anche
 * i numeri, e chi sceglie l'inglese per abitudine si ritroverebbe le distanze
 * convertite senza averlo chiesto.
 *
 * Dentro l'app **non cambia niente**: distanze, dimensioni e accuratezze
 * restano in metri (o in pixel, per la focale). Il sistema di unita' vive solo
 * ai due confini — il testo che finisce a schermo e il numero che l'utente
 * digita — e li' lo applica [DisplayFormat]. Cosi' i conti, le calibrazioni
 * salvate e i luoghi memorizzati non dipendono da questa scelta: cambiarla non
 * tocca ne' i dati ne' i risultati.
 *
 * [stored] e' il valore scritto nelle preferenze: una stringa e non l'indice
 * dell'enum, perche' riordinare le voci non deve cambiare il significato di
 * quello che e' gia' salvato sui telefoni.
 */
enum class UnitSystem(val stored: String) {

    /** Metri e centimetri: il sistema con cui l'app e' nata. */
    METRIC("metric"),

    /** Iarde per le distanze, piedi per l'incertezza, pollici per le dimensioni. */
    IMPERIAL("imperial");

    companion object {

        /** Il valore di partenza: metrico, cosi' chi aggiorna non trova i numeri cambiati. */
        val DEFAULT = METRIC

        /**
         * Sistema corrispondente a un valore salvato. Un valore assente o
         * sconosciuto (preferenze di una versione che non c'e' piu') vale
         * [DEFAULT]: meglio le unita' di sempre che nessuna unita'.
         */
        fun of(stored: String?): UnitSystem =
            entries.firstOrNull { it.stored == stored } ?: DEFAULT
    }
}
