package com.miraglio.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.R
import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.focal.LensSummary
import com.miraglio.ui.i18n.LocalDisplayFormat

/** Cosa sta per essere cancellato: la lente in calibrazione, o tutte. */ 
private enum class ClearScope { LENS, ALL }

/** Padding stretto dei bottoni di cancellazione: sono due, e la card e' densa. */ 
private val ClearButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFocalCapture: () -> Unit = {},
    onNavigateToTransfer: () -> Unit = {},
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val focalPoints by viewModel.focalPoints.collectAsState()
    val focal by viewModel.focal.collectAsState()
    val lensRows by viewModel.lensRows.collectAsState()
    val activeLensPosition by viewModel.activeLensPosition.collectAsState()
    // Cosa si sta cancellando adesso: null = nessuna conferma aperta. La
    // cancellazione e' irreversibile e non c'e' un annulla, quindi si chiede.
    // Usiamo la forma diretta con .value poichè il delegato 'by' non funziona
    // con MutableState.
    var clearScope = remember { mutableStateOf<ClearScope?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.calibration_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FocalCalibrationCard(
                fxPx = focal.first,
                fyPx = focal.second,
                theoreticalPx = viewModel.theoreticalFocalPx,
                points = focalPoints,
                activeLensLabel = activeLensPosition?.let { (numero, totale) ->
                    stringResource(R.string.lens_label, numero, totale)
                },
                lensRows = lensRows,
                onAddPoint = onNavigateToFocalCapture,
                onDeletePoint = viewModel::deleteFocalPoint,
                onClearLens = { clearScope.value = ClearScope.LENS },
                onClearAllLenses = { clearScope.value = ClearScope.ALL }
            )

            // Scambio con un altro telefono: sta fuori dalla card perche' non
            // e' una modifica alla calibrazione, e' mandarla o riceverne una.
            FilledTonalButton(
                onClick = onNavigateToTransfer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.calibration_transfer_entry))
            }
        }
    }

    // Conferma: la calibrazione e' lavoro sul campo, cancellarla per sbaglio
    // costa un giro di misura su un bersaglio, e non si puo' disfare.
    clearScope.value?.let { scope ->
        AlertDialog(
            onDismissRequest = { clearScope.value = null },
            title = {
                Text(
                    stringResource(R.string.calibration_clear_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(
                        if (scope == ClearScope.LENS) R.string.calibration_clear_message_lens
                        else R.string.calibration_clear_message_all
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (scope == ClearScope.LENS) viewModel.clearActiveLensPoints()
                        else viewModel.clearAllLensPoints()
                        clearScope.value = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearScope.value = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun FocalCalibrationCard(
    fxPx: Float?,
    fyPx: Float?,
    theoreticalPx: Float,
    points: List<FocalPoint>,
    activeLensLabel: String?,
    lensRows: List<LensSummary>,
    onAddPoint: () -> Unit,
    onDeletePoint: (Long) -> Unit,
    onClearLens: () -> Unit,
    onClearAllLenses: () -> Unit
) {
    val format = LocalDisplayFormat.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.calibration_card_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onAddPoint) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.calibration_add_point)
                    )
                }
            }
            // Quale lente stanno guardando: la stessa numerazione del mirino.
            // Con una lente sola non compare: non c'e' niente da distinguere.
            activeLensLabel?.let {
                Text(
                    text = stringResource(R.string.calibration_active_lens, it),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val theoreticalLabel = stringResource(
                R.string.calibration_px_theoretical,
                theoreticalPx.toInt()
            )
            val fxLabel = fxPx?.let { stringResource(R.string.calibration_px, it.toInt()) }
                ?: theoreticalLabel
            val fyLabel = fyPx?.let { stringResource(R.string.calibration_px, it.toInt()) }
                ?: theoreticalLabel
            Text(
                text = stringResource(R.string.calibration_fx_fy, fxLabel, fyLabel),
                style = MaterialTheme.typography.bodyMedium
            )

            // Il grafico va qui, subito sotto i due numeri: e' la risposta
            // visiva alla stessa domanda che quei due numeri fanno a testo.
            val scatter = remember(points) { calibrationScatter(points) }
            if (scatter != null && scatter.points.size >= 2) {
                CalibrationScatterChart(scatter)
            }
            Text(
                text = stringResource(R.string.calibration_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            if (points.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.calibration_add_point_count, points.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onAddPoint() }
                )
                points.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // Unita' scelte: distanza in metri o iarde, dimensione
                            // del bersaglio in centimetri o pollici.
                            text = "${format.distance(p.distanceM)} · ${p.pixels.toInt()} px · " +
                                "${format.objectSizeSmall(p.knownSizeM, 0)} · " +
                                stringResource(
                                    if (p.axis == "H") R.string.calibration_axis_horizontal
                                    else R.string.calibration_axis_vertical
                                ) +
                                (if (p.zoom != 1f) " · ${format.number(p.zoom, 1)}x" else "") +
                                " · f≈${p.impliedFocalPx.toInt()} px",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeletePoint(p.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription =
                                    stringResource(R.string.calibration_delete_point),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.calibration_add_point),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onAddPoint() }
                )
            }

            // Dove sono i punti salvati, lente per lente: la lente attiva per
            // prima, in grassetto. Serve dopo qualche mese d'uso, quando i
            // punti sono piu' d'uno e non si sa piu' quale lente li ha.
            if (lensRows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.calibration_points_by_lens),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                lensRows.forEach { row ->
                    Text(
                        text = stringResource(
                            R.string.calibration_lens_points,
                            lensLabel(row),
                            row.points
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (row.isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (row.isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (points.isNotEmpty()) {
                TextButton(
                    onClick = onClearLens,
                    contentPadding = ClearButtonPadding
                ) {
                    Text(
                        stringResource(R.string.calibration_clear_lens),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            // Due bottoni identici sarebbero rumore: questo compare solo quando
            // ci sono davvero i punti di un'altra lente da cancellare.
            if (lensRows.size > 1) {
                TextButton(
                    onClick = onClearAllLenses,
                    contentPadding = ClearButtonPadding
                ) {
                    Text(
                        stringResource(R.string.calibration_clear_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Altezza del grafico: abbastanza per vedere la dispersione, non abbastanza
 *  da rubare lo schermo alla lista dei punti, che resta la cosa operativa. */ 
private val ChartHeight = 132.dp

/** Grafico dei punti: distanza sull'asse orizzontale, focale implicita su quello
 *  verticale, retta di regressione in mezzo.
 * 
 * I punti giusti cadono sulla retta (la focale non dipende dalla distanza):
 * quelli misurati male sono **in rosso**, e la riga sotto quantifica il danno
 * — perche' un grafico che fa vedere il problema deve anche dire qu' e' e
 * cosa si fa (il cestino e' nella lista subito sotto).
 *
 * Gli assi hanno l'unita' scritta accanto (metri o iarde, sempre px per la
 * focale): senza, due numeri su una scala non dicono cosa stanno misurando, e
 * chi ha scelto le iarde leggerebbe metri.
 */ 
@Composable
private fun CalibrationScatterChart(scatter: CalibrationScatter) {
    val format = LocalDisplayFormat.current
    val format0 = { v: Float -> format.number(v, 0) }
    val pointColor = MaterialTheme.colorScheme.primary
    val outlierColor = MaterialTheme.colorScheme.error
    val axisColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.35f)
    val axisLabelColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = MaterialTheme.typography.labelSmall.copy(color = axisLabelColor)
    // L'unita' dell'asse orizzontale segue quella scelta in Impostazioni.
    val distanceAxisLabel =
        stringResource(R.string.calibration_chart_axis_x, format.distanceUnit)
    val focalAxisLabel = stringResource(R.string.calibration_chart_axis_y)
    val scatterPoints = scatter.points
    val midDistance =
        (scatterPoints.minOf { it.distanceM } + scatterPoints.maxOf { it.distanceM }) / 2f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.calibration_chart_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
        ) {
            val points = scatter.points
            var minD = points.minOf { it.distanceM }
            var maxD = points.maxOf { it.distanceM }
            // Distanze tutte uguali (un solo valore): l'asse va comunque
            // aperto, altrimenti la mappatura divide per zero.
            if (maxD - minD < 0.001f) {
                minD -= 1f
                maxD += 1f
            }
            val lineLow = listOf(minD, maxD).minOf { scatter.expectedFocalAt(it) }
            val lineHigh = listOf(minD, maxD).maxOf { scatter.expectedFocalAt(it) }
            var minF = minOf(points.minOf { it.focalPx }, lineLow)
            var maxF = maxOf(points.maxOf { it.focalPx }, lineHigh)
            if (maxF - minF < 1f) {
                val pad = maxOf(minF * 0.05f, 1f)
                minF -= pad
                maxF += pad
            }

            // Le etichette occupano una fascia sopra e una sotto il grafico:
            // i dati restano dentro l'area, senza finirci sopra.
            val topPad = 16.dp.toPx()
            val bottomPad = 16.dp.toPx()
            val plotHeight = size.height - topPad - bottomPad
            fun sx(distanceM: Float): Float =
                (distanceM - minD) / (maxD - minD) * size.width
            fun sy(focalPx: Float): Float =
                size.height - bottomPad - (focalPx - minF) / (maxF - minF) * plotHeight

            drawLine(
                axisColor,
                Offset(0f, size.height - bottomPad),
                Offset(size.width, size.height - bottomPad),
                strokeWidth = 2f
            )
            drawLine(
                axisColor,
                Offset(0f, topPad),
                Offset(0f, size.height - bottomPad),
                strokeWidth = 2f
            )

            // Unita' sugli assi: distanza (m o yd) in basso a destra, focale
            // (px) in alto a sinistra dove comincia l'asse verticale.
            val xLabel = textMeasurer.measure(AnnotatedString(distanceAxisLabel), axisTextStyle)
            drawText(
                xLabel,
                topLeft = Offset(
                    size.width - xLabel.size.width.toFloat(),
                    size.height - xLabel.size.height.toFloat()
                )
            )
            val yLabel = textMeasurer.measure(AnnotatedString(focalAxisLabel), axisTextStyle)
            drawText(yLabel, topLeft = Offset(2.dp.toPx(), 0f))

            // Retta di regressione: se pende, la focal cambia con la distanza
            // e qualcosa e' sistematico.
            val lineY1 = sy(scatter.expectedFocalAt(minD))
            val lineY2 = sy(scatter.expectedFocalAt(maxD))
            drawLine(
                color = pointColor,
                start = Offset(sx(minD), lineY1),
                end = Offset(sx(maxD), lineY2),
                strokeWidth = 3f
            )

            points.forEach { p ->
                val center = Offset(sx(p.distanceM), sy(p.focalPx))
                val radius = (if (p.outlier) 6.dp else 5.dp).toPx()
                drawCircle(color = pointColor, radius = radius, center = center)
                if (p.outlier) {
                    // L'indiziato ha anche il cerchio: il rosso da solo si
                    // confonde con un pixel dell'immagine dietro.
                    drawCircle(
                        color = outlierColor,
                        radius = radius + 4.dp.toPx(),
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        val outliers = scatter.outliers
        Text(
            text = if (outliers.isEmpty()) {
                stringResource(
                    R.string.calibration_chart_ok,
                    format0(scatterPoints.size.toFloat()),
                    format0(scatter.expectedFocalAt(midDistance))
                )
            } else {
                stringResource(
                    R.string.calibration_chart_outliers,
                    format0(outliers.size.toFloat())
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (outliers.isEmpty()) {
                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            } else {
                outlierColor
            }
        )
    }
}

/** 
 * Etichetta della lente come la vede l'utente sul mirino (\"Lente 2/3\"). Se la
 * lente non e' piu' fra quelle del telefono (dati di un device precedente)
 * resta l'id grezzo: scomodo, ma non inventa un numero che non le corrisponde.
 */ 
@Composable
private fun lensLabel(row: LensSummary): String =
    row.position?.let { stringResource(R.string.lens_label, it, row.lensCount) } ?: row.lens