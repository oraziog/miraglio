package com.miraglio.data.profile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I profili sono l'unica cosa che l'arciere non può ricostruire a memoria: se
 * il salvataggio si perde, si rifà la calibrazione da capo (e sono tiri).
 *
 * Il test più importante è quello che **ricrea lo store**: una classe che
 * tiene il dato solo nel proprio `StateFlow` funziona benissimo finché l'app
 * resta aperta e perde tutto al primo riavvio.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class BowProfileStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Un'istanza nuova, come dopo il riavvio dell'app. */
    private fun newStore() = BowProfileStore(context)

    @Test
    fun profiloETiriSopravvivonoAlRiavvio() {
        val store = newStore()
        val profile = store.addProfile(
            nickname = "Olimpico 36#",
            useClicker = true,
            releaseOffsetMs = 25f
        )
        store.addShot(
            profileId = profile.id,
            distanceM = 30f,
            totalTimeS = 0.8123f,
            airTempC = 21.5f,
            pitchDeg = 4f
        )

        val reopened = newStore()
        val restored = reopened.profiles.value.single()

        assertEquals("Olimpico 36#", restored.nickname)
        assertTrue(restored.useClicker)
        assertEquals(25f, restored.releaseOffsetMs)
        assertEquals(1, restored.shots.size)
        assertEquals(30f, restored.shots.single().distanceM)
        assertEquals(0.8123f, restored.shots.single().totalTimeS)
        assertEquals(21.5f, restored.shots.single().airTempC)
    }

    @Test
    fun ilProfiloAttivoRestaTaleDopoIlRiavvio() {
        val store = newStore()
        val first = store.addProfile("Primo arco")
        val second = store.addProfile("Secondo arco")
        store.setActive(second.id)

        val reopened = newStore()

        assertEquals(first.id, reopened.profiles.value.first().id)
        assertEquals(second.id, reopened.activeProfile.value?.id)
    }

    /** Il primo profilo creato diventa attivo: non serve selezionarlo a mano. */
    @Test
    fun ilPrimoProfiloEVAttivo() {
        val store = newStore()
        val profile = store.addProfile("Arco nuovo")

        assertEquals(profile.id, store.activeProfile.value?.id)
    }

    @Test
    fun unaSolaDistanzaNonPermetteDiMisurare() {
        val store = newStore()
        val profile = store.addProfile("Solo una tacca")
        store.addShot(profile.id, 30f, 0.83f, 20f, 0f)
        store.addShot(profile.id, 30f, 0.82f, 20f, 0f)

        val reloaded = store.profiles.value.single()

        assertEquals(2, reloaded.shots.size)
        assertEquals(1, reloaded.calibratedDistances)
        assertFalse(reloaded.canMeasure)
    }

    @Test
    fun dueDistanzeDiverseSbloccanoLeMisure() {
        val store = newStore()
        val profile = store.addProfile("Due tacche")
        store.addShot(profile.id, 30f, 0.83f, 20f, 0f)
        store.addShot(profile.id, 50f, 1.15f, 20f, 8f)

        val reloaded = store.profiles.value.single()

        assertEquals(2, reloaded.calibratedDistances)
        assertTrue(reloaded.canMeasure)
        assertEquals(2, reloaded.fitPoints.size)
    }

    /**
     * Un tiro senza impatto sentito resta salvato con la sua distanza, ma non
     * entra nel fit: senza Δt non dice niente sulla velocità della freccia.
     */
    @Test
    fun unTiroSenzaTempoRestaSalvatoMaFuoriDalFit() {
        val store = newStore()
        val profile = store.addProfile("Senza impatto")

        val shot = store.addShot(profile.id, 40f, null, 18f, -3f)!!
        store.setShotDistance(profile.id, shot.id, 42f)

        val reloaded = store.profiles.value.single()
        val saved = reloaded.shots.single()

        assertEquals(42f, saved.distanceM)
        assertNull(saved.totalTimeS)
        assertFalse(saved.usableForFit)
        assertEquals(0, reloaded.fitPoints.size)
        assertEquals(0, reloaded.calibratedDistances)
        assertEquals(1, reloaded.unusableShots)
    }

    /** Un punto segnalato come sospetto esce dal fit solo quando si conferma. */
    @Test
    fun ilPuntoSospettoEsceSoloDopoLaConferma() {
        val store = newStore()
        val profile = store.addProfile("Con outlier")
        store.addShot(profile.id, 30f, 0.83f, 20f, 0f)
        val suspect = store.addShot(profile.id, 60f, 3.5f, 20f, 0f)!!

        assertEquals(2, store.profiles.value.single().fitPoints.size)

        store.setShotDiscarded(profile.id, suspect.id, true)

        val reloaded = store.profiles.value.single()
        assertEquals(1, reloaded.fitPoints.size)
        assertFalse(reloaded.canMeasure)
    }

    @Test
    fun cancellareIlProfiloAttivoLasciaSenzaProfiloAttivo() {
        val store = newStore()
        val profile = store.addProfile("Da cancellare")
        store.addShot(profile.id, 20f, 0.6f, 20f, 0f)

        store.deleteProfile(profile.id)

        assertTrue(store.profiles.value.isEmpty())
        assertNull(store.activeProfile.value)
        assertTrue(newStore().profiles.value.isEmpty())
    }

    @Test
    fun iTiriSiCancellanoTenendoIlProfilo() {
        val store = newStore()
        val profile = store.addProfile("Da azzerare")
        store.addShot(profile.id, 20f, 0.6f, 20f, 0f)
        store.addShot(profile.id, 30f, 0.85f, 20f, 0f)

        store.clearShots(profile.id)

        val reloaded = store.profiles.value.single()
        assertEquals("Da azzerare", reloaded.nickname)
        assertTrue(reloaded.shots.isEmpty())
    }

    @Test
    fun ilNicknameVuotoNonCancellaIlNome() {
        val store = newStore()
        val profile = store.addProfile("Olimpico")
        store.rename(profile.id, "   ")

        assertEquals("Olimpico", store.profiles.value.single().nickname)
    }

    @Test
    fun lOffsetNonPuoEssereNegativo() {
        val store = newStore()
        val profile = store.addProfile("Offset")

        store.setReleaseOffsetMs(profile.id, -20f)

        assertEquals(0f, store.profiles.value.single().releaseOffsetMs)
    }

    /** Cancellare un tiro non deve toccare gli altri né i profili. */
    @Test
    fun cancellareUnTiroNonToccaGliAltri() {
        val store = newStore()
        val profile = store.addProfile("Due tiri")
        val first = store.addShot(profile.id, 20f, 0.6f, 20f, 0f)!!
        store.addShot(profile.id, 40f, 1.0f, 20f, 0f)

        store.deleteShot(profile.id, first.id)

        val reloaded = store.profiles.value.single()
        assertEquals(1, reloaded.shots.size)
        assertNotEquals(first.id, reloaded.shots.single().id)
    }
}
