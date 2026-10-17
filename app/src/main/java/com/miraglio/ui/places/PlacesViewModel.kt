package com.miraglio.ui.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.history.HistoryEntry
import com.miraglio.data.history.MeasurementHistory
import com.miraglio.data.places.PlaceRecord
import com.miraglio.data.places.PlacesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PlacesRepository,
    private val measurementHistory: MeasurementHistory
) : ViewModel() {

    val places: StateFlow<List<PlaceRecord>> = repository.places

    /**
     * Ultime misure, registrate da sole e senza premere "salva": stanno qui
     * perche' e' la schermata dove si guarda gia' quello che si e' misurato.
     */
    val history: StateFlow<List<HistoryEntry>> = measurementHistory.entries

    /** Svuota la cronologia. I Luoghi salvati non vengono toccati. */
    fun clearHistory() {
        viewModelScope.launch { measurementHistory.clear() }
    }

    /** Miniatura per la lista (decodifica con inSampleSize). */
    suspend fun thumbnail(record: PlaceRecord, targetPx: Int = 128): Bitmap? {
        val file = repository.photoFile(record) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
