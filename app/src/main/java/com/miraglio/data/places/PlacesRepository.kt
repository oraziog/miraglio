package com.miraglio.data.places

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.miraglio.domain.location.GpsFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archivio dei "Luoghi" salvati, interamente offline:
 * - foto in `filesDir/places/<id>.jpg` (JPEG qualita' 90);
 * - indice unico in `filesDir/places/index.json` (org.json, array).
 *
 * Nessun database: l'indice e' riscritto atomicamente (tmp + rename) a ogni
 * modifica; all'avvio le foto orfane vengono rimosse.
 */
@Singleton
class PlacesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _places = MutableStateFlow<List<PlaceRecord>>(emptyList())
    val places: StateFlow<List<PlaceRecord>> = _places.asStateFlow()

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    init {
        scope.launch { reload() }
    }

    /** Nuovo id per un record (chiamare PRIMA di salvare la foto). */
    fun newId(): String = UUID.randomUUID().toString()

    /** Ricarica l'indice da disco e rimuove le foto orfane. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        val records = loadIndex()
        // Confronto sul NOME COMPLETO ("<id>.jpg"): paragonare
        // nameWithoutExtension all'elenco dei photoFile cancellava OGNI
        // foto come "orfana" a ogni avvio dell'app, e l'aggiornamento ha
        // costretto un riavvio: tutte le immagini sono sparite insieme.
        val validFiles = records.mapNotNull { it.photoFile }.toSet()
        dir.listFiles { f ->
            f.isFile && f.extension == "jpg" &&
                f.name !in validFiles && f.nameWithoutExtension !in validFiles
        }?.forEach { it.delete() }
        _places.value = records
    }

    /**
     * File foto del record, se esiste ancora. Prova il nome cosi' com'era
     * nel record e, in ripiego, con l'estensione .jpg: indici scritti da
     * versioni vecchie conservano solo l'id.
     */
    fun photoFile(record: PlaceRecord): File? =
        record.photoFile?.let { name ->
            File(dir, name).takeIf { it.exists() }
                ?: File(dir, if (name.endsWith(".jpg")) name else "$name.jpg")
                    .takeIf { it.exists() }
        }

    /** URI content:// per la condivisione (FileProvider, niente permessi). */
    fun photoUri(record: PlaceRecord): Uri? = photoFile(record)?.let {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }

    /**
     * Salva la foto (se presente) e il record, poi riscrive l'indice.
     * Ritorna il record salvato, o null se la foto non e' scrivibile.
     */
    suspend fun save(kind: PlaceKind, bitmap: Bitmap?, record: PlaceRecord): PlaceRecord? =
        withContext(Dispatchers.IO) {
            var saved = record
            if (bitmap != null) {
                val file = File(dir, "${record.id}.jpg")
                val ok = runCatching {
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "Salvataggio foto luogo fallito", e)
                    false
                }
                if (!ok) return@withContext null
                saved = record.copy(photoFile = file.name)
            }
            val updated = _places.value + saved
            writeIndex(updated)
            _places.value = updated
            saved
        }

    /** Rinomina il luogo (etichetta vuota = nessun cambio). */
    suspend fun rename(id: String, newLabel: String) = withContext(Dispatchers.IO) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty()) return@withContext
        val updated = _places.value.map {
            if (it.id == id) it.copy(label = trimmed) else it
        }
        writeIndex(updated)
        _places.value = updated
    }

    /** Elimina il luogo con la sua foto. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val target = _places.value.firstOrNull { it.id == id }
        target?.let { photoFile(it)?.delete() }
        val updated = _places.value.filterNot { it.id == id }
        writeIndex(updated)
        _places.value = updated
    }

    /** Etichetta predefinita: data/ora leggibile dello scatto. */
    fun defaultLabel(createdAtMs: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(createdAtMs))

    // ------------------------------------------------------------------
    // Serializzazione
    // ------------------------------------------------------------------

    private fun GpsFix.toJson(): JSONObject = JSONObject()
        .put("lat", latitudeDeg)
        .put("lon", longitudeDeg)
        .put("acc", accuracyM.toDouble())
        .put("t", timeMs)
        .putOpt("brg", bearingDeg?.toDouble())

    private fun fixFromJson(o: JSONObject): GpsFix = GpsFix(
        latitudeDeg = o.getDouble("lat"),
        longitudeDeg = o.getDouble("lon"),
        accuracyM = o.getDouble("acc").toFloat(),
        timeMs = o.optLong("t", 0L),
        bearingDeg = o.optDouble("brg", Double.NaN).toFloat().takeIf { !it.isNaN() }
    )

    private fun PlaceRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("created", createdAtMs)
        .put("kind", kind.name)
        .put("label", label)
        .putOpt("photo", photoFile)
        .putOpt("shot", shotFix?.toJson())
        .putOpt("bearing", bearingDeg?.toDouble())
        .putOpt("pitch", pitchDeg?.toDouble())
        .putOpt("dist", distanceM?.toDouble())
        .putOpt("hdist", horizontalDistanceM?.toDouble())
        .put("zoom", zoomRatio.toDouble())
        .putOpt("lens", lensLabel)
        .putOpt("target", targetFix?.toJson())
        .putOpt("size", measuredSizeM?.toDouble())
        .putOpt("start", startFix?.toJson())
        .putOpt("end", endFix?.toJson())
        .putOpt("walked", walkedM?.toDouble())

    private fun recordFromJson(o: JSONObject): PlaceRecord {
        val created = o.optLong("created", 0L)
        fun deg(key: String): Float? =
            o.optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() }
        return PlaceRecord(
            id = o.getString("id"),
            createdAtMs = created,
            kind = runCatching { PlaceKind.valueOf(o.getString("kind")) }
                .getOrDefault(PlaceKind.CAMERA),
            label = o.optString("label").ifEmpty { defaultLabel(created) },
            photoFile = o.optString("photo").takeIf { it.isNotEmpty() },
            shotFix = o.optJSONObject("shot")?.let { fixFromJson(it) },
            bearingDeg = deg("bearing"),
            pitchDeg = deg("pitch"),
            distanceM = deg("dist"),
            horizontalDistanceM = deg("hdist"),
            zoomRatio = o.optDouble("zoom", 1.0).toFloat(),
            lensLabel = o.optString("lens").takeIf { it.isNotEmpty() },
            targetFix = o.optJSONObject("target")?.let { fixFromJson(it) },
            measuredSizeM = deg("size"),
            startFix = o.optJSONObject("start")?.let { fixFromJson(it) },
            endFix = o.optJSONObject("end")?.let { fixFromJson(it) },
            walkedM = deg("walked")
        )
    }

    private fun loadIndex(): List<PlaceRecord> = runCatching {
        val file = File(context.filesDir, INDEX_FILE)
        if (!file.exists()) return@runCatching emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i -> recordFromJson(arr.getJSONObject(i)) }
    }.getOrElse { e ->
        Log.w(TAG, "Lettura indice luoghi fallita", e)
        emptyList()
    }

    private fun writeIndex(records: List<PlaceRecord>) {
        runCatching {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            val file = File(context.filesDir, INDEX_FILE)
            val tmp = File(context.filesDir, "$INDEX_FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                // Ripiego (rename fallisce su alcuni filesystem se dest esiste).
                file.writeText(arr.toString())
                tmp.delete()
            }
        }.onFailure { e ->
            Log.w(TAG, "Scrittura indice luoghi fallita", e)
        }
    }

    companion object {
        private const val TAG = "PlacesRepository"
        private const val DIR_NAME = "places"
        private const val INDEX_FILE = "places/index.json"
        private const val JPEG_QUALITY = 90
    }
}
