package com.miraglio.data.history

import com.miraglio.data.places.PlaceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Una misura dentro l'archivio automatico delle ultime misure.
 *
 * Non e' un luogo: niente foto, niente etichetta, nessun pulsante "salva" da
 * premere prima che il numero sparisca. Contiene cio' che serve per dire "era
 * quella distanza, con quell'errore, in quel momento".
 *
 * [kind] e' lo stesso enumerativo dei Luoghi perche' le due voci si leggono
 * nella stessa schermata e devono usare le stesse parole.
 */
data class HistoryEntry(
    val id: String,
    val atMs: Long,
    val kind: PlaceKind,
    /** Distanza stimata o percorsa, in metri (sempre, indipendente dall'unita' a schermo). */
    val distanceM: Float,
    /** Errore stimato (solo GPS): i due fix combinati in quadratura. */
    val errorM: Float? = null,
    val latitudeDeg: Float? = null,
    val longitudeDeg: Float? = null
)

/**
 * Il buffer circolare delle ultime misure: piu' recente in testa.
 *
 * Sta fuori dal ViewModel e non tocca Android per due motivi, ed e' qui che
 * si decide il comportamento che l'utente vedra':
 *
 * 1. **Non si moltiplicano gli scatti che si rifiniscono.** Un fermo immagine
 *    che si ricompone piu' volte entro [REPLACE_WINDOW_MS] e' una sola misura
 *    che si affina: ogni prova a creare una riga riempirebbe il buffer dei
 *    numeri dello stesso scatto e spingerebbe fuori le misure vere.
 * 2. **Si taglia.** Oltre [MAX_ENTRIES] la piu' vecchia esce per sempre: e' un
 *    salvagente, non uno storico (per quello ci sono i Luoghi, che hanno foto
 *    e coordinate).
 *
 * E' un oggetto puro: si prova con valori scritti a mano (HistoryBufferTest).
 */
internal object HistoryBuffer {

    /** Quante misure tengono: abbastanza per un pomeriggio di tiro. */
    const val MAX_ENTRIES = 10

    /**
     * Finestra in cui una misura dello stesso tipo **sostituisce** la piu'
     * recente invece di aggiungersi.
     */
    const val REPLACE_WINDOW_MS = 30_000L

    /** Mette [entry] in testa, sostituendo la testa se e' la stessa misura che si affina. */
    fun push(entries: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> {
        val newest = entries.firstOrNull()
        val stessaMisura = newest != null &&
            newest.kind == entry.kind &&
            entry.atMs >= newest.atMs &&
            entry.atMs - newest.atMs <= REPLACE_WINDOW_MS
        val updated = if (stessaMisura) {
            listOf(entry) + entries.drop(1)
        } else {
            listOf(entry) + entries
        }
        return updated.take(MAX_ENTRIES)
    }

    fun toJson(entries: List<HistoryEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("at", entry.atMs)
                    put("kind", entry.kind.name)
                    put("dist", entry.distanceM.toDouble())
                    putOpt("err", entry.errorM?.toDouble())
                    putOpt("lat", entry.latitudeDeg?.toDouble())
                    putOpt("lon", entry.longitudeDeg?.toDouble())
                }
            )
        }
    }.toString()

    /**
     * Legge il buffer da un file scritto da questa stessa app. Un file
     * corrotto o tagliato (scrittura interrotta a meta') **non deve buttare
     * via le misure leggibili**: ogni riga viene accettata o scartata da sola.
     */
    fun fromJson(text: String): List<HistoryEntry> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            runCatching { entryFrom(array.getJSONObject(index)) }.getOrNull()
        }.filter { it.distanceM > 0f && it.distanceM.isFinite() }
    }.getOrElse { emptyList() }

    /** Una riga: qui si decide se quella riga sopravvive, non le altre. */
    private fun entryFrom(o: JSONObject): HistoryEntry = HistoryEntry(
        id = o.getString("id"),
        atMs = o.getLong("at"),
        kind = runCatching { PlaceKind.valueOf(o.getString("kind")) }
            .getOrDefault(PlaceKind.CAMERA),
        distanceM = o.getDouble("dist").toFloat(),
        errorM = o.optDouble("err", Double.NaN).toFloat().takeIf { !it.isNaN() },
        latitudeDeg = o.optDouble("lat", Double.NaN).toFloat().takeIf { !it.isNaN() },
        longitudeDeg = o.optDouble("lon", Double.NaN).toFloat().takeIf { !it.isNaN() }
    )
}
