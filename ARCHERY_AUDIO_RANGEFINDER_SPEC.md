# SPEC: Telemetro Acustico (Audio Rangefinder) per MIRAGLIO

## 1. Obiettivo
Aggiungere a MIRAGLIO la misura della distanza del bersaglio tramite analisi audio del tiro con l'arco, affiancando la misura ottica esistente. Il sistema deve essere preciso fino a 50–70 m, con compensazione di:
1. Ritardo tra scocco (clicker) e rilascio effettivo della corda.
2. Rumore dell'appoggio dello smartphone dopo l'avvio della misura.
3. Angolo di sito (pendenza) bloccato al momento del puntamento.
4. Temperatura dell'aria per la velocità del suono.

## 2. Vincoli Generali
- **Linguaggio:** Kotlin. Coroutines e Flow per l'asincronia.
- **Audio:** `AudioRecord` in Kotlin puro. **Nessun NDK** nella prima versione.
- **Sensori:** `TYPE_ROTATION_VECTOR` o sensore di gravità per il pitch. **Non usare ARCore.**
- **Lingua:** italiano per documentazione, commenti e stringhe di log. Nomi di classi e variabili in inglese.
- **Persistenza:** prima di scrivere il salvataggio dei profili, analizza il codebase esistente e proponi lo stesso pattern. Chiedi conferma prima di procedere.
- **Testing:** l'harness di test usa file WAV sintetici, così il DSP si valida senza telefono in campo.

## 3. Modello Fisico

### 3.1 Tempo di volo e distanza
Tempo totale misurato tra scocco e impatto:
  t_tot = t_volo + t_suono

Distanza in linea d'aria:
  d = t_tot / (1/v_f(d) + 1/v_s)

dove:
- v_f(d): velocità della freccia alla distanza d, da modello di decadimento (vedi 3.3).
- v_s: velocità del suono (vedi 3.2).

### 3.2 Velocità del suono
  v_s = 331.3 + 0.606 * T   [m/s, T in °C]

Sorgente di T, in ordine di priorità:
1. Valore inserito manualmente dall'utente (sempre prevale).
2. Open-Meteo, via GPS: `https://api.open-meteo.com/v1/forecast?latitude={LAT}&longitude={LON}&current=temperature_2m`
   Cache di 30 minuti.
3. Fallback a 20.0 °C, con indicazione visiva nell'UI.

### 3.3 Decadimento della freccia
- La velocità `v_f` non è costante. Si modella con decadimento in funzione della distanza, con coefficiente calibrato su più distanze.
- La calibrazione va eseguita a distanze note, almeno ogni 10 m, meglio ogni 5 m. Il range minimo di calibrazione è 10 m.
- Il fit si aggiorna a ogni nuovo tiro calibrato. Il modello minimo richiede almeno 2 distanze di calibrazione distinte prima di essere usato per le misure.
- Serve almeno 1 calibrazione per distanza di riferimento per profilo arco.

### 3.4 Compensazione dell'angolo di sito
Con angolo θ bloccato al puntamento:
- d_reale: distanza calcolata dall'audio.
- d_orizz = d_reale * cos(θ). Mostrare entrambe nell'UI.

### 3.5 Offset clicker/rilascio
- Toggle "Usa clicker" impostato dall'utente.
- Con clicker attivo: offset di rilascio configurato dall'utente per il profilo arco (default 0 ms, modificabile).
- Senza clicker: il primo picco ad alta energia è lo scocco.

## 4. Pipeline DSP (Kotlin)

### 4.1 Acquisizione e conteggio campioni
- `AudioRecord`, sample rate 48000 Hz, `PCM_FLOAT`, sorgente `MIC` o `UNPROCESSED` se supportato (fallback a `MIC`).
- Disabilitare AGC e noise suppression se disponibili tramite `AcousticEchoCanceler`/`NoiseSuppressor` o flag equivalenti.
- Nessun NDK nella prima versione.
- Il timestamp di ogni evento è sempre `sampleIndex`, cioè il contatore cumulativo dei campioni letti (`sampleIndex += n` per ogni buffer). **Mai usare `System.nanoTime()` o `System.currentTimeMillis()` per i tempi di evento**, neanche nel callback di lettura.
- Δt = (N_impatto − N_rilascio) / 48000.0

### 4.2 Filtraggio
1. Biquad IIR passa-banda:
   - Ramo A (clicker/corda): 1500–8000 Hz.
   - Ramo B (impatto battifreccia): 120–900 Hz.
2. STA/LTA:
   - STA: 5 ms (240 campioni).
   - LTA: 500 ms (24000 campioni).
   - Ratio[n] = STA[n] / (LTA[n] + ε).
   - Evento on quando Ratio > Soglia_Trigger, off quando Ratio < Soglia_Detrigger. Soglie configurabili.

### 4.3 Macchina a stati
Stati: `IDLE` → `SETTLING` → `READY` → `ARMED` → `RELEASE_DETECTED` → `IMPACT_WAIT` → `DONE`, con `RESET` da qualsiasi stato in caso di timeout o errore.

1. **Assestamento (anti-tonfo):**
   - Dopo la pressione di "Distanza via Audio", lo stato resta `SETTLING` finché il telefono non è fermo.
   - Criterio di stabilità: varianza dell'accelerazione lineare su finestra mobile (es. 500 ms) sotto soglia configurabile. Il criterio va applicato in `SETTLING` prima di passare a `READY`.
   - Feedback: beep o indicatore visivo in `READY`.
2. **Clicker attivo:**
   - Primo picco breve ad alta frequenza = click.
   - **Ma una frase non è un click:** se nella finestra arriva un transiente acuto **almeno quattro volte** quello preso per click, il click vero è quello, la finestra riparte da lì e i candidati già raccolti si buttano (`ShotTimingMachine.ANCORA_CLICK_FACTOR`). Misurato sui tiri 3, 20 e 34 della registrazione: il primo picco è una sillaba a 0,003–0,006 e il click arriva 350–470 ms dopo a 0,25–0,40, cioè 50–113 volte tanto; su tutti i quaranta tiri lo scocco non supera mai il click di più di 3 volte, quindi la soglia separa le due famiglie senza toccare la coppia click→scocco. Senza questa regola la finestra ancorata alla frase si chiude prima dello scocco, il click vero viene scelto come rilascio e il tiro misura 149 ms di troppo (Δ 1,597 s invece di 1,438).
   - Finestra di 50–450 ms per il picco di rilascio. Se non arriva, `RESET` a `READY`.
   - Il picco di rilascio sovrascrive N_rilascio. L'offset utente (ritardo click → rilascio) sposta il riferimento **dal click al rilascio**: con offset 0 si usa il picco, con offset dichiarato si usa click + offset. Sottrarlo a un picco già misurato conterebbe due volte lo stesso ritardo (su un clicker a 150 ms sono ~7 m a 70 m).
3. **Clicker non attivo:** il primo picco ad alta energia avvia N_rilascio.
   - **Clicker riconosciuto in ascolto (`ShotTimingConfig.autoClicker`):** se il profilo non lo dichiara, il motore non decide al primo transiente. Raccoglie gli acuti dei 450 ms successivi e, chiusa la finestra, se c'è un secondo picco almeno un quinto del primo lo tratta come corda che parte (riferimento = il suo picco, non il click), altrimenti il primo transiente è il rilascio. La conferma è fisica e arriva alla fine: se l'ipotesi clicker non produce nessun impatto dentro la banda, il motore torna al primo picco. Serve perché a 5 m l'impatto arriva ~60 ms dopo la corda, cioè **dentro** la finestra dei 450 ms: scambiarlo per un click perderebbe il tiro. Un clicker dichiarato nel profilo usa il percorso con l'offset e non viene rimesso in discussione.
4. **Impatto:** ricerca del picco su Ramo B dal minimo fisico a 2.20 s dal rilascio. L'inizio non è più fisso a 0.15 s: con una banda nota si apre 20 ms prima del suo estremo inferiore (e mai oltre 0.15 s), altrimenti a 5 m il battifreccia — che torna in ~0,06 s — resterebbe fuori. L'ampiezza deve essere inferiore a quella del rilascio.
   - **Campo H&S 5–50 m (toggle della schermata di misura):** distanza sconosciuta, bersagli fra 5 e 50 m. Acceso, la banda è l'unione fra il campo e le distanze di calibrazione, così un profilo calibrato a 15–25 m misura anche un 45 m. È spento di default perché misurato sui quaranta tiri a 70 m: con la finestra 5–75 un profilo 65–75 prende un transiente iniziale in **19 tiri su 39** (Δ medio 0,839 s invece di 1,441). Chi tira a distanza nota non deve ritrovarsi la misura rotta da un default.
   - Fra più candidati si preferisce quello a **banda larga**, cioè con un transiente del Ramo A entro ±20 ms: il battifreccia è un urto e arriva anche in alto, la voce no. Misurato sui 40 tiri della registrazione di riferimento: l'impatto vero ha un acuto coincidente in 11 casi su 11, le scelte sbagliate solo in 3 su 11. Senza questo criterio il più forte nella finestra è quasi sempre una parola (11 tiri su 36 con Δ sbagliato e distanze 30–96 m); con il criterio, 30 tiri su 36 tornano a 68,5–70,7 m.
   - L'impatto scelto deve essere **fisicamente possibile**: la sua Δ deve cadere nella banda derivata dal profilo arco ([ImpactPlausibility]), non in una costante. La banda è il tempo di volo del modello calibrato alle distanze min e max dei tiri di calibrazione: a 70 m con il profilo 65 m/s e campo 65–75 m dà 1,328–1,559 s, un barebow indoor a 18 m (51 m/s, 16–30 m) dà 0,371–0,712 s. Un battifreccia che tornasse da 33 m mentre si tira a 70 non esiste: quel transiente è un'altra cosa, per quanto somigli a un urto. La vecchia costante 1,0–2,0 s descriveva solo l'arco a 70 m: su un barebow a 18 m non vietava niente, e il motore ripiegava su un transiente più tardi.
     - **Degradazione esplicita:** se il profilo non è calibrato (modello assente o non valido) o le distanze note sono meno di due, `ImpactPlausibility.band` restituisce `null` e il veto non si applica: il motore torna al comportamento precedente invece di inventare una finestra da una velocità tipica.
   - **Candidati d'impatto:** non solo gli eventi che superano il trigger grave. A distanza breve l'impatto è troppo quieto rispetto al rilascio appena avvenuto (a 18 m il rapporto STA/LTA all'impatto è 0,37–0,47) e non produce nessun evento: entra perciò in scelta anche il **picco locale dell'inviluppo lo+mid** ([BroadbandPeakDetector]), purché le due bande d'impatto siano entrambe presenti (rapporto ≥ 0,15: una vocale è grave senza media, una sibilante il contrario, un urto no) e purché la sua Δ cada nella banda derivata. Il rilevatore di scocco STA/LTA e la logica del clicker non cambiano.
   - Ordine della scelta: (1) picco d'inviluppo lo+mid plausibile più forte; (2) nessun picco, banda larga **e** plausibile; (3) nessun picco, grave plausibile più forte; (4) nessuno plausibile (bersaglio fuori dal campo di tiro o modello sbagliato), banda larga più forte; (5) comportamento di sempre. I picchi vengono per primi perché a 18 m sono l'unico candidato; a 70 m la precedenza non cambia nulla (39/40, 188 rilevamenti, +8 ms mediani, Δ medio 1,441 s, 69,9 m, identici a prima).
   - Misurato il 21/11/2026 sui tre file reali con la banda del profilo: 70 m 39/40 con 188 rilevamenti e +8 ms; barebow 18 m 60/60 scocchi e 60/60 impatti, Δ mediano −0 ms; compound 61/61 e 61/61, Δ mediano −1 ms. L'istante d'impatto scelto dista dal riferimento entro 20 ms in 58/60 (barebow) e 33/36 (compound): i due tiri compound sbagliati (6 e 14) prendono un'eco 130–170 ms dopo, **più forte** dell'impatto vero e dentro la finestra solo perché il nostro rilascio cade 35–44 ms dopo l'onset usato nella tabella. Il 35/36 misurato offline valeva ancorando la finestra al rilascio della tabella; con il rilevatore di scocco invariato il tetto è 33/36. Provato a preferire il primo picco di pari forza (entro il 30% dal massimo): peggiora (barebow 56/60, compound 31/36), perché prima dell'impatto ci sono anche rumori secchi, quindi resta il più forte.
   - **Eco dell'impatto: provata e scartata.** I tiri compound 6 e 14 scelgono un transiente 82–168 ms dopo l'impatto, più forte di lui (0,061 contro 0,047; il secondo è appena oltre il tetto della banda calcolato dal rilascio vero, 348,655 s contro 348,642, e entra solo perché il nostro rilascio cade 35–44 ms dopo l'onset della tabella). Una regola "un picco è l'eco di uno precedente entro 200 ms se quello vale almeno metà di questo" ripara proprio 6 e 14, ma rompe i tiri in cui un rumore secco precede l'impatto di 55–78 ms (barebow 54/60, compound 32/36): i due casi hanno la stessa forma temporale, e una finestra più stretta di 170 ms non copre l'eco a +168 ms. Restano 33/36; correggerli richiederebbe di ancorare la finestra all'onset del rilascio o di stringere la banda, e la banda dei 70 m finisce a 1,559 s con l'ultimo tiro buono a 1,558, quindi non c'è margine per stringerla.
   - Misurato in precedenza, con la sola banda larga e il veto: il veto da solo porta la distanza media da 70,8 a 69,8 m e i tiri con distanza coerente da 30 a 33 su 36, senza perdere nessuno dei 36 agganci. Provata anche una soglia relativa sull'acuto coincidente (un decimo dell'acuto più forte della finestra): peggiora a 31 su 36 (rompe i tiri 9, 19, 40), scartata.
   - Se nessun candidato è a banda larga resta la scelta a ampiezza: meglio un impatto incerto che un tiro dichiarato senza.
5. **Riavvio su un colpo più forte:** mentre si aspetta l'impatto (fino a 2,20 s) un transiente acuto **almeno il doppio** di quello che si sta cronometrando riapre il tiro da lì. In un campo silenzioso non capita mai; su una registrazione con il parlato, senza questa regola, il motore resta agganciato a una frase e non sente il tiro vero che arriva nel frattempo (misurato sul file di riferimento: 9 tiri su 40 invece di 36). Con la riancoratura del click e il veto di plausibilità il file dà **39 tiri su 40**; provato anche a trattare il transiente della ripresa come scocco quando ha un tonfo grave (una vocale è a banda larga quanto una corda): inventa un tiro da 79 m su una frase e ne rompe 21, scartato.
6. **Caso non separabile (tiro 22 del file di riferimento):** al 386,88 s non c'è nessuno scocco da agganciare. La firma misurata di uno scocco — acuto 6–12 kHz ≥ 0,05 **e** tonfo grave ≥ 0,10 entro ±20 ms — c'è in 39 riferimenti su 40; qui l'acuto sopra 6 kHz vale **0,007** (0,065–0,391 nei restanti tiri) e il grave a 0,211 è una vocale sostenuta. Scandendo la zona (386,40–389,40 s) ogni 5 ms non esiste nessun evento con quella firma; l'unico transiente meccanico è un click a 387,05 s (6–12 kHz 0,189, grave 0,077), 170 ms **dopo** il riferimento e senza scocco che lo segua. Quindi il motore resta a 39/40 e non si aggiunge nessun criterio che agganci una vocale: la ripresa come scocco con tonfo grave inventa un tiro da 79 m e ne rompe 21, l'ampiezza a banda intera non separa (0,25 contro 0,41), e il confronto con la forma d'onda dei click validati non ha un fronte da confrontare.
7. **Fallback:** se non c'è impatto, lo stato va a `DONE_NO_IMPACT`. L'utente può inserire la distanza reale a posteriori. Il tiro viene salvato, ma marcato come non utilizzabile per il fit finché non ha anche un Δt valido.
8. **Verde, giallo, rosso (`ShotQuality`):** la distanza mostrata è colorata secondo la prova che sostiene la scelta dell'impatto, non secondo una probabilità inventata. **Verde:** impatto a banda larga o picco d'inviluppo dentro la banda fisica, e distanza dentro il campo dichiarato. **Giallo:** impatto grave senza acuto coincidente (poteva essere rumore), oppure distanza fuori dal campo 5–50 m (il modello estrapola). **Rosso:** nessun candidato nella banda, la scelta è un ripiego. Il numero non cambia con il colore: `BallisticCalculator` calcola la stessa distanza in tutti e tre i casi.

## 5. Calibrazione Profilo Arco
- Profilo identificato da un **nickname** che descrive la configurazione (es. "Olimpico 36#, riser X, flettenti Y, freccia Z").
- Nessuna persistenza esistente nota: l'agente deve chiedere conferma sul metodo prima di implementare.
- Procedura: tiri a distanze note, almeno ogni 10 m (meglio 5 m), a partire da 10 m. Per ogni tiro: distanza nota, t_tot, temperatura, angolo.
- Solo i tiri con distanza nota e Δt valido alimentano il fit di `v_f(d)`. I tiri con distanza inserita a posteriori ma senza Δt restano salvati e non entrano nel fit.
- **Controllo outlier:** prima di includere un punto nel fit, l'app confronta il punto con la curva attuale. Se lo scarto supera una soglia configurabile, il punto viene segnalato all'utente (es. possibile errore di inserimento o tiro anomalo). Il punto viene scartato solo dopo conferma esplicita dell'utente.
- Un tiro a distanza nota viene anche salvato da "fallback manuale".

## 6. Flusso UX
1. **Setup profilo:** nickname, offset clicker (toggle + valore), calibrazioni.
2. **Misura:**
   - Mirino con zoom digitale 1x–8x (`CameraControl.setZoomRatio`).
   - Pulsante "Distanza via Audio": blocca il pitch corrente e ignora ulteriori aggiornamenti angolari.
   - Stati visivi: "Appoggia il telefono..." → "Pronto, tira!" → "Calcolo..."
   - Risultato: distanza reale, distanza compensata, angolo bloccato, temperatura (con sorgente indicata: manuale, meteo, fallback).
   - Se non c'è impatto: campo per inserire la distanza reale a posteriori.
3. **Temperatura:** campo modificabile sempre visibile nella schermata di misura.

## 7. Criteri di Accettazione Quantitativi
- Errore di distanza ≤ 1 m a 70 m, dopo calibrazione del profilo.
- Errore di Δt ≤ 5 ms su segnale sintetico con due impulsi distanziati di 1.000 s.
- Deviazione standard dell'offset di latenza di AudioRecord misurata e documentata su almeno 20 prove.
- Con useClicker = true, un pre-click a 150 ms dal battito principale non deve essere scambiato per il rilascio.
- Il criterio di stabilità non deve attivarsi durante l'appoggio: test con telefono appoggiato da fermo e da in movimento.
- Il fit non include nessun punto senza Δt valido o senza conferma in caso di outlier.

## 8. Piano a Step

### STEP 1: Persistenza profili (previa conferma utente)
Criterio: profili salvati e recuperati dopo riavvio.

### STEP 2: WeatherRepository e BallisticCalculator (Kotlin puro)
Criterio: unit test JUnit a 15, 50, 70 m, temperature 5 °C e 30 °C, angoli ±15°, scarto < 0.1 m.

### STEP 3: Pipeline DSP con AudioRecord
Criterio: su WAV sintetico, due impulsi a 1.000 s → Δt = 1.000 s ± 0.005 s. Pre-click a 150 ms ignorato con useClicker = true.

### STEP 4: Stabilità e ShotOrientationManager
Criterio: pitch bloccato a +15° anche dopo appoggio a 0°. Motore armato solo a telefono fermo.

### STEP 5: UI, calibrazione guidata, fit del modello, risultati
Criterio: flusso completo su dispositivo reale, con log dei tempi e distanze. Il fit si aggiorna solo con punti validi o confermati.