# Miraglio — come si installa sul telefono

![Miraglio — calcolo balistico tiro 3D: bersaglio misurato, app al centro, arciere](https://raw.githubusercontent.com/oraziog/miraglio/master/docs/img/banner-miraglio.jpg)

Questa guida serve a chi vuole **usare** Miraglio, non a chi lo sviluppa: si fa
tutto dal telefono, in cinque minuti, senza computer.

Serve un telefono **Android 8.0 o successivo**. Su iPhone Miraglio non funziona.

Miraglio **non è sul Play Store**: si scarica da GitHub. Una volta installata non
serve più tornarci, perché **gli aggiornamenti se li prende da sola**.

---

## 1. Apri il link della release (dal telefono)

Apri questo indirizzo **sul telefono**, non sul computer:

👉 <https://github.com/oraziog/miraglio/releases/latest>

In cima vedi *Miraglio* seguito dal numero di versione. Scorri fino alla voce
**Assets** e tocca il file che finisce in **`.apk`** (si chiama
`miraglio-<versione>.apk`, è scritto in piccolo sotto al nome: sono circa 120 MB,
conviene scaricarlo in wifi).

## 2. Apri il file scaricato

Quando il download è finito, apri il file: dalla **notifica** di Chrome, oppure da
**File → Download** e toccando `miraglio-<versione>.apk`.

Se compare un avviso tipo *"Per motivi di sicurezza il telefono non può
installare app sconosciute da questa origine"*:

1. tocca **Impostazioni**;
2. attiva **Consenti da questa origine** (in alcuni telefoni si chiama
   *Installa app sconosciute*) per l'app da cui hai aperto il file, cioè Chrome
   o File;
3. torna indietro e tocca di nuovo il file.

## 3. Conferma l'installazione

Android chiede conferma un'ultima volta: tocca **Installa**.

Se compare un avviso di **Play Protect** del tipo *"Play Protect non riconosce
questa app"*, scegli **Installa comunque** (a volte è sotto *Altri dettagli*).
Play Protect lo chiede per le app che **non arrivano dal Play Store** e che non
ha ancora visto: è la stessa richiesta che fa per qualsiasi app installata da
fuori, e può anche proporti di mandarla a Google per un controllo. Non
significa che dentro ci sia qualcosa di strano: il file arriva dal repository
ufficiale di Miraglio.

## 4. Al primo avvio: dai i permessi e calibra

L'app chiede i permessi dove servono davvero: appena si apre chiede la
**fotocamera** (senza, non si misura), mentre la **posizione** la chiede quando
apri *Verifica distanza* o salvi un luogo. Se dici no per sbaglio, riapri quella
schermata e la richiesta torna.

Poi fai la cosa più importante: **la calibrazione**. Miraglio calcola le distanze
dalla fotocamera, e la fotocamera di ogni telefono è diversa: **senza calibrare
la misura può sbagliare anche del 30%**. Serve un metro a nastro e cinque
minuti.

👉 Istruzioni nella sezione *Calibrazione della focale* del
[manuale d'uso](https://github.com/oraziog/miraglio/blob/master/MANUALE.md)

La calibrazione resta salvata **solo su quel telefono**: se installi Miraglio su
un altro telefono, va rifatta da zero. Non copiarla da un telefono all'altro,
darebbe misure sbagliate.

## Da qui in avanti si aggiorna da sola

Non serve ripassare da GitHub. Quando pubblichiamo una versione nuova l'app avvisa
all'avvio, oppure glielo chiedi tu da **menu ☰ → Impostazioni → Aggiornamenti →
Controlla aggiornamenti**. L'aggiornamento non cancella niente: **luoghi, foto e
calibrazioni restano**.

> **Se hai installato Miraglio da una release precedente alla 1.0.3**, quella
era firmata con una chiave diversa e **l'aggiornamento non si installa sopra**:
Android lo rifiuta. Cancella Miraglio, reinstallala dal link qui sopra e rifai
la calibrazione — i luoghi salvati si perdono. Dalla 1.0.3 in avanti la firma
non cambia più, quindi questo passaggio si fa una volta sola.

---

## Se qualcosa non funziona

| Cosa vedi | Cosa fare |
|---|---|
| *"App non installata"* appena tocchi il file | Manca l'autorizzazione del punto 2: attiva *Consenti da questa origine* e riprova. |
| *"Pacchetto non valido"* | Il download si è interrotto: cancella il file scaricato, riapri il link e scaricalo di nuovo (in wifi). |
| Play Protect blocca l'installazione | Tocca **Installa comunque** (a volte è sotto *Altri dettagli*). |
| Android rifiuta di **aggiornare** un Miraglio già installato | La versione installata è firmata con una chiave diversa dall'APK che stai installando: succede con una release precedente alla **1.0.3** o con una copia compilata da te. Va disinstallata prima, perdendo i dati salvati. |
| La distanza sbaglia di parecchio | Non hai calibrato (punto 4): è il caso più comune. |
| In *Verifica distanza* resta *in attesa del fix* | Serve il satellite: mettiti all'aperto, lontano da muri e alberi. |

---

## Come calcola la distanza (per capire cosa si guarda in schermata)

![Infografica: calcolo della distanza compensata — fisica del tiro in pendenza, CD = LoS x cos θ, regolazione del mirino](https://raw.githubusercontent.com/oraziog/miraglio/master/docs/img/infografica-distanza.jpg)

In breve: la fotocamera misura la **linea di vista** verso il bersaglio,
l'inclinometro del telefono misura di quanto punti in alto o in basso e l'app
riporta la misura sulla **distanza orizzontale** (`× cos θ`): è quello che in
schermata vedi scritto *Distanza compensata*. L'infografica mette insieme
tutto, compresa la parte sul mirino (che non è una funzione dell'app ma serve a
capire i numeri). I dettagli sono nel
[manuale d'uso](https://github.com/oraziog/miraglio/blob/master/MANUALE.md).

<!-- fine-guida-installazione -->
