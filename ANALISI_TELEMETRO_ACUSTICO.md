# Telemetro acustico — analisi delle registrazioni reali

Le misure fatte sulle tre registrazioni vere (70 m con la voce del conduttore, 18 m
in sala, microfono a 3 m dalla linea di tiro) e le regole nate da quelle misure.
È il riferimento tecnico per chi in futuro vuole migliorare l'algoritmo: i numeri
sono quelli letti sul segnale, non stime. Il riassunto per chi usa l'app sta nel
[README](README.md).

## Analizzare una registrazione reale (telemetro acustico)

Il banco sintetico dice che il DSP misura bene due impulsi distanziati di un
secondo. Non dice niente su un video dove sopra lo scocco c'è il parlato. Per
quello c'è `ShotAudioProbe`, uno strumento che gira sulle classi di test (non
entra nell'APK) e fa passare un WAV per **la pipeline vera**: stesso
`AcousticShotAnalyzer`, stessa `ShotTimingMachine`, stesso pilotaggio a blocchi
da 20 ms che usa `AcousticRangefinder`. Cambia solo la sorgente: un file invece
del microfono.

Serve un decodificatore audio sulla macchina, **non** nel progetto: `ffmpeg` (su
Windows basta `pip install imageio-ffmpeg` e poi usare il binario che stampa
`python -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())"`).

```bash
# 1. decodifica: mono 16 bit, il formato che lo strumento sa leggere
ffmpeg -i registrazione.mp3 -ac 1 -ar 48000 -sample_fmt s16 app/build/audio/tiri.wav

# 2. analisi (percorso assoluto: il task gira nella cartella app/)
./gradlew :app:analyzeShotAudio -Pwav=$PWD/app/build/audio/tiri.wav
```

Opzioni: `-Pmodo=continuo` (tutto il file d'un fiato invece di una sessione per
tiro), `-PuseClicker=false`, `-PoffsetMs=150`, `-PautoClicker=true` (il motore
decide da solo se il clicker c'è), `-PreleaseFloor=0.05` (il pavimento di
ampiezza della corda che parte, in ampiezza di picco: 0,05 è −26 dBFS), e il
profilo arco con cui costruire la banda di plausibilità:
`-Pprofilo=70m|recurve|barebow|compound` (`-Pdmin`/`-Pdmax` sovrascrivono il
campo di tiro). **Senza profilo la banda non c'è e il veto non si applica**,
come in produzione senza calibrazione: non si ricade su una costante.

La tabella che stampa confronta ogni scocco misurato dal motore con i riferimenti
passati (i quaranta misurati a mano su una registrazione di dodici minuti, 40
tiri a ~70 m con la voce del conduttore sopra), con l'errore in millisecondi e
il Δ misurato. La distanza non viene stampata: dipende dal modello dell'arco, e
con il modello dei 70 m su un barebow o un compound darebbe un numero senza
senso. Sullo stesso file dei 70 m, in modalità continua con `-Pprofilo=70m`:
**39 scocchi su 40** entro 120 ms (tolleranza dettata dai riferimenti letti a
mano, dalla latenza del decoder MP3 e dall'inviluppo STA/LTA), errore mediano
**+8 ms** con scarto tipo 7 ms, Δ medio 1,441 s. Dei 39 tiri con Δ, tutti
cadono fra **65 e 75 m** (media 69,9 m): è la banda del profilo, che è anche il
veto; l'unico scocco non agganciato resta il 22.

Come sono rientrati i quattro che mancavano:

- **3, 20, 34**: il primo picco acuto era una frase e la finestra dei 450 ms si
  chiudeva prima dello scocco vero, così il click del clicker finiva preso per
  rilascio (149 ms prima). Adesso un transiente acuto almeno quattro volte il
  "click" in corso è il click vero: sui tre tiri la frase sta a 0,003–0,006 e il
  click a 0,25–0,40, mentre su tutti i quaranta tiri lo scocco non supera mai il
  click di più di 3 volte. I tre tornano a 69,8, 69,8 e 70,0 m.

I residui, ognuno con la sua ragione letta sul segnale:

- **22**: qui non c'è niente da agganciare, e la misura spettrale lo dice. La
  firma di uno scocco — acuto 6–12 kHz ≥ 0,05 **e** tonfo grave ≥ 0,10 entro
  ±20 ms — c'è in 39 riferimenti su 40; al 386,88 s l'acuto sopra 6 kHz vale
  **0,007** contro 0,065–0,391 degli altri tiri (il più debole è il 3), mentre il
  grave a 0,211 è la vocale sostenuta. Scandendo 386,40–389,40 s ogni 5 ms non
  esiste **nessun** evento con quella firma. L'unico transiente meccanico della
  zona è un click a 387,05 s (6–12 kHz 0,189, grave 0,077), cioè **170 ms dopo**
  il riferimento e senza scocco che lo segua: il clicker ha scattato, la corda
  no, o il suo suono è coperto dalla voce. Provate e scartate, misurando: la
  ripresa come scocco quando c'è un tonfo grave (una vocale è a banda larga
  quanto una corda: inventa un tiro da 79 m e ne rompe 21); l'ampiezza a banda
  intera (0,25 contro 0,41 del click, nessuna separazione); il confronto con la
  forma d'onda dei click validati (al 386,88 s non c'è fronte da confrontare).
  Il motore resta a 39/40: nessuna regola che agganci questa vocale senza
  inventare un tiro.
- **10**: una vocale maschera il battifreccia e il grave resta sotto soglia;
  il tiro misura 56,9 m.
- **21**: il battifreccia torna da 70 m quasi senza energia sotto 1,5 kHz
  (0,006 contro 0,056 in alto), quindi il rilevatore grave non lo vede; vince
  un'esplosiva del parlato (86,9 m).
- **26**: un transiente debole 130 ms prima tiene il rilevatore in trigger e
  l'impatto, venti volte più forte, cade dentro la stessa finestra (64,3 m).

Fra quel che è stato provato e misurato, senza effetto o peggio: selezione per
rapporto STA/LTA, per durata del transiente, con la soglia del ramo grave
abbassata, con la previsione del tiro precedente, e una soglia relativa
sull'acuto coincidente (31 tiri su 36 invece di 33).

## La sessione a 18 m (riferimento vicino e silenzioso)

Secondo riferimento del progetto, e il caso opposto al primo: **sette minuti a
18 m in sala**, telefono a terra con il microfono verso il paglione, dieci
frecce, nessun clicker e nessuna voce se non due frasi lontane. La distanza è
quella dichiarata da chi ha tirato; **la velocità dell'arco resta ignota** e non
si ricava da questo file, quindi la sessione entra nei riferimenti **senza
banda** (`barebow` a 51 m/s darebbe 0,371–0,712 s, che non contiene il Δ
misurato: sarebbe una banda inventata).

La tabella misurata sta in
[app/src/test/resources/audio/voleo_18m_tiri.csv](app/src/test/resources/audio/voleo_18m_tiri.csv):

| # | scocco (s) | Δ (s) | acuto | medio | grave | SNR grave |
|---|---|---|---|---|---|---|
| 1 | 51,611 | 0,293 | 0,033 | 0,037 | 0,078 | 380 |
| 2 | 88,501 | 0,314 | 0,033 | 0,055 | 0,025 | 120 |
| 3 | 128,781 | 0,316 | 0,036 | 0,052 | 0,056 | 273 |
| 4 | 160,150 | 0,330 | 0,037 | 0,030 | 0,065 | 318 |
| 5 | 193,869 | 0,323 | 0,038 | 0,060 | 0,030 | 145 |
| 6 | 264,374 | 0,329 | 0,043 | 0,050 | 0,069 | 337 |
| 7 | 298,488 | 0,321 | 0,032 | 0,081 | 0,051 | 251 |
| 8 | 334,549 | 0,320 | 0,034 | 0,047 | 0,065 | 315 |
| 9 | 368,825 | 0,338 | 0,039 | 0,032 | 0,048 | 234 |
| 10 | 406,825 | 0,306 | 0,033 | 0,119 | 0,024 | 115 |

Δ mediana **0,321 s** (media 0,319, scarto tipo 12 ms). Le colonne di ampiezza
sono le tre ampiezze dell'inviluppo misurate offline (acuto 6–11,5 kHz, medio
1,5–8 kHz, grave 120–900 Hz) e l'SNR è quello del grave sul fondo della stessa
banda: 41–52 dB, cioè un file **pulito**, dove il fondo utile sta a −93 dBFS.

Ed è proprio quello che lo rompeva. Con soglie pensate per i 70 m il motore su
questo file apriva una sessione a ogni fruscio — **37 misure e 72 sessioni
chiuse senza impatto** su dieci tiri — perché qualunque rumore da 0,008 supera
uno STA/LTA di 3 quando il fondo è a −93 dBFS. I falsi si riconoscono da soli
nell'ampiezza: lo scocco inventato vale **0,001–0,047**, i dieci scocchi veri
**0,154–0,248**. La corda che parte a un metro dal microfono è sempre un evento
forte; il fruscio no. Da lì il pavimento assoluto
`ShotTimingConfig.minReleaseAmplitude` = **0,05** (−26 dBFS), applicato
**all'apertura del tiro** e non agli eventi con cui si conferma l'impatto: a 70
m il battifreccia in alto vale 0,006 e una soglia così lo cancellerebbe.

Risultato sullo stesso file, `-Pmodo=continuo -PuseClicker=false`:

```
scocchi agganciati: 10/10
con impatto misurato: 10/10
misure del motore: 10 (non collegate a un riferimento: 0)
sessioni chiuse senza impatto: 0
errore mediano rispetto al riferimento: -1 ms (scarto tipo 7 ms)
delta dei tiri agganciati: medio 0.318 s, mediana 0.322 s
```

Nessun falso positivo, Δ mediana 0,322 s contro 0,321 misurata offline. Il
pavimento non tocca i 70 m (39/40, +8 ms, Δ medio 1,441 s, identici), ma ne
riduce le misure spurie da 188 a 108: quelle in più erano frase e rumore che
superavano il trigger, non tiri. Lo sweep 0,02–0,05 è nei numeri qui sopra; con
0,02 e 0,03 su questo file resta una misura falsa (una sillaba a 236,6 s,
acuto 0,047) e con 0,05 sparisce.

I test automatici usano tre finestre ritagliate da questa sessione:
`voleo_18m_51.wav` e `voleo_18m_368.wav` (due tiri veri, il secondo con
l'impatto più debole del set) e `voleo_18m_silenzio.wav`, otto secondi di sala
vuota in cui il motore senza pavimento inventava due tiri a 3,8 e 7,4 s.

## La sessione con il microfono a 3 m dalla linea di tiro

Terzo riferimento, e il primo con il telefono **lontano dalla corda**: sei
minuti e diciotto, microfono a 3 m dalla linea di tiro, dieci frecce. La
distanza del bersaglio **non è dichiarata** e non si ricava senza la velocità
dell'arco: il Δ non dipende da quanto è lontano il microfono (il suono del
rilascio perde quei 3 m e l'impatto li riguadagna), quindi anche questa sessione
resta nei riferimenti **senza banda**.

| # | scocco (s) | Δ (s) | acuto scocco | medio impatto | grave impatto |
|---|---|---|---|---|---|
| 1 | 76,157 | 0,353 | 0,058 | 0,054 | 0,016 |
| 2 | 108,253 | 0,346 | 0,044 | 0,029 | 0,058 |
| 3 | 141,013 | 0,361 | 0,056 | 0,011 | 0,030 |
| 4 | 171,741 | 0,376 | 0,060 | 0,018 | 0,033 |
| 5 | 205,569 | 0,393 | 0,067 | 0,013 | 0,025 |
| 6 | 237,558 | 0,348 | 0,043 | 0,078 | 0,015 |
| 7 | 269,447 | 0,354 | 0,052 | 0,086 | 0,032 |
| 8 | 298,617 | 0,330 | 0,044 | 0,084 | 0,012 |
| 9 | 328,503 | 0,366 | 0,053 | 0,043 | 0,015 |
| 10 | 362,942 | 0,355 | 0,060 | 0,083 | 0,009 |

Δ mediana **0,354 s** (media 0,358, scarto tipo 16 ms). La tabella completa, con
SNR per tiro, sta in
[app/src/test/resources/audio/tiri_3m_riferimenti.csv](app/src/test/resources/audio/tiri_3m_riferimenti.csv).

Qui il rilascio arriva attutito (acuto 0,043–0,067, sopra il pavimento di 0,05
per un pelo) e — soprattutto — **la stanza non lo spegne netto**. L'inviluppo
lo+mid scende per 300 ms con dentro un'onda a 0,15 s che vale 0,03, quanto
l'impatto vero a 0,35 s (0,04): stesso ordine di ampiezza, quindi nessuna soglia
li separa. Con il solo criterio dell'ampiezza due tiri su dieci misuravano
**0,157 e 0,180 s** invece di 0,361 e 0,393, cioè una distanza inventata.

La differenza non è l'ampiezza, è la **forma**: un urto è un attacco che parte
da un minimo, un'onda di coda è una risalita del 10–60% sopra il minimo dei 60
ms precedenti. Misurato sui venti tiri delle due sessioni a 18 m (microfono a 1
m e a 3 m): gli impatti veri salgono **5,9–27×**, le onde della coda
**1,0–3,1×**. Il rilevatore di picchi (`BroadbandPeakDetector`) porta perciò
l'informazione `isAttack` e la macchina **preferisce un attacco**, ripiegando
sul picco più forte quando in finestra non ce n'è nessuno: a 60 m il compound ha
un impatto sepolto nel rumore (salita 1,9–3,4×) e lì il ripiego è l'unico
candidato.

Risultato sullo stesso file, `-Pmodo=continuo -PuseClicker=false`:

```
scocchi agganciati: 10/10
con impatto misurato: 10/10
misure del motore: 12 (non collegate a un riferimento: 2)
  misura fuori tabella: scocco 5.175 s, delta 0.546 s
  misura fuori tabella: scocco 374.817 s, delta 0.905 s
sessioni chiuse senza impatto: 0
errore mediano rispetto al riferimento: -4 ms (scarto tipo 15 ms)
delta dei tiri agganciati: medio 0.362 s, mediana 0.358 s
```

Le due misure fuori tabella stanno prima del primo tiro (5,2 s) e dopo
l'ultimo (374,8 s): è parlato, non frecce. Il Δ del motore è 4 ms più lungo di
quello misurato offline in mediana, e fino a 30 ms più lungo nei due tiri dove
l'attacco ha un picco arrotondato: la tabella prende l'inizio dell'inviluppo,
il motore il suo massimo.

Test automatici: `tiri_3m_141.wav` (il tiro che sbagliava, ora a 0,388 s) e
`tiri_3m_76.wav` in `RealRecordingTest`, con Δ atteso dalla tabella offline e
tolleranza 40 ms.

## La coda del picco e il fronte dell'attacco (le due regole del 22/11/2026)

Il tempo che l'app misura è il **massimo** dell'inviluppo lo+mid, ma il tempo di
volo del profilo finisce quando **arriva** l'onda. Il massimo arriva sempre
qualche decina di millisecondo dopo: misurato su 40 tiri a 70 m, 5 ms in
mediana e 9 ms al massimo (10° percentile 5, 90° 16). Per questo la banda porta
la tolleranza di coda `BAND_PEAK_LAG_S` = **40 ms**: senza, il vero impatto
veniva scartato per una ventina di millisecondi e vinceva il rimbombo (5,5 e
6,8 m invece di 17–19).

La regola nuova è più forte della tolleranza: se il **fronte** dell'attacco
(dove l'inviluppo attraversa metà salita, 4–33 ms prima del massimo) cade dentro
la banda, il picco viene accettato anche se sfora la coda, e resta **verde**.
Il tempo misurato non cambia — resta il massimo — quindi le distanze restano
quelle calibrate. Le due registrazioni delle 12.32 del 22/11/2026 (picco +7 e
+19 ms oltre la fine della banda, fronte dentro) sono il caso che lo fissa:
senza la regola erano gialle, con la regola sono verdi a 18,7 e 18,3 m. Il
fronte si conosce solo per i picchi che salgono di almeno 4× (a 60 m il compound
sale di 1,9–3,4× e ripiega sul massimo) e non si conosce affatto per i
transienti a banda larga: per quelli la tolleranza di coda resta indispensabile.

La seconda regola riguarda il caso opposto: un **attacco vero oltre il campo di
calibrazione** non deve essere sostituito da un'onda della coda che cade dentro
il campo. Prima succedeva: sulla registrazione delle 19.21 del 22/11/2026 lo
scocco è a 1,647 s, il rimbombo a ~1,75 s (salita 1,5×, quindi non un attacco) e
il vero impatto a 2,039 s (attacco a banda larga, salita 20×, Δ 0,395 s = 17,4 m
e 182 km/h). Con il profilo tarato fino a 15 m il 17,4 m cade fuori dalla banda,
il motore scartava l'attacco e misurava il rimbombo: **0,099 s**, cioè 5–6 m e
oltre 210 km/h, mostrati **verdi**. Ora un picco che non è un attacco e cade nei
primi **200 ms** dallo scocco è riconosciuto come coda della corda, e se più
tardi c'è un attacco oltre il campo vince quello: stesso Δ, 17,4 m, ma **rosso**
perché fuori dal campo calibrato. Con un profilo che arriva a 18 m lo stesso
tiro torna verde. Gli attacchi *prima* della banda restano esclusi: una voce a
0,652 s non deve battere il battifreccia a 1,435 s (tiro 18 dei 70 m).

Test: `ilFronteDellAttaccoRendeVerdiLeDueRegistrazioniDelleDodiciTrentadue`,
`ilRimbomboNonPrendeIlPostoDellImpattoFuoriDalCampo` (con
`tiri_1921_38.wav`, la registrazione vera) e i 70 m completi con
`:app:analyzeImpactOnsetFull` (39/40, tutti verdi, margine minimo +107 ms).
