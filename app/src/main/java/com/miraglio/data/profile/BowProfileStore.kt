package com.miraglio.data.profile

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archivio dei profili arco, salvato in `SharedPreferences` con `org.json`
 * come la calibrazione focale ([com.miraglio.data.focal.FocalCalibrationStore])
 * e le impostazioni ([com.miraglio.data.settings.AppSettingsStore]).
 *
 * Un profilo con tutti i suoi tiri pesa qualche kilobyte: un file su disco
 * (come l'indice dei luoghi) servirebbe solo a portarsi dietro un secondo
 * meccanismo di scrittura atomica per niente, e un database sarebbe una
 * dipendenza nuova per un elenco che l'arciere conta sulle dita di una mano.
 *
 * Tutto l'elenco sta sotto una chiave sola: i profili sono una lista, e
 * scriverli come chiavi separate (come le lenti della focale) renderebbe
 * l'ordine una cosa da ricostruire a ogni lettura.
 */
@Singleton
class BowProfileStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private companion object {
        const val PREFS = "bow_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<BowProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    private val _activeProfile = MutableStateFlow(
        _profiles.value.firstOrNull { it.id == _activeId.value }
    )
    val activeProfile: StateFlow<BowProfile?> = _activeProfile.asStateFlow()

    /**
     * Crea un profilo e lo rende attivo se non ce n'era nessuno: chi apre la
     * schermata per la prima volta si trova già dentro il profilo che ha
     * appena scritto, invece di doverlo selezionare.
     */
    fun addProfile(
        nickname: String,
        useClicker: Boolean = false,
        releaseOffsetMs: Float = 0f
    ): BowProfile {
        val profile = BowProfile(
            id = UUID.randomUUID().toString(),
            nickname = nickname.trim(),
            useClicker = useClicker,
            releaseOffsetMs = releaseOffsetMs,
            createdMs = System.currentTimeMillis()
        )
        write(_profiles.value + profile)
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun setActive(id: String) {
        if (_activeId.value == id) return
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
        publishActive()
    }

    /** Rinomina il profilo (un nickname vuoto non è un nome: si ignora). */
    fun rename(id: String, nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        update(id) { it.copy(nickname = trimmed) }
    }

    fun setUseClicker(id: String, useClicker: Boolean) {
        update(id) { it.copy(useClicker = useClicker) }
    }

    /** Offset clicker/rilascio in millisecondi; negativo non ha senso. */
    fun setReleaseOffsetMs(id: String, offsetMs: Float) {
        update(id) { it.copy(releaseOffsetMs = offsetMs.coerceAtLeast(0f)) }
    }

    fun deleteProfile(id: String) {
        write(_profiles.value.filterNot { it.id == id })
        if (_activeId.value == id) {
            prefs.edit().remove(KEY_ACTIVE).apply()
            _activeId.value = null
        }
        publishActive()
    }

    /**
     * Salva un tiro. Il tempo può essere assente (tiro senza impatto sentito):
     * in quel caso la distanza si può ancora inserire a mano, ma il tiro non
     * entrerà nel fit finché non ha anche un tempo.
     */
    fun addShot(
        profileId: String,
        distanceM: Float?,
        totalTimeS: Float?,
        airTempC: Float,
        pitchDeg: Float
    ): CalibrationShot? {
        val profile = _profiles.value.firstOrNull { it.id == profileId } ?: return null
        val shot = CalibrationShot(
            id = nextShotId(profile),
            distanceM = distanceM?.takeIf { it.isFinite() && it > 0f },
            totalTimeS = totalTimeS?.takeIf { it.isFinite() && it > 0f },
            airTempC = airTempC,
            pitchDeg = pitchDeg,
            timestampMs = System.currentTimeMillis()
        )
        update(profileId) { it.copy(shots = it.shots + shot) }
        return shot
    }

    /**
     * Distanza inserita a posteriori: il tiro senza impatto non ha un tempo,
     * quindi resta fuori dal fit, ma l'arciere ritrova la distanza che aveva
     * davvero e il tiro ha senso nell'elenco.
     */
    fun setShotDistance(profileId: String, shotId: Long, distanceM: Float?) {
        updateShot(profileId, shotId) {
            it.copy(distanceM = distanceM?.takeIf { value -> value.isFinite() && value > 0f })
        }
    }

    /** Conferma dell'utente su un punto segnalato come sospetto. */
    fun setShotDiscarded(profileId: String, shotId: Long, discarded: Boolean) {
        updateShot(profileId, shotId) { it.copy(discarded = discarded) }
    }

    fun deleteShot(profileId: String, shotId: Long) {
        update(profileId) { profile ->
            profile.copy(shots = profile.shots.filterNot { it.id == shotId })
        }
    }

    /** Cancella i tiri tenendo il profilo: si riparte con lo stesso arco. */
    fun clearShots(profileId: String) {
        update(profileId) { it.copy(shots = emptyList()) }
    }

    private fun updateShot(profileId: String, shotId: Long, transform: (CalibrationShot) -> CalibrationShot) {
        update(profileId) { profile ->
            profile.copy(
                shots = profile.shots.map { if (it.id == shotId) transform(it) else it }
            )
        }
    }

    private fun update(id: String, transform: (BowProfile) -> BowProfile) {
        val current = _profiles.value.firstOrNull { it.id == id } ?: return
        val updated = transform(current)
        if (updated == current) return
        write(_profiles.value.map { if (it.id == id) updated else it })
    }

    private fun write(profiles: List<BowProfile>) {
        prefs.edit().putString(KEY_PROFILES, encode(profiles)).apply()
        _profiles.value = profiles
        publishActive()
    }

    private fun publishActive() {
        _activeProfile.value = _profiles.value.firstOrNull { it.id == _activeId.value }
    }

    private fun nextShotId(profile: BowProfile): Long =
        maxOf(System.currentTimeMillis(), (profile.shots.maxOfOrNull { it.id } ?: 0L) + 1L)

    // ------------------------------------------------------------------
    // Serializzazione
    // ------------------------------------------------------------------

    private fun encode(profiles: List<BowProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val shots = JSONArray()
            profile.shots.forEach { shot ->
                shots.put(
                    JSONObject()
                        .put("id", shot.id)
                        .putOpt("d", shot.distanceM?.toDouble())
                        .putOpt("t", shot.totalTimeS?.toDouble())
                        .put("temp", shot.airTempC.toDouble())
                        .put("pitch", shot.pitchDeg.toDouble())
                        .put("disc", shot.discarded)
                        .put("ts", shot.timestampMs)
                )
            }
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("nick", profile.nickname)
                    .put("clicker", profile.useClicker)
                    .put("offset", profile.releaseOffsetMs.toDouble())
                    .put("created", profile.createdMs)
                    .put("shots", shots)
            )
        }
        return array.toString()
    }

    private fun loadProfiles(): List<BowProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> decodeProfile(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun decodeProfile(json: JSONObject): BowProfile {
        val shotsArray = json.optJSONArray("shots")
        val shots = if (shotsArray == null) {
            emptyList()
        } else {
            List(shotsArray.length()) { index -> decodeShot(shotsArray.getJSONObject(index)) }
        }
        return BowProfile(
            id = json.getString("id"),
            nickname = json.optString("nick").ifEmpty { json.optString("id") },
            useClicker = json.optBoolean("clicker", false),
            releaseOffsetMs = json.optDouble("offset", 0.0).toFloat(),
            createdMs = json.optLong("created", 0L),
            shots = shots
        )
    }

    private fun decodeShot(json: JSONObject): CalibrationShot = CalibrationShot(
        id = json.optLong("id", 0L),
        distanceM = optionalFloat(json, "d"),
        totalTimeS = optionalFloat(json, "t"),
        airTempC = json.optDouble("temp", 20.0).toFloat(),
        pitchDeg = json.optDouble("pitch", 0.0).toFloat(),
        discarded = json.optBoolean("disc", false),
        timestampMs = json.optLong("ts", 0L)
    )

    /**
     * Un numero che può mancare. `optDouble` restituisce NaN quando la chiave
     * non c'è, ma un JSON scritto a mano può anche avere `null`: entrambi
     * diventano "assente", non zero.
     */
    private fun optionalFloat(json: JSONObject, key: String): Float? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optDouble(key, Double.NaN).toFloat()
        return value.takeIf { it.isFinite() && it > 0f }
    }
}
