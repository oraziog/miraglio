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
   - Finestra di 50–450 ms per il picco di rilascio. Se non arriva, `RESET` a `READY`.
   - Il picco di rilascio sovrascrive N_rilascio. L'offset utente (ritardo click → rilascio) sposta il riferimento **dal click al rilascio**: con offset 0 si usa il picco, con offset dichiarato si usa click + offset. Sottrarlo a un picco già misurato conterebbe due volte lo stesso ritardo (su un clicker a 150 ms sono ~7 m a 70 m).
3. **Clicker non attivo:** il primo picco ad alta energia avvia N_rilascio.
4. **Impatto:** ricerca del picco su Ramo B tra 0.15 s e 2.20 s dal rilascio. L'ampiezza deve essere inferiore a quella del rilascio.
5. **Fallback:** se non c'è impatto, lo stato va a `DONE_NO_IMPACT`. L'utente può inserire la distanza reale a posteriori. Il tiro viene salvato, ma marcato come non utilizzabile per il fit finché non ha anche un Δt valido.

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