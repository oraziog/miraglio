package com.miraglio.data.focal

import android.content.Context
import android.content.SharedPreferences
import com.miraglio.domain.location.GpsFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Risoluzione del flusso di analisi in cui sono espressi i punti salvati
 * senza indicazione esplicita (bitmap ruotato, lato lungo 640: generazione 1).
 */
const val LEGACY_FRAME_LONG_SIDE = 640
/** Lato lungo corrente del flusso di analisi (1280x960). */
const val CURRENT_FRAME_LONG_SIDE = 1280

/**
 * Punto di calibrazione focale: a distanza nota [distanceM], un oggetto di
 * dimensione nota [knownSizeM] occupa [pixels] sullo [axis]
 * ('H' = linee orizzontali, misura altezza -> fy; 'V' = linee verticali -> fx).
 * [frameLongSide] e' il lato lungo del bitmap di analisi in cui valgono i
 * [pixels] (lato lungo = larghezza in orizzontale, altezza in verticale).
 */
data class FocalPoint(
    val id: Long,
    val distanceM: Float,
    val pixels: Float,
    val knownSizeM: Float,
    val axis: String,
    val timestamp: Long,
    val zoom: Float = 1f,
    /** Lato lungo del frame di analisi in cui e' espresso [pixels]. */
    val frameLongSide: Int = LEGACY_FRAME_LONG_SIDE
) {
    /** Focale implicita compensata per lo zoom digitale (f x z). */
    val impliedFocalPx: Float
        get() = if (knownSizeM > 0f && zoom > 0f) {
            distanceM * pixels / (knownSizeM * zoom)
        } else {
            0f
        }
}

/**
 * Focale camera persistente, per-lente. La calibrazione è salvata per ogni
 * obiettivo (id camera), più una "lente principale" (1x). Quando la lente
 * attiva non è calibrata si ripiega sulla focale della lente 1x.
 *
 * Finché non calibrata, i flussi emettono null e il chiamante usa la focale
 * teorica (FOV diagonale 67°).
 */
@Singleton
class FocalCalibrationStore @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val PREFS = "focal_calibration"
        private const val KEY_ACTIVE_LENS = "active_lens"
        private const val KEY_DEFAULT_LENS = "default_lens"
        private const val KEY_MIGRATED = "legacy_migrated"

        // Pin del bersaglio di calibrazione: un solo valore, non per lente —
        // la posizione del bersaglio non dipende dall'obiettivo usato.
        private const val KEY_TARGET_FIX = "target_fix"

        // Chiavi della vecchia calibrazione "unica" (migrate verso la lente 1x).
        private const val KEY_FX = "fx_px"
        private const val KEY_FY = "fy_px"
        private const val KEY_POINTS = "points_json"

        // Prefissi delle chiavi per-lente. Servono anche a CANCELLARE per
        // lente e a CONTARE i punti per lente: entrambe le cose si fanno
        // scorrendo le chiavi salvate, senza tenere un indice a parte che
        // potrebbe disallinearsi dai dati veri.
        private const val FX_PREFIX = "fx_"
        private const val FY_PREFIX = "fy_"
        private const val POINTS_PREFIX = "points_"
        private const val FMM_PREFIX = "fmm_"

        // Focale attesa dopo un'importazione: resta finche' una misura di
        // prova su un bersaglio noto non conferma (o smentisce) i numeri
        // arrivati dall'altro telefono.
        private const val VERIFY_FX_PREFIX = "verify_fx_"
        private const val VERIFY_FY_PREFIX = "verify_fy_"

        private fun fxKey(lens: String) = "$FX_PREFIX$lens"
        private fun fyKey(lens: String) = "$FY_PREFIX$lens"
        private fun pointsKey(lens: String) = "$POINTS_PREFIX$lens"
        private fun fmmKey(lens: String) = "$FMM_PREFIX$lens"
        private fun verifyFxKey(lens: String) = "$VERIFY_FX_PREFIX$lens"
        private fun verifyFyKey(lens: String) = "$VERIFY_FY_PREFIX$lens"

        /** Stima prudente se l'HAL non espone LENS_FOCAL_LENGTH (raro). */
        const val DEFAULT_NOMINAL_FOCAL_MM = 4.3f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activeLens = MutableStateFlow(prefs.getString(KEY_ACTIVE_LENS, null))
    /** Lente attualmente selezionata; null finché non determinata. */
    val activeLens: StateFlow<String?> = _activeLens.asStateFlow()

    private val _defaultLensId = MutableStateFlow(prefs.getString(KEY_DEFAULT_LENS, null))
    /** Lente principale (1x) del dispositivo, nota dopo il primo bind camera. */
    val defaultLensId: StateFlow<String?> = _defaultLensId.asStateFlow()

    private val _focal = MutableStateFlow(computeActiveFocal())
    /** Pair(fxPx?, fyPx?) della lente attiva, con ripiego sulla lente 1x. */
    val focalFlow: StateFlow<Pair<Float?, Float?>> = _focal.asStateFlow()

    private val _points = MutableStateFlow(loadPoints(_activeLens.value))
    /** Punti di calibrazione della lente attiva. */
    val pointsFlow: StateFlow<List<FocalPoint>> = _points.asStateFlow()

    private val _targetFix = MutableStateFlow(loadTargetFix())
    /**
     * Pin del bersaglio di calibrazione: dove si trovava l'oggetto quando
     * l'utente ha premuto "Segna bersaglio" standogli davanti. E' la seconda
     * posizione della distanza GPS che riempie il campo distanza; resta fra
     * due sessioni perche' il bersaglio, di solito, non si muove.
     */
    val targetFix: StateFlow<GpsFix?> = _targetFix.asStateFlow()

    private val _verificationExpected =
        MutableStateFlow(loadVerification())
    /**
     * Focale da verificare per lente (id camera -> (fx, fy)), impostata
     * dall'importazione e cancellata quando la misura di prova la conferma.
     * Un valore assente su un asse significa che il file non aveva punti per
     * quell'asse: non c'e' niente da confrontare.
     */
    val verificationExpected: StateFlow<Map<String, Pair<Float?, Float?>>> =
        _verificationExpected.asStateFlow()

    private val _pointCounts = MutableStateFlow(loadPointCounts())
    /**
     * Quanti punti salvati ha ogni lente (id camera -> numero di punti). Le
     * lenti senza punti non compaiono: chi legge vuole sapere dove sono i
     * punti, non l'elenco degli obiettivi del telefono.
     */
    val pointCountsByLens: StateFlow<Map<String, Int>> = _pointCounts.asStateFlow()

    /**
     * Imposta la lente principale (1x) con la sua focale nominale e migra la
     * vecchia calibrazione unica.
     */
    fun setDefaultLensId(id: String, focalLengthMm: Float? = null) {
        if (_defaultLensId.value != id) {
            prefs.edit().putString(KEY_DEFAULT_LENS, id).apply()
            _defaultLensId.value = id
            migrateLegacyIfNeeded(id, focalLengthMm)
        } else {
            storeLensFocal(id, focalLengthMm)
        }
        if (_activeLens.value == null) {
            setActiveLens(id)
        }
    }

    /** Ricorda la focale nominale (mm) della lente; null = lascia il valore esistente. */
    private fun storeLensFocal(lens: String, focalLengthMm: Float?) {
        if (focalLengthMm == null || focalLengthMm <= 0f) return
        if (prefs.getFloat(fmmKey(lens), 0f) != focalLengthMm) {
            prefs.edit().putFloat(fmmKey(lens), focalLengthMm).apply()
            publish()
        }
    }

    /** Focale nominale (mm) ricordata per la lente; stima se assente. */
    fun nominalFocalMm(lens: String): Float {
        val stored = prefs.getFloat(fmmKey(lens), 0f)
        return if (stored > 0f) stored else DEFAULT_NOMINAL_FOCAL_MM
    }

    /** Seleziona la lente attiva (quella su cui si fotografa e calibra). */
    fun setActiveLens(id: String) {
        if (_activeLens.value == id) return
        prefs.edit().putString(KEY_ACTIVE_LENS, id).apply()
        _activeLens.value = id
        publish()
    }

    /** Segna (o risegna) la posizione del bersaglio di calibrazione. */
    fun setTargetFix(fix: GpsFix) {
        val json = JSONObject()
            .put("lat", fix.latitudeDeg)
            .put("lon", fix.longitudeDeg)
            .put("acc", fix.accuracyM.toDouble())
            .put("ts", fix.timeMs)
            .toString()
        prefs.edit().putString(KEY_TARGET_FIX, json).apply()
        _targetFix.value = fix
    }

    /** Il pin salvato, o null se nessuno ha mai segnato un bersaglio. */
    private fun loadTargetFix(): GpsFix? =
        prefs.getString(KEY_TARGET_FIX, null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                GpsFix(
                    latitudeDeg = o.getDouble("lat"),
                    longitudeDeg = o.getDouble("lon"),
                    accuracyM = o.getDouble("acc").toFloat(),
                    timeMs = o.getLong("ts")
                )
            }.getOrNull()
        }

    fun addPoint(
        distanceM: Float,
        pixels: Float,
        knownSizeM: Float,
        axis: String,
        zoom: Float = 1f,
        frameLongSide: Int = CURRENT_FRAME_LONG_SIDE
    ): FocalPoint {
        val lens = _activeLens.value ?: _defaultLensId.value ?: "0"
        val point = FocalPoint(
            id = System.currentTimeMillis(),
            distanceM = distanceM,
            pixels = pixels,
            knownSizeM = knownSizeM,
            axis = axis,
            timestamp = System.currentTimeMillis(),
            zoom = zoom,
            frameLongSide = frameLongSide
        )
        val updated = loadPoints(lens) + point
        savePoints(lens, updated)
        saveFocal(lens, recomputeFocal(updated))
        publish()
        return point
    }

    fun deletePoint(id: Long) {
        val lens = _activeLens.value ?: return
        val updated = loadPoints(lens).filterNot { it.id == id }
        savePoints(lens, updated)
        saveFocal(lens, recomputeFocal(updated))
        publish()
    }

    /**
     * Cancella i punti della lente attiva e la focale che ne deriva: la lente
     * riparte da zero, come prima della prima calibrazione (per la lente
     * principale la focale torna teorica, per le altre vale il ripiego di
     * [computeActiveFocal]). I punti delle altre lenti non si toccano.
     */
    fun clearPoints() {
        val lens = _activeLens.value ?: return
        prefs.edit().apply {
            remove(pointsKey(lens))
            remove(fxKey(lens))
            remove(fyKey(lens))
            remove(verifyFxKey(lens))
            remove(verifyFyKey(lens))
            apply()
        }
        _verificationExpected.value = _verificationExpected.value - lens
        publish()
    }

    /**
     * Cancella punti e focali di **tutte** le lenti: l'uscita per chi ha
     * calibrazioni sbagliate accumulate su piu' obiettivi e vuole ripartire da
     * zero. Non tocca le focali nominali (`fmm_`), che arrivano dall'HAL e non
     * sono una calibrazione dell'utente.
     */
    fun clearAllPoints() {
        val daCancellare = prefs.all.keys.filter {
            it.startsWith(POINTS_PREFIX) || it.startsWith(FX_PREFIX) ||
                it.startsWith(FY_PREFIX) || it.startsWith(VERIFY_FX_PREFIX) ||
                it.startsWith(VERIFY_FY_PREFIX)
        }
        prefs.edit().apply {
            daCancellare.forEach { remove(it) }
            apply()
        }
        _verificationExpected.value = emptyMap()
        publish()
    }

    /**
     * Punti di **tutte** le lenti (id camera -> punti), per l'esportazione.
     * Le lenti senza punti non compaiono, come nel conteggio per la card.
     */
    fun allPointsByLens(): Map<String, List<FocalPoint>> =
        prefs.all.keys
            .filter { it.startsWith(POINTS_PREFIX) && it != KEY_POINTS }
            .associate { key ->
                key.removePrefix(POINTS_PREFIX) to
                    parsePoints(prefs.getString(key, null).orEmpty())
            }
            .filterValues { it.isNotEmpty() }

    /**
     * Scrive una calibrazione importata da un altro telefono: per ogni lente
     * (id **locale**) i punti del file, e la focale che ne deriva.
     *
     * Sostituisce quello che c'era su quella lente invece di aggiungersi: una
     * calibrazione importata e' una calibrazione nuova, e farne la media con la
     * vecchia darebbe il numero di mezzo fra due telefoni diversi — cioe' un
     * valore che non vale per nessuno dei due.
     */
    fun replaceLenses(pointsByLens: Map<String, List<FocalPoint>>) {
        pointsByLens.forEach { (lens, punti) ->
            if (punti.isEmpty()) return@forEach
            savePoints(lens, punti)
            val focal = recomputeFocal(punti)
            saveFocal(lens, focal)
            // La focale appena scritta diventa quella "attesa": la prima
            // misura di prova su un bersaglio noto la confronta col numero
            // arrivato dall'altro telefono.
            saveVerification(lens, focal)
        }
        _verificationExpected.value = loadVerification()
        publish()
    }

    /** Focale attesa per la lente, o null se non c'e' una verifica in sospeso. */
    fun verificationFor(lens: String?): Pair<Float?, Float?>? =
        lens?.let { _verificationExpected.value[it] }

    /**
     * La verifica e' conclusa (superata): si smette di chiedere la misura di
     * prova. Se non lo e', il valore resta e la schermata continua a dirlo.
     */
    fun clearVerification(lens: String?) {
        if (lens == null) return
        prefs.edit().apply {
            remove(verifyFxKey(lens))
            remove(verifyFyKey(lens))
            apply()
        }
        _verificationExpected.value = _verificationExpected.value - lens
    }

    private fun saveVerification(lens: String, focal: Pair<Float?, Float?>) {
        val (fx, fy) = focal
        prefs.edit().apply {
            if (fx != null && fx > 0f) putFloat(verifyFxKey(lens), fx) else remove(verifyFxKey(lens))
            if (fy != null && fy > 0f) putFloat(verifyFyKey(lens), fy) else remove(verifyFyKey(lens))
            apply()
        }
    }

    private fun loadVerification(): Map<String, Pair<Float?, Float?>> {
        val perLente = mutableMapOf<String, Pair<Float?, Float?>>()
        prefs.all.forEach { (key, value) ->
            when {
                key.startsWith(VERIFY_FX_PREFIX) -> {
                    val lens = key.removePrefix(VERIFY_FX_PREFIX)
                    val fx = (value as? Float)?.takeIf { it > 0f }
                    perLente[lens] = Pair(fx, perLente[lens]?.second)
                }
                key.startsWith(VERIFY_FY_PREFIX) -> {
                    val lens = key.removePrefix(VERIFY_FY_PREFIX)
                    val fy = (value as? Float)?.takeIf { it > 0f }
                    perLente[lens] = Pair(perLente[lens]?.first, fy)
                }
            }
        }
        return perLente
    }

    /**
     * Punti salvati di ogni lente, letti dalle chiavi su disco.
     *
     * La chiave della vecchia calibrazione "unica" si chiama `points_json` e
     * comincia come le altre: senza l'esclusione esplicita comparirebbe una
     * lente che si chiama "json".
     */
    private fun loadPointCounts(): Map<String, Int> =
        prefs.all.keys
            .filter { it.startsWith(POINTS_PREFIX) && it != KEY_POINTS }
            .associate { key ->
                val punti = parsePoints(prefs.getString(key, null).orEmpty())
                key.removePrefix(POINTS_PREFIX) to punti.size
            }
            .filterValues { it > 0 }

    private fun publish() {
        _focal.value = computeActiveFocal()
        _points.value = loadPoints(_activeLens.value)
        _pointCounts.value = loadPointCounts()
    }

    /**
     * Rapporto di focale nominale tra [lens] e la lente principale: ~1 per la
     * principale, ~2-3 per un tele, ~0.5-0.6 per un ultra-grandangolo.
     * Se una delle due focali e' sconosciuta si assume 1 (comportamento
     * precedente, nessun miglioramento ma nessun peggioramento).
     */
    fun lensFactors(lens: String?): Pair<Float, Float> {
        val def = _defaultLensId.value
        if (lens == null || def == null || lens == def) return Pair(1f, 1f)
        val focalLens = prefs.getFloat(fmmKey(lens), 0f)
        val focalDef = prefs.getFloat(fmmKey(def), 0f)
        if (focalLens <= 0f || focalDef <= 0f) return Pair(1f, 1f)
        val ratio = focalLens / focalDef
        return Pair(ratio, ratio)
    }

    /**
     * Focale della lente attiva per il flusso di analisi corrente.
     * Per gli assi NON calibrati NON si riutilizza
     * la calibrazione della lente 1x (sarebbe sbagliato di ~2-3x con un tele):
     * si parte dalla focale teorica scalata del rapporto di focale nominale
     * (LENS_FOCAL_LENGTH) tra la lente attiva e la principale.
     */
    private fun computeActiveFocal(): Pair<Float?, Float?> {
        val active = _activeLens.value
        val own = if (active != null) loadFocal(active) else Pair(null, null)
        if (own.first != null && own.second != null) return own

        val (fxFactor, fyFactor) = lensFactors(active)
        val def = _defaultLensId.value
        val defFx = def?.let { loadFocal(it).first }
        val defFy = def?.let { loadFocal(it).second }

        val fx = own.first ?: defFx?.let { it * fxFactor }
        val fy = own.second ?: defFy?.let { it * fyFactor }
        return Pair(fx, fy)
    }

    /**
     * Focale (fx, fy) della lente attiva per un flusso di analisi di lato lungo
     * [frameLongSide]. I valori salvati sono espressi in pixel a 640 (o alla
     * risoluzione memorizzata nei punti); qui vengono riscalati al volo, senza
     * riscrivere le preferenze (l'utente puo' tornare a build precedenti).
     */
    fun focalForFrame(frameLongSide: Int): Pair<Float?, Float?> {
        val (fx, fy) = computeActiveFocal()
        val scale = resolutionScale(frameLongSide)
        return Pair(
            fx?.let { it * scale },
            fy?.let { it * scale }
        )
    }

    /**
     * Punti di calibrazione della lente attiva, riscalati a [frameLongSide]:
     * i pixel dei punti vecchi (640) vengono moltiplicati per il fattore di
     * risoluzione; i punti nuovi portano gia' il proprio frameLongSide.
     */
    fun scaledPointsForFrame(frameLongSide: Int): List<FocalPoint> {
        val scale = resolutionScale(frameLongSide)
        if (scale == 1f) return _points.value
        return _points.value.map { it.copy(pixels = it.pixels * scale) }
    }

    /** Fattore di conversione dai pixel salvati ai pixel di [frameLongSide]. */
    private fun resolutionScale(frameLongSide: Int): Float {
        if (frameLongSide <= 0) return 1f
        // Se almeno un punto dichiara la risoluzione del frame, quella e' la
        // base dei valori salvati; altrimenti e' la generazione 1 (640).
        val stored = _points.value.firstOrNull()?.frameLongSide
            ?: LEGACY_FRAME_LONG_SIDE
        return frameLongSide.toFloat() / stored.toFloat()
    }

    private fun recomputeFocal(points: List<FocalPoint>): Pair<Float?, Float?> {
        val fxPoints = points.filter { it.axis == "V" && it.impliedFocalPx > 0f }
        val fyPoints = points.filter { it.axis == "H" && it.impliedFocalPx > 0f }
        val fx = fxPoints.map { it.impliedFocalPx }.average().takeIf { fxPoints.isNotEmpty() }?.toFloat()
        val fy = fyPoints.map { it.impliedFocalPx }.average().takeIf { fyPoints.isNotEmpty() }?.toFloat()
        return Pair(fx, fy)
    }

    private fun loadFocal(lens: String): Pair<Float?, Float?> {
        val fx = if (prefs.contains(fxKey(lens))) prefs.getFloat(fxKey(lens), 0f) else null
        val fy = if (prefs.contains(fyKey(lens))) prefs.getFloat(fyKey(lens), 0f) else null
        return Pair(fx, fy)
    }

    private fun saveFocal(lens: String, focal: Pair<Float?, Float?>) {
        val (fx, fy) = focal
        prefs.edit().apply {
            if (fx != null && fx > 0f) putFloat(fxKey(lens), fx) else remove(fxKey(lens))
            if (fy != null && fy > 0f) putFloat(fyKey(lens), fy) else remove(fyKey(lens))
            apply()
        }
    }

    private fun loadPoints(lens: String?): List<FocalPoint> {
        if (lens == null) return emptyList()
        val raw = prefs.getString(pointsKey(lens), null) ?: return emptyList()
        return parsePoints(raw)
    }

    private fun savePoints(lens: String, points: List<FocalPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("d", p.distanceM.toDouble())
                    .put("px", p.pixels.toDouble())
                    .put("size", p.knownSizeM.toDouble())
                    .put("axis", p.axis)
                    .put("ts", p.timestamp)
                    .put("zoom", p.zoom.toDouble())
                    .put("fls", p.frameLongSide)
            )
        }
        prefs.edit().putString(pointsKey(lens), arr.toString()).apply()
    }

    private fun parsePoints(raw: String): List<FocalPoint> = runCatching {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            FocalPoint(
                id = o.getLong("id"),
                distanceM = o.getDouble("d").toFloat(),
                pixels = o.getDouble("px").toFloat(),
                knownSizeM = o.getDouble("size").toFloat(),
                axis = o.optString("axis", "H"),
                timestamp = o.optLong("ts", 0L),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                frameLongSide = o.optInt("fls", LEGACY_FRAME_LONG_SIDE)
            )
        }
    }.getOrDefault(emptyList())

    /** Migra la vecchia calibrazione "unica" (pre-lenti) verso la lente 1x. */
    private fun migrateLegacyIfNeeded(defaultLens: String, focalLengthMm: Float?) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            storeLensFocal(defaultLens, focalLengthMm)
            return
        }
        val legacyPoints = prefs.getString(KEY_POINTS, null)
        val legacyFx = if (prefs.contains(KEY_FX)) prefs.getFloat(KEY_FX, 0f) else null
        val legacyFy = if (prefs.contains(KEY_FY)) prefs.getFloat(KEY_FY, 0f) else null
        prefs.edit().apply {
            if (legacyFx != null && legacyFx > 0f) putFloat(fxKey(defaultLens), legacyFx)
            if (legacyFy != null && legacyFy > 0f) putFloat(fyKey(defaultLens), legacyFy)
            if (!legacyPoints.isNullOrEmpty()) putString(pointsKey(defaultLens), legacyPoints)
            remove(KEY_FX)
            remove(KEY_FY)
            remove(KEY_POINTS)
            putBoolean(KEY_MIGRATED, true)
            apply()
        }
        storeLensFocal(defaultLens, focalLengthMm)
        publish()
    }
}