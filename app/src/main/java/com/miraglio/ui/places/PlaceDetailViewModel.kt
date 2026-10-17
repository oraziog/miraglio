package com.miraglio.ui.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.data.places.PlacesRepository
import com.miraglio.data.share.ShareImageComposer
import com.miraglio.domain.location.GpsFix
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.i18n.Texts
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Stato del dettaglio di un luogo. */
data class PlaceDetailState(
    val record: PlaceRecord? = null,
    val thumbnail: Bitmap? = null,
    val shareMessage: String? = null,
    val qrContent: String? = null,
    val shareImageUri: Uri? = null,
    val missingPhoto: Boolean = false,

    /**
     * Nota mentre si scrive: resta in memoria finche' non si salva, cosi'
     * non si riscrive l'indice a ogni tasto e non si perde quello che stava
     * per essere scritto cambiando schermata per sbaglio.
     * [record.notes] e' invece quello che e' sul disco.
     */
    val notesDraft: String = "",
    /** La nota appena salvata: il bottone resta in "Salvato" finche' non si tocca. */
    val notesSaved: Boolean = false
)

@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val repository: PlacesRepository,
    private val texts: Texts
) : ViewModel() {

    private val placeId: String = savedStateHandle.get<String>("id").orEmpty()

    private val _state = MutableStateFlow(PlaceDetailState())
    val state: StateFlow<PlaceDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val record = repository.places.value.firstOrNull { it.id == placeId }
            if (record == null) {
                _state.value = PlaceDetailState()
                return@launch
            }
            val message = shareMessageFor(record)
            val pin = pinFix(record)
            val photoFile = repository.photoFile(record)
            val thumb = photoFile?.let { loadThumbnail(it) }
            val shareUri = photoFile?.let { file ->
                val lines = message?.lineSequence()
                    ?.filter { it.isNotBlank() }?.take(4)?.toList().orEmpty()
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                val composed = if (lines.isEmpty() || decoded == null) null
                else ShareImageComposer.compose(decoded, lines)
                val target = composed ?: decoded
                if (target != null) {
                    runCatching {
                        val dir = File(appContext.cacheDir, "share").apply { mkdirs() }
                        val out = File(dir, "place_share.jpg")
                        out.outputStream().use { o ->
                            target.compress(Bitmap.CompressFormat.JPEG, 90, o)
                        }
                        // Via FileProvider: i file:// espliciti crashano su API 24+.
                        FileProvider.getUriForFile(
                            appContext,
                            "${appContext.packageName}.fileprovider",
                            out
                        )
                    }.getOrNull()
                } else {
                    null
                }
            }
            _state.value = PlaceDetailState(
                record = record,
                notesDraft = record.notes,
                thumbnail = thumb,
                shareMessage = message,
                // Con il pin il QR apre la mappa; senza fix si codifica il
                // messaggio di condivisione, che uno scanner legge lo stesso.
                qrContent = pin?.let { ShareMessageComposer.mapsLink(it) } ?: message,
                shareImageUri = shareUri,
                missingPhoto = record.photoFile != null && photoFile == null
            )
        }
    }

    /** Messaggio di condivisione in base al tipo di luogo. */
    private fun shareMessageFor(record: PlaceRecord): String? {
        return when (record.kind) {
            PlaceKind.CAMERA -> {
                val distance = record.distanceM
                val horizontal = record.horizontalDistanceM
                if (distance == null || horizontal == null) return null
                ShareMessageComposer.cameraPlace(
                    texts = texts,
                    fix = record.shotFix,
                    bearingDeg = record.bearingDeg ?: 0f,
                    distanceM = distance,
                    horizontalDistanceM = horizontal,
                    pitchDeg = record.pitchDeg ?: 0f,
                    measuredSizeM = record.measuredSizeM,
                    target = record.targetFix
                )
            }
            PlaceKind.GPS -> {
                val end = record.endFix ?: return null
                val distance = record.distanceM ?: record.walkedM ?: return null
                // gpsPlace aggiunge l'errore (±) gia' calcolato sui fix:
                // la precisione resta nel messaggio condiviso anche per le
                // misure accettate con errore elevato.
                ShareMessageComposer.gpsPlace(texts, distance, record.startFix, end, record.gpsErrorM)
            }
        }
    }

    /** Fix preferenziale per il pin del QR: bersaglio stimato, altrimenti
     *  arrivo, scatto, partenza. */
    private fun pinFix(record: PlaceRecord): GpsFix? =
        record.targetFix ?: record.endFix ?: record.shotFix ?: record.startFix

    /** Rinomina il luogo. */
    fun rename(newLabel: String) {
        viewModelScope.launch {
            repository.rename(placeId, newLabel)
            _state.value = _state.value.copy(
                record = _state.value.record?.copy(label = newLabel.trim())
            )
        }
    }

    /**
     * Modifica della nota: tocca solo lo stato locale. Il disco lo tocca
     * [saveNotes], quando l'utente lo chiede.
     */
    fun onNotesChange(value: String) {
        _state.value = _state.value.copy(notesDraft = value, notesSaved = false)
    }

    /** Salva la nota sul disco (spazio vuoti ai bordi non vanno salvati). */
    fun saveNotes() {
        val corrente = _state.value
        val trimmed = corrente.notesDraft.trim()
        // Note identiche (o solo diversa di spazi in piu'): non si riscrive
        // l'indice e non si dice "salvato" per una scrittura che non c'e'.
        if (trimmed == corrente.record?.notes) return
        viewModelScope.launch {
            repository.setNotes(placeId, trimmed)
            _state.value = _state.value.copy(
                record = _state.value.record?.copy(notes = trimmed),
                notesDraft = trimmed,
                notesSaved = true
            )
        }
    }

    /** Elimina il luogo e torna alla lista. */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(placeId)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun loadThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val target = 1024
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }
}
