# Miraglio — Manuale d'uso

**Miraglio** stima la distanza del bersaglio con la fotocamera del telefono e calcola
traiettoria e mira per tiro con l'arco. Questa guida spiega come usare al meglio
l'app, con un focus sulla **calibrazione** che è la chiave della precisione.

---

## Uso rapido

1. L'app si apre sulla **schermata di misura**. Inquadra il bersaglio.
2. Pizzica con due dita per **zoomare** sul bersaglio.
3. Premi **Fermo immagine** (o **Scatta fermo immagine** in calibrazione).
4. Posiziona le **due linee** (verde e blu) sui bordi del bersaglio:
   - tap sull'asse per riselezionare,
   - striscia di **sinistra**: sposta entrambe le linee,
   - striscia di **destra**: allarga o stringe la distanza tra le linee.
5. Inserisci la **dimensione nota** del bersaglio (in cm). La distanza si aggiorna in tempo reale.

Le linee **orizzontali** misurano l'**altezza**, quelle **verticali** la **larghezza**
(bottone per scambiare la direzione).

---

## Calibrazione della focale

La distanza si calcola con il modello *pinhole*: più px occupa il bersaglio nel
fotogramma, più è vicino. Il valore che lega px → metri è la **focale in pixel**.
Per essere precisi l'app deve conoscerla: si misura con la **calibrazione**.

Senza calibrazione l'app usa una focale **teorica** (FOV diagonale 67°, valore
medio): utile come prima prova, ma può sbagliare anche del 30% sul tuo telefono.

### Come calibrare per la massima precisione

1. **Distanza e dimensione note**: usa un oggetto di cui conosci con certezza
   l'altezza (es. un bersaglio da 122 cm) e mettiti a una distanza che puoi
   misurare con precisione (metro a nastro, non a occhio).
2. **Inquadra l'oggetto ben grande**: più pixel occupa tra le linee, più la
   misura è precisa. Per questo è consigliabile zoomare o avvicinarsi.
3. **Allinea le linee esattamente sui bordi** dell'oggetto nel fermo immagine.
4. **Inserisci distanza e dimensione esatte** e salva il punto.
5. **Aggiungi 2-3 punti a distanze diverse**: l'app fa la **media** dei punti,
   riducendo gli errori di una singola misura.
6. **Calibra sia l'altezza sia la larghezza** (linee orizzontali e verticali):
   ogni asse ha la propria focale (fx/fy). Se calibri solo l'altezza, il calcolo
   della distanza con le linee verticali userà comunque la focale teorica.

### La calibrazione vale solo sul tuo telefono

La focale dipende da **lente e sensore del dispositivo**: è una proprietà fisica
della camera, identica per tutte le distanze ma **diversa da telefono a telefono**.

- La calibrazione è salvata **solo sul tuo telefono** e non viene trasferita.
- **Ogni telefono** su cui installi Miraglio va calibrato da zero.
- Non condividere/o copiare i dati di calibrazione tra dispositivi: darebbero
  risultati sbagliati.

### Più obiettivi (lenti)

Se il telefono ha **più di una fotocamera posteriore** (es. ultra-grandangolo,
standard, tele), nelle schermate di misura e calibrazione compare il bottone
**"Lente x/N"** per passare da un obiettivo all'altro:

- Il bottone è disponibile **solo prima del fermo immagine**: una volta
  congelata la foto non si cambia più obiettivo.
- Ogni obiettivo ha la **propria calibrazione** salvata separatamente.
- Una lente **non ancora calibrata** usa automaticamente la focale della
  lente principale (1x) come ripiego: per la massima precisione calibrala.
- Nei telefoni con una sola fotocamera posteriore il bottone non compare.
- Alcuni telefoni (es. diversi Motorola) **espongono alla API Android solo la
  fotocamera principale**: gli obiettivi extra visti nell'app nativa (0.5x,
  macro) sono proprietari e non raggiungibili da altre app. Anche in quel caso
  il bottone non compare, e Miraglio usa la sola lente disponibile.
- Sono incluse anche le lenti esposte solo come *fotocamere fisiche* della
  principale (es. macro o ultra-grandangolo): se un obiettivo non si apre su
  un dispositivo, l'app ripiega automaticamente sulla lente principale.
- Il cambio obiettivo azzera lo zoom (ogni lente ha il suo).

### Zoom e calibrazione

La calibrazione si fa a zoom di base (1x). Durante le misure lo **zoom viene
compensato automaticamente**: puoi zoomare liberamente senza ricalibrare.

---

## Aggiornamenti

L'app si aggiorna da sola quando viene pubblicata una versione nuova, senza
passare dal Play Store e **senza perdere niente di quello che hai salvato**.

- **All'avvio** l'app chiede a GitHub qual è l'ultima versione: è una richiesta
  sola, non viene inviato nessun dato tuo, e se non c'è rete non si vede nulla.
- Puoi anche controllare a mano: **Impostazioni → Aggiornamenti**, dove vedi la
  versione installata e il bottone **Controlla aggiornamenti**.
- Se c'è una versione più recente compare un avviso con le novità. Premi
  **Scarica e installa**: l'APK viene scaricato (con la percentuale) e Android
  apre la sua schermata di conferma. **L'app non installa niente da sola**:
  l'ultima parola è sempre tua.
- La prima volta Android chiede di autorizzare Miraglio a installare app
  ("installa app sconosciute"): l'app apre la schermata giusta, attiva la voce e
  torna indietro.
- Durante il download c'è **Annulla**; con **Più tardi** rimandi
  l'aggiornamento (quella versione non te la ripropone più in quella sessione,
  ma resta disponibile nelle Impostazioni).
- **Luoghi, foto e calibrazioni restano**: si sostituisce l'app, non i dati.

Se l'installazione non parte le cause sono quasi sempre due: manca
l'autorizzazione di cui sopra, oppure il download si è interrotto (in quel caso
basta riprovare: l'app se ne accorge e non ti fa installare un file incompleto).

---

## Suggerimenti per la precisione

- Misura sempre distanza e dimensione note con strumenti affidabili.
- Preferisci oggetti grandi nel fotogramma tra le due linee.
- Usa più punti di calibrazione (2-3+), a distanze diverse tra loro.
- Inquadra il bersaglio e mantieni il telefono fermo prima dello scatto
  (l'app attiva la stabilizzazione e usa esposizioni brevi per ridurre il mosso).
- Verifica la calibrazione con una misura di prova su una distanza nota.