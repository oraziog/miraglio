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
5. Inserisci la **dimensione nota** del bersaglio, nell'unità scritta sul campo
   (cm, oppure pollici se hai scelto le unità imperiali). La distanza si aggiorna
   in tempo reale.

Le linee **orizzontali** misurano l'**altezza**, quelle **verticali** la **larghezza**
(bottone per scambiare la direzione).

---

## Le tacche sul mirino (MOA e pollici a 20 iarde)

Sul bordo sinistro dell'immagine **in mira** c'è una **scala di tacche** con lo
zero al centro dell'inquadratura. Serve a confrontare il bersaglio con il
proprio mirino senza togliere l'occhio da lì.

Sul **fermo immagine** le tacche non ci sono più: lì la misura la fa già la
distanza fra le due linee, e le tacche coprivano la foto proprio nel momento in
cui si controlla che le linee stiano sui bordi dell'oggetto.

Accanto alla scala l'app scrive due numeri, e sono sempre **la stessa cosa detta
in due modi**:

- **Quanto vale una tacca**: `Ogni tacca = 10 MOA = 2,1 in/20yd`.
- **Quanto copre il bersaglio**: `Bersaglio = 140 MOA = 29,3 in/20yd`, calcolato
  dalla dimensione nota del bersaglio e dalla distanza stimata. Se hai inquadrato
  un paglione da 122 cm e l'app dice 30 m, il bersaglio copre 140 MOA: è il
  numero da confrontare con il reticolo che hai sul tuo mirino.

Le due unità sono scritte insieme perché i mirini sono **tarati in pollici a 20
iarde** (`in/20yd`, quello che in inglese si chiama IPHY) mentre i conti si
fanno in **MOA**: 1 MOA è 1/60 di grado, e 1 pollice a 20 iarde vale 4,77 MOA.

Il passo delle tacche **non è fisso**, ed è una scelta dichiarata invece che
nascosta: a 1x una fotocamera di telefono ha una focale di circa 1200 px, quindi
1 MOA vale **0,35 pixel** e tacche da 1 MOA sarebbero invisibili. L'app
allarga il passo finché le tacche si vedono (100 MOA a 1x, 5 MOA con un tele
effettivo) e **ti scrive quanto vale**, così una scala grossolana è dichiarata
e non finta. Zoomando, il passo si stringe da solo.

Le tacche compaiono solo quando c'è tutto quello che serve: focale calibrata,
dimensione nota del bersaglio e distanza stimata. Senza uno dei tre non si
disegna niente — una scala senza numeri accanto sarebbe solo decorazione.

---

## Rotazione e conferme con le mani

Nella barra in alto, accanto al pulsante della modalità outdoor, c'è quello
della **rotazione**: Miraglio è nato in verticale e ci resta finché non lo
sblocchi tu. Un tocco **consente la rotazione** (se il telefono ha la rotazione
automatica attiva), un altro la **blocca** di nuovo. La scelta resta anche alla
riapertura e vale **solo dentro Miraglio**: non cambia la rotazione delle altre
app né l'impostazione di sistema.

Lo stesso interruttore è in **Impostazioni**, sotto la stessa preferenza: il
pulsante in barra e la voce nelle Impostazioni sono due modi di fare la stessa
cosa, e cambiarla in un posto la cambia anche nell'altro.

### Cosa cambia in orizzontale

Con la rotazione libera, le due schermate che si usano con il telefono in mano
si dispongono da sole:

- **Mira**: l'immagine a sinistra (circa il 60% della larghezza) e i pannelli a
destra — risultato, linee, distanza — invece di schiacciare l'inquadratura in
una striscia sopra i pulsanti.
- **Misurazione** (fermo immagine con le linee): la foto a sinistra e il pannello
dei parametri a destra, che è dove serve quando devi leggere il numero e
muovere le linee con le due dita.
- **Verifica distanza** (GPS): il contenuto scorre, così quello che non entra in
altezza resta raggiungibile.

In verticale non cambia niente: è la disposizione di sempre.

Quando salvi un **punto di calibrazione** o un **luogo**, il telefono dà un
**tocco breve**: serve a chi sta guardando il bersaglio e non lo schermo. Arriva
solo se il salvataggio è **riuscito** — un ronzio su un salvataggio fallito
sarebbe una bugia — e rispetta l'interruttore di sistema del feedback tattile:
se l'hai spento, l'app non insiste.

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

### La distanza dal GPS (quando il metro non c'è)

Se il bersaglio è in un punto che il GPS sa indicare, la distanza non si digita:
la prende il satellite.

1. **Vai davanti al bersaglio** (dove sta l'oggetto di dimensione nota) e premi
   **Segna bersaglio**: il telefono registra il punto e nel messaggio scrive con
   quale precisione l'ha registrato.
2. **Torna al posto di mira**, ferma il frame e allinea le linee come al solito.
3. Premi **Distanza GPS**: il campo si compila da solo con la distanza fra dove
   sei e il punto segnato, più l'errore stimato (±). Il punto resta salvato fra
   una sessione e l'altra: finché il bersaglio non si muove, lo segni una volta.

Due cose da sapere:

- Il **GPS sbaglia di qualche metro**, quindi questa strada va bene per una
  calibrazione buona e per le distanze lunghe; per la massima precisione rimane
  il metro a nastro.
- La distanza usa solo un **fix fresco**: se il telefono sta ancora cercando i
  satelliti (o l'ultimo fix è vecchio), il messaggio te lo dice e il numero
  arriva appena il fix è pronto, senza premere due volte.

### La calibrazione è di quel modello di telefono

La focale dipende da **lente e sensore del dispositivo**: è una proprietà fisica
della camera, identica per tutte le distanze ma **diversa da telefono a telefono**.

- La calibrazione vale **per quel modello**: importata su un altro telefono dà
  numeri del telefono sbagliato.
- Un telefono **dello stesso modello** parte già calibrato: è il caso vero in
  cui mandarla ha senso (stessa compagnia, stesso attrezzo).
- **Ogni altro telefono** va calibrato da zero, come sempre.

### Mandare e ricevere una calibrazione

Dalla schermata **Calibrazione**, il pulsante **"Condividi o importa"** apre lo
scambio con un altro telefono. Serve a chi ha due telefoni uguali, o a chi
sostituisce il telefono con lo stesso modello senza rifare tutto il lavoro.

**Per mandarla** (telefono A): la card dice **da quale telefono** parte (marca
e modello) e quanti punti e lenti contiene. Due modi, lo stesso testo:

- **Condividi il testo**: un messaggio normale (WhatsApp, email...), da incollare
  dall'altra parte.
- **Mostra il QR**: l'altro telefono lo inquadra e legge tutto in un colpo, senza
  errori di copia-incolla. Con molti punti il QR non basta più e l'app te lo dice
  (resta la condivisione come testo).

**Per importarla** (telefono B): incolla il testo, oppure **Inquadra il QR**.
Prima di scrivere qualsiasi cosa l'app ti mostra **cosa sta per importare**:
la **marca e il modello del telefono che l'ha mandata**, quanti punti sono, e
lente per lente dove finiscono ("Lente 2/3 → Lente 2/4"). È l'informazione che
ti dice se quella calibrazione ha senso sul tuo telefono: se viene da un modello
diverso, annulla.

Quattro cose da sapere su come importa:

- Le lenti si accoppiano **per posizione** (1a con 1a, 2a con 2a): l'ordine è
  l'unica cosa confrontabile fra due telefoni. Se il numero di lenti non
  coincide, l'app te lo scrive — i numeri possono non corrispondere.
- Una lente del file che **qui non esiste** si salta, non si appoggia a un'altra:
  una calibrazione giusta scritta sulla lente sbagliata è peggio di nessuna
  calibrazione.
- Importare **sostituisce** la calibrazione di quella lente su questo telefono.
  Non si somma alla tua: sarebbe la media fra due telefoni, cioè un numero che
  non vale per nessuno dei due.
- Un testo rovinato dal copia-incolla (tagliato, o con un carattere cambiato) viene
  **rifiutato**: il messaggio porta con sé una somma di controllo, e senza quella
  un errore entrerebbe nella calibrazione in silenzio.
- La focale importata **si verifica misurando**: la prima misura di prova su un
  bersaglio noto viene confrontata con la focale arrivata dal file. Se lo scarto
  supera il **15%** l'app avvisa — quei numeri non descrivono questa fotocamera
  (modello diverso, o punto misurato male) — invece di lasciar credere che
  valgano qui.

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

### Vedere e cancellare i punti salvati

La card della calibrazione dice **quale lente stai calibrando** — "Stai
calibrando Lente 2/3", la stessa numerazione del mirino — e in fondo elenca i
punti **lente per lente** ("Lente 2/3 · punti: 3"), con quella in corso in
grassetto. Serve dopo qualche mese d'uso, quando i punti sono più d'uno e non
si sa più quale obiettivo li ha. La lente si cambia dove si calibra, cioè nel
fermo immagine di calibrazione (bottone **Lente x/N**).

Ci sono due cancellazioni, entrambe da confermare prima di eseguirle:

- **Elimina tutti i punti di questa lente**: la lente in calibrazione riparte da
  zero, le altre non si toccano.
- **Elimina la calibrazione di tutte le lenti**: compare solo quando esistono
  davvero i punti di un'altra lente, ed è l'uscita per chi ha accumulato misure
  sbagliate su più obiettivi.

Quello che hai già misurato non cambia: la calibrazione serve ai calcoli da qui
in avanti, non riscrive i luoghi salvati.

### Il grafico dei punti

Sotto i valori c'è il grafico: **distanza** sull'asse orizzontale, **focale
implicita** su quello verticale, con l'unità scritta sugli assi (metri o iarde
secondo l'unità scelta, sempre px per la focale). Tutti e due gli assi hanno i
**numeri delle tacche**: la distanza in basso (5, 10, 15…) e la focale a
sinistra (600, 800, 1000…), con una linea leggera che attraversa il grafico a
ogni valore. Così si legge non solo a che distanza ma anche **a che focale** sta
ogni punto, e quanto è lontano dalla riga dove dovrebbe stare; da ogni punto
scende inoltre un **trattino leggero** fino all'asse delle distanze. I punti
corretti stanno sulla retta di regressione; quelli misurati male sono
cerchiati in **rosso**.

Quando un punto è fuori linea l'app scrive **a quali distanze rifare la misura**
e sotto compare un pulsante per ciascuna distanza (**"Rifai 5 m"**): aprono la
schermata di calibrazione con distanza, dimensione del bersaglio e asse già
compilati, quindi basta inquadrare, segnare le due linee e salvare. Fatta la
nuova misura, cancella il punto vecchio: rimisura **quella distanza**, non tutta
la calibrazione. Un punto sbagliato pesa molto sulla media, soprattutto se per
quell'asse ci sono pochi punti (con tre misure di altezza, ognuna vale un terzo
di fy).

Sotto il grafico c'è anche lo **scarto massimo**: se nessun punto è rosso ma il
numero è vicino al 10%, la calibrazione è ancora dentro la tolleranza ma rifare
le misure più scostate la rende più stretta. Con uno scarto basso non c'è niente
da rifare, anche se due punti sembrano lontani sulla scala del disegno.

**Se hai calibrato con una versione precedente non devi rifare niente.** Le
misure prese quando l'app analizzava a metà risoluzione vengono riportate da
sole a quella attuale, e la focale si ricalcola da quelle: le distanze erano
giuste prima e restano giuste. Le versioni precedenti però mescolavano i due
tipi di misura quando se ne aggiungeva una nuova dopo l'aggiornamento, e la
focale usciva circa un quarto troppo alta; l'app se ne accorge all'avvio e
rimette i punti d'accordo. Se sul grafico vedi un punto isolato che sta al
doppio degli altri, aggiorna e riapri la schermata: se resta lì, è una misura
davvero da rifare. Rifare tutta la calibrazione serve solo a stringerla, non a
correggerla.

### Zoom e calibrazione

La calibrazione si fa a zoom di base (1x). Durante le misure lo **zoom viene
compensato automaticamente**: puoi zoomare liberamente senza ricalibrare.

---

## Come Miraglio calcola la distanza

![Infografica: calcolo della distanza compensata — fisica del tiro in pendenza, CD = LoS × cos θ, regolazione del mirino](docs/img/infografica-distanza.jpg)

L'infografica riassume il ragionamento in tre blocchi:

- **La fisica del tiro in pendenza**: la traiettoria dipende dalla distanza
  *orizzontale*, non da quella che guardi con gli occhi. Puntando in salita o
  in discesa la distanza di vista (LoS) è più corta o più lunga di quella
  reale: è l'errore che fa credere di dover correggere mirino.
- **Il calcolo**: `CD = LoS × cos(θ)`. È esattamente quello che fa l'app:
  la fotocamera misura la linea di vista, l'**inclinometro** del telefono dà
  l'angolo θ e in schermata leggi il risultato come *Distanza compensata*
  (con l'angolo accanto). Valori tipici del coseno: 10° → 0,98, 20° → 0,94,
  30° → 0,87, cioè a 30° di pendenza la distanza grezza mente del 13%.
- **Regolazione del mirino**: punte, distanza di innesto e compensazioni sono
  **riferimento generale** sull'attrezzo, non una funzione dell'app: Miraglio
  misura la distanza, il mirino lo regoli tu. Resta utile per capire perché a
  distanza uguale i punti di mira non coincidono.

---

## Lingua

Miraglio parla **italiano, spagnolo e inglese**, e la lingua si sceglie dentro
l'app: **Impostazioni → Lingua**, con quattro voci.

- **Come il telefono**: segue la lingua di Android. È la scelta di partenza.
- **Italiano** / **Español** / **Inglese**: la lingua la decidi tu, anche se il
  telefono è in un'altra lingua.

Il cambio è immediato, non c'è niente da riavviare. Cambia **tutto**: le
etichette, gli avvisi, il testo che mandi quando condividi un luogo e anche il
modo di scrivere numeri e date (in italiano `20,5 m` e `24/10/2026`, in inglese
`20.5 m` e `Oct 24, 2026`, in spagnolo `20,5 m` e `24 oct 2026`).

Da **Android 13 in poi** la stessa voce esiste anche nelle impostazioni di
sistema (*App → Miraglio → Lingua*): è la stessa preferenza vista da due posti,
quindi quello che scegli qui lo ritrovi là. Su Android 12 e precedenti il
selettore dentro l'app è l'unico modo di cambiarla.

Su un telefono in una lingua diversa da quelle tradotte, e senza nessuna scelta,
Miraglio si apre in **inglese**.

---

## Unità di misura

Il sistema di unità si sceglie a parte dalla lingua, sempre nelle Impostazioni
(**Unità di misura**), con due voci:

- **Metriche (m, cm)**: metri per le distanze, centimetri per la dimensione dei
  bersagli. È la scelta di partenza.
- **Imperiali (yd, in, ft)**: iarde per le distanze, pollici per la dimensione dei
  bersagli, piedi per l'incertezza della misura.

Sono due cose indipendenti apposta: un inglese che tira in Italia misura in metri,
un italiano a un campo americano in iarde. **Cambiare lingua non cambia le
unità**, e cambiare unità non cambia la lingua.

Ecco cosa cambia, con un esempio di ciascuno:

| Grandezza | Metriche | Imperiali |
|---|---|---|
| Distanza del bersaglio | `30,0 m` | `32,8 yd` |
| Percorso a piedi (verifica GPS) | `120,5 m` | `131,8 yd` |
| Dimensione del bersaglio | `1,22 m` (o `122 cm`) | `48,03 in` |
| Accuratezza GPS / errore stimato | `±5,0 m` | `±16,4 ft` |
| Scarto stima-misura | `+1,2 m` | `+1,3 yd` |

Cosa **non** cambia: dentro l'app distanze, dimensioni e focale restano in metri e
in pixel. I luoghi che hai già salvato e le calibrazioni che hai fatto valgono
identici nei due sistemi: il sistema di unità è solo il modo di scrivere i numeri,
non un modo diverso di calcolarli.

I campi dicono la loro unità e la seguono. Nella schermata di mira, per esempio, il
campo della dimensione chiede i **centimetri** o i **pollici** a seconda della
scelta: scrivere `48` in pollici dà lo stesso risultato di scrivere `122` in
centimetri. Se cambi sistema mentre la schermata è aperta, il numero che avevi già
scritto **viene riscritto** nella nuova unità: `130` cm diventa `51,2` in, perché
la stessa cifra letta come pollici sarebbe un bersaglio 2,54 volte più grande.

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

## Far installare Miraglio a un altro arciere

In **Impostazioni** c'è la card **Installa su un altro telefono**:

- **Mostra QR e link** apre il QR del link della release più recente. Fai
  inquadrare il QR con il telefono dell'altro arciere: gli si apre la pagina con
  le istruzioni di installazione e l'APK da scaricare.
- **Condividi** manda il messaggio con dentro il link: va bene per chi è lontano
  (WhatsApp, email...).
- **Condividi il QR come immagine** manda il QR come foto, per farlo inquadrare
  da un altro schermo o da un foglio stampato.

Serve anche a te, quando cambi telefono.

Chi installa deve fare due cose che Android chiede sempre: autorizzare
*installa app sconosciute* e confermare l'installazione. Poi **deve calibrare**:
la calibrazione non si copia da un telefono all'altro (vedi *La calibrazione vale
solo sul tuo telefono*).

---

## Verifica distanza (GPS)

Serve a misurare a piedi la distanza fra due punti e confrontarla con quella
stimata dall'app.

1. Mettiti **all'aperto** e aspetta che in basso compaia
   *Fix GPS disponibile (±… m)*. In casa, sotto gli alberi o fra i palazzi il
   satellite non aggancia: senza fix buono la misura non parte.
2. Premi **MISURA**: la **partenza si blocca sul fix di quel momento** (serve un
   fix di adesso entro ±10 m, confermato dal fix successivo). Fino ad allora
   vedi *In attesa del fix di partenza*: è l'app che si rifiuta di partire da
   una posizione vecchia, che sposterebbe la partenza di decine di metri.
3. Cammina fino al punto finale. Il numero grande è la distanza **in linea
d'aria** dalla partenza, quella sotto è il **percorso a piedi** (quanto hai
   camminato davvero).
4. Premi **FINO A QUI**: si chiude solo con un fix **di adesso**; se il numero
   sotto è rosso lampeggiante il fix è vecchio o scadente e conviene aspettare.
5. Confronta la misura con la **stima dell'app** e lo scarto (tolleranza ±3 m).

Note utili:

- L'**errore stimato** (±x m) accompagna sempre il risultato e finisce anche nel
  luogo salvato e nel messaggio condiviso: la misura resta valida, ma chi la
  legge sa di quanto può sbagliare.
- Oltre **5 m** di errore stimato l'app chiede conferma prima di chiudere.
- Se la misura va in background (schermo spento) il GPS resta attivo per
  **10 minuti**: il tempo di arrivare al punto finale a piedi.
- Se il processo viene chiuso, la misura riprende da dove era **solo se** non è
  più vecchia di 15 minuti: oltre, partenza e percorso non valgono più.

---

## Luoghi salvati

Ogni scatto e ogni misura GPS si possono **salvare** (menu → *Luoghi salvati*):
foto, misura, coordinate e bussola restano sul telefono, tutto offline.Nel dettaglio di un luogo ci sono due cose da ricordarsi:

### Note libere

Oltre all'**etichetta** — che è solo un nome, e di default è la data e l'ora —
c'è un campo **Note** per quello che dopo una settimana non ricordi più:
"facciata nord", "misurato con zoom 2x", "dubbio sulla distanza". Si scrive e si
salva col pulsante **Salva** (o con "fatto" sulla tastiera): finché il testo è
quello di prima il pulsante resta spento, e dopo il salvataggio compare
"Salvato ✓". Le note **non entrano nella condivisione**: sono un appunto tuo, non
qualcosa da mandare a chi riceve il luogo.

### Coordinate copiate con un tap lungo

Sulla riga delle coordinate, **tieni premuto**: `lat, lon` finiscono negli
appunti (sei cifre, punto decimale, **senza** l'accuratezza dietro), pronti da
incollare in un'altra app — Maps, un foglio, un messaggio. Sotto alla card c'è
la riga che te lo ricorda, perché il gesto di per sé non si vede. Un tocco breve
non fa niente: il tap lungo è scelto apposta, così una distrazione non butta via
quello che stavi leggendo. Lo stesso tap lungo funziona anche **nell'elenco dei
Luoghi**, senza aprire il dettaglio, e lì la nota compare in anteprima sotto
l'etichetta.

---

## Suggerimenti per la precisione

- Misura sempre distanza e dimensione note con strumenti affidabili.
- Preferisci oggetti grandi nel fotogramma tra le due linee.
- Usa più punti di calibrazione (2-3+), a distanze diverse tra loro.
- Inquadra il bersaglio e mantieni il telefono fermo prima dello scatto
  (l'app attiva la stabilizzazione e usa esposizioni brevi per ridurre il mosso).
- Verifica la calibrazione con una misura di prova su una distanza nota.