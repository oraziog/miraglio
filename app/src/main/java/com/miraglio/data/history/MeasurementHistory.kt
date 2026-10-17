package com.miraglio.data.history

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cronologia automatica delle ultime misure.
 *
 * Il problema che risolve: misuri, leggi il numero, e se non premi "salva" il
 * numero perde tutto (si spegne lo schermo, si chiude l'app, si torna
 * indietro). Qui ogni misura **compiuta** viene registrata da sola, e la
 * schermata dei Luoghi la mostra sopra i luoghi salvati.
 *
 * Dove vive: un file piccolo riscritto a ogni misura (`history.json`),
 * quindi sopravvive alla morte del processo — che e' proprio il caso che fa
 * perdere i numeri. E' un **salvagente** con capienza fissa
 * ([HistoryBuffer.MAX_ENTRIES]): lo storico vero resta nei Luoghi, che hanno
 * foto e coordinate e si salvano di proposito.
 *
 * Tutta la logica di rotazione e di scrittura del buffer sta in
 * [HistoryBuffer] (puro, testato); qui ci sono solo disco e flusso.
 */
@Singleton
class MeasurementHistory @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Le ultime misure, la piu' recente in testa. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        scope.launch { _entries.value = readFile() }
    }

    /**
     * Registra una misura. Sospeso e non a fuoco di chiamata: chi misura non
     * deve aspettare un disco scritto, ma il disco va scritto davvero.
     */
    suspend fun record(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val updated = HistoryBuffer.push(_entries.value, entry)
        // push torna la stessa lista quando la misura e' quella che si sta
        // rifinendo: non si riscrive un file per non aver cambiato niente.
        if (updated != _entries.value) {
            _entries.value = updated
            writeFile(updated)
        }
    }

    /** Svuota la cronologia (i Luoghi non si toccano). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        _entries.value = emptyList()
        writeFile(emptyList())
    }

    /** Nuovo id per una voce (come PlacesRepository.newId). */
    fun newId(): String = UUID.randomUUID().toString()

    private fun readFile(): List<HistoryEntry> = runCatching {
        if (!file.exists()) emptyList() else HistoryBuffer.fromJson(file.readText())
    }.getOrElse { e ->
        Log.w(TAG, "Lettura cronologia fallita", e)
        emptyList()
    }

    private fun writeFile(entries: List<HistoryEntry>) {
        runCatching {
            val text = HistoryBuffer.toJson(entries)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            // Stesso ripiego dell'indice dei Luoghi: rename e, se fallisce,
            // riscrittura diretta.
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "Scrittura cronologia fallita", e)
        }
    }

    companion object {
        private const val TAG = "MeasurementHistory"
        private const val FILE_NAME = "history.json"
    }
}
