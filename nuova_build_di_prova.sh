#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Una build di prova (D<n>) in un colpo solo: compila, controlla, copia l'APK
# in download_page/, riscrive index.html con versione, peso e data, e toglie
# l'APK della build precedente.
#
#   ./nuova_build_di_prova.sh                  # nota presa da download_page/nota.html
#   ./nuova_build_di_prova.sh -n "<p>novità</p>"
#   (su Windows: doppio click su nuova_build_di_prova.cmd)
#
# Cosa fa, in ordine:
#   1. ricava dall'indice il numero D successivo e compila
#      `./gradlew :app:assembleRelease -PtestBuild` (log in build_d_test.log);
#      se la compilazione fallisce si ferma subito, senza toccare download_page/
#      e rimettendo il contatore com'era, così il numero D non si brucia;
#   2. legge l'etichetta dall'APK prodotto: se non è "x.y.z (D<n>)" o non è
#      quella attesa, si ferma (niente pagina che promette una D diversa);
#   3. verifica la firma con apksigner, se lo trova nell'SDK;
#   4. copia l'APK in download_page/miraglio-<versione>-D<n>.apk;
#   5. rigenera index.html dal modello download_page/index.template.html con i
#      segnaposto {{VERSIONE}}, {{VERSIONE_BASE}}, {{NUMERO}}, {{PRECEDENTE}},
#      {{FILE}}, {{PESO}}, {{DATA}} e {{NOTA}};
#   6. toglie gli altri APK della cartella (solo dopo che il nuovo c'è).
#
# La nota è l'unica parte che va scritta a mano: è il paragrafo che racconta
# cosa c'è di nuovo. Senza -n si usa download_page/nota.html, che quindi va
# aggiornato prima di una build con novità diverse.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")"

PAGINA=download_page
MODELLO="$PAGINA/index.template.html"
NOTA_FILE="$PAGINA/nota.html"
CONTATORE=debug_version.txt
LOG=build_d_test.log
APK_BUILD=app/build/outputs/apk/release/app-release.apk
METADATA=app/build/outputs/apk/release/output-metadata.json

nota=""
while getopts ":n:h" opzione; do
    case "$opzione" in
        n) nota=$OPTARG ;;
        h) awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        :) echo "Manca il testo dopo -n." >&2; exit 2 ;;
        *) echo "Opzione sconosciuta: -$OPTARG (usa -h)." >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
[[ $# -gt 0 && -z $nota ]] && nota=$1

if [[ ! -f $MODELLO ]]; then
    echo "Errore: manca $MODELLO (è il modello della pagina)." >&2
    exit 1
fi
if [[ -z $nota ]]; then
    if [[ -f $NOTA_FILE ]]; then
        nota=$(cat "$NOTA_FILE")
        echo "== Nota letta da $NOTA_FILE: deve descrivere QUESTA build, non la precedente."
    else
        echo "Errore: nessuna nota. Passa -n \"<p>novità...</p>\" o crea $NOTA_FILE." >&2
        exit 1
    fi
fi

# --- numero D atteso ------------------------------------------------------
precedente=0
if [[ -f $CONTATORE ]]; then
    letto=$(tr -d '\r\n' < "$CONTATORE")
    [[ $letto =~ ^[0-9]+$ ]] && precedente=$letto
fi
atteso=$((precedente + 1))

# --- build ----------------------------------------------------------------
echo "== Build di prova D$atteso (log in $LOG)"
if ! ./gradlew :app:assembleRelease -PtestBuild >"$LOG" 2>&1; then
    echo "La build è fallita: la pagina e gli APK in $PAGINA/ non sono stati toccati." >&2
    printf '%s\n' "$precedente" > "$CONTATORE"
    echo "Contatore rimesso a $precedente: la prossima build riproverà la D$atteso." >&2
    echo "--- ultime righe di $LOG ---" >&2
    tail -n 30 "$LOG" >&2
    exit 1
fi

if [[ ! -f $APK_BUILD ]]; then
    echo "Errore: la build è finita ma non c'è $APK_BUILD." >&2
    exit 1
fi

# --- etichetta -------------------------------------------------------------
if [[ ! -f $METADATA ]]; then
    echo "Errore: manca $METADATA: impossibile leggere l'etichetta della build." >&2
    exit 1
fi
versione=$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$METADATA" | head -n 1)
codice=$(sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' "$METADATA" | head -n 1)
if [[ ! $versione =~ ^(.+)\ \(D([0-9]+)\)$ ]]; then
    echo "Errore: la build non porta l'etichetta D (versionName \"$versione\")." >&2
    echo "Serve -PtestBuild: la pagina non è stata toccata." >&2
    exit 1
fi
base=${BASH_REMATCH[1]}
numero=${BASH_REMATCH[2]}
etichetta="D$numero"
if (( numero != atteso )); then
    echo "Errore: atteso D$atteso, la build dice D$numero ($CONTATORE fuori posto)." >&2
    exit 1
fi
echo "== Etichetta: \"$versione\", versionCode ${codice:-sconosciuto}"

# --- firma ----------------------------------------------------------------
sdk=""
if [[ -f local.properties ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tr -d '\r')
fi
sdk=${sdk:-${ANDROID_HOME:-}}
apksigner=""
if [[ -n $sdk && -d $sdk/build-tools ]]; then
    for cartella in $(ls -1 "$sdk/build-tools" | sort -V); do
        if [[ -f $sdk/build-tools/$cartella/apksigner.bat ]]; then
            apksigner=$sdk/build-tools/$cartella/apksigner.bat
        elif [[ -f $sdk/build-tools/$cartella/apksigner ]]; then
            apksigner=$sdk/build-tools/$cartella/apksigner
        fi
    done
fi
if [[ -n $apksigner ]]; then
    if ! "$apksigner" verify "$APK_BUILD" >>"$LOG" 2>&1; then
        echo "Errore: l'APK non ha una firma valida (dettagli in $LOG): non lo copio." >&2
        exit 1
    fi
    echo "== Firma verificata ($(basename "$(dirname "$apksigner")"))"
else
    echo "== Attenzione: apksigner non trovato, firma non verificata."
fi

# --- copia e pulizia -------------------------------------------------------
mkdir -p "$PAGINA"
destinazione="$PAGINA/miraglio-$base-D$numero.apk"
cp "$APK_BUILD" "$destinazione"
echo "== APK copiato in $destinazione"

# Etichetta della build che c'era prima, per il piè di pagina.
etichettaPrecedente="build precedente"
for vecchio in "$PAGINA"/miraglio-*.apk; do
    [[ -f $vecchio ]] || continue
    [[ $vecchio == "$destinazione" ]] && continue
    if [[ $(basename "$vecchio") =~ -D([0-9]+)\.apk$ ]]; then
        etichettaPrecedente="D${BASH_REMATCH[1]}"
    fi
done
for vecchio in "$PAGINA"/miraglio-*.apk; do
    [[ -f $vecchio ]] || continue
    [[ $vecchio == "$destinazione" ]] && continue
    rm -f "$vecchio"
    echo "== Tolto $(basename "$vecchio")"
done

# --- pagina ---------------------------------------------------------------
byte=$(wc -c < "$destinazione" | tr -d ' \r\n')
peso=$(awk -v b="$byte" 'BEGIN { printf "%.1f", b / 1000000 }')
peso=${peso/./,}
data=$(date +"%d/%m/%Y alle %H:%M")

# Sostituzione letterale: la nota contiene entità come &ugrave;, e nella
# `${testo//segnaposto/valore}` di bash 5.2+ la "&" del valore rimetterebbe
# dentro il segnaposto appena sostituito.
sostituisci() { # $1 testo, $2 segnaposto, $3 valore
    local testo=$1 testa=""
    while [[ $testo == *"$2"* ]]; do
        testa+=${testo%%"$2"*}$3
        testo=${testo#*"$2"}
    done
    printf '%s' "$testa$testo"
}

html=$(cat "$MODELLO")
html=$(sostituisci "$html" '{{VERSIONE_BASE}}' "$base")
html=$(sostituisci "$html" '{{VERSIONE}}' "$versione")
html=$(sostituisci "$html" '{{NUMERO}}' "$etichetta")
html=$(sostituisci "$html" '{{PRECEDENTE}}' "$etichettaPrecedente")
html=$(sostituisci "$html" '{{FILE}}' "$(basename "$destinazione")")
html=$(sostituisci "$html" '{{PESO}}' "$peso MB")
html=$(sostituisci "$html" '{{DATA}}' "$data")
html=$(sostituisci "$html" '{{NOTA}}' "$nota")

if [[ $html == *'{{'* ]]; then
    echo "Errore: nel modello è rimasto un segnaposto senza valore." >&2
    exit 1
fi

provvisorio="$PAGINA/.index.html.tmp"
printf '%s\n' "$html" > "$provvisorio"
mv -f "$provvisorio" "$PAGINA/index.html"
echo "== Pagina riscritta: $PAGINA/index.html"

echo
echo "Fatto: $versione"
echo "  file   $destinazione"
echo "  peso   $peso MB ($byte byte)"
echo "  data   $data"
echo "  sha256 $(sha256sum "$destinazione" | cut -d' ' -f1)"
echo "  pagina $PAGINA/index.html (sostituisce la $etichettaPrecedente)"
echo
echo "Ricorda: la nota viene da $NOTA_FILE — se le novità non sono più quelle,"
echo "aggiornalo prima della prossima build. Il server statico sulla porta 8000"
echo "serve già la cartella, non va riavviato."
