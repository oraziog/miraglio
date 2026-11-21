package com.miraglio.domain.audio

import com.miraglio.domain.ballistics.ArrowSpeedModel
import com.miraglio.domain.ballistics.BallisticCalculator
import com.miraglio.tools.ShotAudioProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tre tiri veri del file a 70 m, con la voce sopra, più due registrazioni di
 * archi diversi: i casi più difficili che il motore sbagliava, ritagliati dalle
 * registrazioni al campo. Ogni finestra è un WAV mono 16 bit a 48 kHz,
 * esattamente quello che esce dal telefono a parte il rumore del convertitore:
 * niente segnale sintetico. I test sintetici restano in [ShotTimingTest]; qui
 * contano le registrazioni vere.
 */
class RealRecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Fa passare la finestra [risorsa] per la pipeline e restituisce l'unico
     * tiro che contiene. Se il motore ne trova zero o più d'uno il test
     * fallisce qui, prima delle asserzioni sul tempo.
     */
    private fun unTiro(
        risorsa: String,
        useClicker: Boolean = true,
        banda: ClosedFloatingPointRange<Float>? = BANDA_70M,
        releaseFloor: Float = ShotAudioProbe.DEFAULT_RELEASE_FLOOR
    ): ShotAudioProbe.Tiro {
        val file = temp.newFile(risorsa)
        javaClass.getResourceAsStream("/audio/$risorsa")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val tiri = ShotAudioProbe.analizzaContinuo(
            file,
            useClicker = useClicker,
            offsetMs = 0f,
            banda = banda,
            releaseFloor = releaseFloor
        )
        assertEquals("la finestra di $risorsa contiene un solo tiro", 1, tiri.size)
        return tiri.single()
    }

    /**
     * Le tre cose che ogni tiro deve rispettare: lo scocco dove l'ha visto chi
     * ha misurato a mano, il Δ giusto e la distanza del bersaglio, ricavata con
     * il modello dell'arco che ha sparato.
     */
    private fun assertTiro(
        risorsa: String,
        scoccoS: Double,
        deltaS: Double,
        useClicker: Boolean = true,
        banda: ClosedFloatingPointRange<Float> = BANDA_70M,
        modello: ArrowSpeedModel = ShotAudioProbe.MODELLO,
        distanzaM: Double = 70.0,
        tolleranzaDistanza: Double = 1.5
    ) {
        val tiro = unTiro(risorsa, useClicker, banda)
        assertEquals("lo scocco in $risorsa", scoccoS, tiro.releaseS, 0.060)

        val delta = requireNotNull(tiro.deltaS) { "il battifreccia in $risorsa deve esserci" }
        assertEquals("scocco -> impatto in $risorsa", deltaS, delta.toDouble(), 0.020)
        assertTrue(
            "la misura in $risorsa deve stare nella banda fisicamente possibile",
            delta in banda
        )

        val distanza = requireNotNull(
            BallisticCalculator.distance(delta, modello, airTempC = 20f)
        ) { "il modello deve produrre una distanza" }
        assertEquals("distanza ricavata in $risorsa", distanzaM, distanza.toDouble(), tolleranzaDistanza)
    }

    /**
     * Tiro 11 (finestra 140,0–146,0 s): il caso che smascherava la scelta
     * dell'impatto a ampiezza.
     *
     * Dentro ci sono il click a 1,50 s, lo scocco a 1,654 s e il battifreccia
     * 1,442 s dopo. A 0,58 s dalla corda parte una parola forte 0,081 contro i
     * 0,009 del battifreccia, e il motore la sceglieva — Δ 0,58 s, distanza
     * 30 m invece di 70. Adesso l'impatto si sceglie fra i transienti **a banda
     * larga** (un urto eccita anche l'acuto, una voce no) e il tiro torna a
     * 70 m.
     */
    @Test
    fun ilTiroVeroConLaVoceSopraTornaASettantaMetri() {
        assertTiro("tiro_141_65.wav", scoccoS = 1.654, deltaS = 1.442)
    }

    /**
     * Tiro 18 (finestra 234,0–240,0 s), il caso in cui la scelta produceva la
     * distanza più assurda: **33,6 m**.
     *
     * Il candidato colpevole è una sillaba a 0,652 s dalla corda, larga di
     * banda quanto un urto. La banda derivata dal profilo (65–75 m) però a
     * 33 m non lascia passare niente: il candidato viene scartato e l'impatto
     * torna a 1,435 s, cioè 69,7 m.
     */
    @Test
    fun ilTiroVeroConLaDistanzaAssurdaTornaASettantaMetri() {
        assertTiro("tiro_235_95.wav", scoccoS = 1.963, deltaS = 1.435)
    }

    /**
     * Tiro 3 (finestra 24,6–27,6 s), il caso in cui una frase prende il posto
     * del click.
     *
     * La registrazione, in quel punto, ha una sillaba a 24,802 s (0,006 di
     * acuto), il click del clicker a 25,161 (0,405) e lo scocco a 25,320
     * (0,320). Prendendo per click la sillaba, la finestra dei 450 ms si chiude
     * prima dello scocco e il click vero viene scelto come rilascio: il tiro
     * misura 1,597 s e lo scocco risulta 149 ms prima del vero.
     *
     * Con la riancoratura ([ShotTimingMachine.ANCORA_CLICK_FACTOR]) il click
     * vero, 67 volte più forte della sillaba, riapre la finestra e lo scocco
     * torna al suo posto: Δ 1,438 s, cioè 69,8 m.
     */
    @Test
    fun laFraseNonPrendeIlPostoDelClick() {
        assertTiro("tiro_25_31.wav", scoccoS = 0.720, deltaS = 1.438)
    }

    /**
     * Barebow a 18 m (tiro 30 della registrazione, scocco a 849,715 s).
     *
     * È il caso che la banda fissa non toccava: Δ 0,418 s sta **fuori** dalla
     * vecchia 1,0–2,0 s, quindi il veto non si applicava e il motore poteva
     * ripiegare su un transiente più tardi. La banda del profilo (51 m/s,
     * campo 16–30 m) è 0,371–0,712 s, e il picco lo+mid dell'evento la
     * rispetta. Il trigger grave qui non produce niente (STA/LTA 0,4): senza i
     * picchi non ci sarebbe nessun candidato.
     */
    @Test
    fun ilBarebowIndoorAVentottoMetriRecuperaLImpatto() {
        val banda = bandaProfilo(51f, 0.002f, 16f, 30f)
        assertFalse(
            "la costante di prima non contiene un tiro a 18 m, la banda del profilo sì",
            banda.contains(1.0f)
        )
        assertEquals("inizio della banda barebow", 0.371f, banda.start, 0.005f)
        assertEquals("fine della banda barebow", 0.712f, banda.endInclusive, 0.010f)
        assertTiro(
            "barebow_18m_849.wav",
            scoccoS = 0.815, // 849,715 s meno l'inizio della finestra (848,9 s)
            deltaS = 0.418,
            useClicker = false,
            banda = banda,
            modello = ArrowSense.BAREBOW.modello,
            distanzaM = 18.0,
            tolleranzaDistanza = 1.0
        )
    }

    /**
     * Compound a ~51 m (tiro 57, scocco a 1540,461 s): il caso in cui la
     * vecchia scelta a ampiezza prendeva un'eco o una parola e finiva a 96 m.
     *
     * Con la banda del profilo (86 m/s, campo 15–90 m) e i picchi lo+mid il
     * Δ torna 0,787 s contro i 0,787 misurati a mano, cioè 51,9 m sul modello
     * dell'arco (la tabella dice 51,0 m).
     */
    @Test
    fun ilCompoundASessantaMetriRecuperaLImpatto() {
        assertTiro(
            "compound_51m_1540.wav",
            scoccoS = 0.801, // 1540,461 s meno l'inizio della finestra (1539,66 s)
            deltaS = 0.787,
            useClicker = false,
            banda = bandaProfilo(86f, 0.001f, 15f, 90f),
            modello = ArrowSense.COMPOUND.modello,
            distanzaM = 51.0,
            tolleranzaDistanza = 2.0
        )
    }

    /**
     * La sessione del 21/11/2026 a 18 m in sala, telefono orizzontale a terra e
     * microfono verso il paglione: dieci frecce, Δ mediana 0,321 s.
     *
     * Il caso che ha reso necessario il pavimento di rilascio: il fondo nella
     * banda 1,5–8 kHz sta a −93 dBFS, quindi un fruscio qualsiasi supera il
     * trigger e apre un tiro. Senza pavimento il motore produceva 37 misure,
     * con scocchi da 0,001 a 0,047; con il pavimento sono dieci, e il tiro
     * dopo il taglio cade dove l'ha messo la misura offline.
     *
     * Qui la banda di plausibilità non c'è di proposito: la distanza è
     * dichiarata (18 m) ma la velocità dell'arco è ignota, e una banda senza
     * velocità inventerebbe il numero che poi si legge come distanza.
     */
    @Test
    fun ilTiroPulitoAVentottoMetriSiMisuraSenzaModello() {
        val tiro = unTiro("voleo_18m_51.wav", useClicker = false, banda = null)
        assertEquals("lo scocco nella finestra del 21/11", 2.011, tiro.releaseS, 0.060)
        assertEquals(
            "scocco -> impatto a 18 m",
            0.293,
            requireNotNull(tiro.deltaS) { "il battifreccia deve esserci" }.toDouble(),
            0.020
        )
    }

    /**
     * Il tiro con l'impatto più debole della stessa sessione (0,048 di grave
     * contro 0,024–0,078 degli altri): il pavimento deve lasciarlo passare,
     * perché è comunque 234 volte il rumore di fondo.
     */
    @Test
    fun ancheIlTiroPiuDeboleDellaSessioneSiMisura() {
        val tiro = unTiro("voleo_18m_368.wav", useClicker = false, banda = null)
        assertEquals(2.425, tiro.releaseS, 0.060)
        assertEquals(0.338, requireNotNull(tiro.deltaS).toDouble(), 0.020)
    }

    /**
     * Otto secondi di sala vuota, primi della stessa sessione: qui il vecchio
     * motore inventava due tiri (a 3,8 e 7,4 s, scocco 0,005 e 0,008, impatto
     * 0,007 e 0,003). Un telemetro che misura il silenzio non è usabile.
     */
    @Test
    fun ilSilenzioDellaStessaSalaNonInventaTiri() {
        val file = temp.newFile("voleo_18m_silenzio.wav")
        javaClass.getResourceAsStream("/audio/voleo_18m_silenzio.wav")!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val tiri = ShotAudioProbe.analizzaContinuo(
            file,
            useClicker = false,
            offsetMs = 0f,
            banda = null
        )
        assertEquals("nessun tiro in otto secondi di sala vuota", 0, tiri.size)
    }

    /**
     * La sessione del 21/11/2026 con il microfono a **3 m dalla linea di tiro**:
     * dieci frecce, Δ mediana 0,354 s. La distanza del bersaglio non è
     * dichiarata e non si ricava senza la velocità dell'arco: il Δ non dipende
     * da quanto è lontano il microfono (il suono del rilascio perde quei 3 m e
     * l'impatto li riguadagna), quindi resta un numero di tempo e basta.
     *
     * Qui il rilascio arriva attutito (acuto 0,045–0,067 nella tabella offline)
     * e la stanza non lo spegne netto: l'inviluppo lo+mid scende per 300 ms con
     * dentro un'onda a 0,15 s che vale 0,03, quanto l'impatto vero a 0,35 s
     * (0,04). Prima del criterio di salita il motore prendeva quell'onda e due
     * tiri su dieci misuravano 0,157 e 0,180 s invece di 0,361 e 0,393.
     *
     * Nessuna banda, come per la sessione con il microfono a 1 m (distanza non
     * dichiarata, velocità ignota). Il Δ atteso è quello della tabella offline;
     * il motore può cadere sul picco dell'attacco invece che sul suo inizio
     * (fino a ~30 ms dopo), per questo la tolleranza è 40 ms.
     */
    @Test
    fun ilMicrofonoATreMetriNonScambiaLaCodaPerIlBattifreccia() {
        val tiro = unTiro("tiri_3m_141.wav", useClicker = false, banda = null)
        assertEquals(1.413, tiro.releaseS, 0.060)
        assertEquals(0.361, requireNotNull(tiro.deltaS).toDouble(), 0.040)
    }

    /** Lo stesso file, il primo tiro: qui la coda non basta a ingannare il motore. */
    @Test
    fun ilTiroConIlMicrofonoATreMetriSiMisura() {
        val tiro = unTiro("tiri_3m_76.wav", useClicker = false, banda = null)
        assertEquals(1.557, tiro.releaseS, 0.060)
        assertEquals(0.353, requireNotNull(tiro.deltaS).toDouble(), 0.040)
    }

    /** I modelli dei due archi nuovi. */
    private enum class ArrowSense(val modello: ArrowSpeedModel) {
        BAREBOW(ArrowSpeedModel(51f, 0.002f)),
        COMPOUND(ArrowSpeedModel(86f, 0.001f))
    }

    private fun bandaProfilo(vf: Float, decay: Float, dmin: Float, dmax: Float) =
        requireNotNull(
            ImpactPlausibility.band(
                ArrowSpeedModel(vf, decay),
                listOf(dmin, dmax),
                airTempC = 20f
            )
        ) { "il profilo deve dare una banda" }

    private companion object {
        /** La banda dei tiri a 70 m, quella del profilo 65–75 m. */
        val BANDA_70M = requireNotNull(
            ImpactPlausibility.band(
                ArrowSpeedModel(65f, 0.002f),
                listOf(65f, 75f),
                airTempC = 20f
            )
        )
    }
}
