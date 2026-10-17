package com.miraglio.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Disposizione delle schermate che guardano qualcosa attraverso la fotocamera:
 * l'immagine e i pannelli.
 *
 * In verticale i pannelli stanno **sotto** l'immagine (ed e' la disposizione di
 * sempre); in orizzontale **accanto**, perche' li' l'altezza e' poca e
 * l'immagine finirebbe in una striscia: il mirino si guarda, i pannelli si
 * leggono, e le due cose non devono contendersi lo stesso spazio.
 *
 * L'immagine prende lo spazio che **resta** dopo i pannelli (in verticale con
 * il `weight`). E' questo che rende gestibile la tastiera: quando si apre,
 * lo spazio in meno lo paga l'immagine, e i campi di testo restano visibili
 * sopra la tastiera. Se invece lo spazio venisse tolto ai pannelli (per
 * esempio con `imePadding` *dentro* un pannello ad altezza fissa) il pannello
 * si schiaccerebbe fino a zero e i campi sparirebbero mentre si scrive.
 *
 * Ai due blocchi si passa il [Modifier] della propria area: cosi' chi li
 * scrive non deve sapere in che verso e' girato il telefono.
 *
 * La usano la schermata Misura e la calibrazione della focale: sono lo stesso
 * gesto (inquadra, ferma l'immagine, scrivi due numeri), quindi devono
 * comportarsi allo stesso modo davanti alla tastiera.
 */
@Composable
internal fun AimLayout(
    landscape: Boolean,
    modifier: Modifier = Modifier,
    image: @Composable (Modifier) -> Unit,
    panels: @Composable (Modifier) -> Unit
) {
    if (landscape) {
        Row(modifier = modifier) {
            // L'immagine prende la parte piu' larga: e' quella che si guarda.
            image(Modifier.weight(1.45f).fillMaxHeight())
            panels(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier = modifier) {
            image(Modifier.fillMaxWidth().weight(1f))
            panels(Modifier.fillMaxWidth())
        }
    }
}
