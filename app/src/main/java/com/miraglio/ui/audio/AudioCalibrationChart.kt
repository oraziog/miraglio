package com.miraglio.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.miraglio.R
import com.miraglio.ui.calibration.axisTicks
import com.miraglio.ui.calibration.tickDecimals
import com.miraglio.ui.i18n.LocalDisplayFormat
import kotlin.math.abs

/** Altezza del grafico: come quello ottico, deve mostrare la dispersione senza
 *  rubare lo schermo alla lista dei tiri, che resta la cosa operativa. */
private val ChartHeight = 150.dp

/** Quanti tratti disegnano la curva fra la distanza minima e la massima. */
private const val CURVE_SEGMENTS = 48

/** Padding stretto dei pulsanti "Misura di nuovo": possono essere più d'uno. */
private val RedoButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

/**
 * Grafico dei tiri di calibrazione: distanza sull'asse orizzontale, velocità
 * implicita su quello verticale, curva attesa in mezzo.
 *
 * I tiri giusti stanno sulla curva; quelli che se ne allontanano di più della
 * tolleranza sono **in rosso**, e la riga sotto dice a che distanze rifare il
 * tiro — perché un grafico che fa vedere il problema deve anche dire dove e
 * cosa si fa (il "Misura di nuovo" riempie il campo della distanza, e basta
 * premere il pulsante di misura).
 *
 * I tiri già scartati restano visibili in grigio: sono fuori dal modello, ma
 * togliere il punto dal grafico farebbe sparire anche la traccia del perché la
 * curva passa dove passa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioCalibrationChart(
    curve: AudioCalibrationCurve,
    onRedoDistance: (ShotCurvePoint) -> Unit
) {
    val format = LocalDisplayFormat.current
    val pointColor = MaterialTheme.colorScheme.primary
    val outlierColor = MaterialTheme.colorScheme.error
    val discardedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = MaterialTheme.typography.labelSmall.copy(color = axisLabelColor)
    val distanceAxisLabel =
        stringResource(R.string.audio_calibration_chart_axis_x, format.distanceUnit)
    val speedAxisLabel = stringResource(R.string.audio_calibration_chart_axis_y)
    val points = curve.points
    val activePoints = points.filterNot { it.discarded }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.audio_calibration_chart_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )

        // Unità degli assi sopra il grafico, allineate agli estremi come nel
        // grafico ottico: dentro finirebbero sopra la colonna dei numeri.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(speedAxisLabel, style = axisTextStyle)
            Text(distanceAxisLabel, style = axisTextStyle)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
        ) {
            var minD = points.minOf { it.distanceM }
            var maxD = points.maxOf { it.distanceM }
            // Tutti i tiri alla stessa distanza: l'asse va comunque aperto,
            // altrimenti la mappatura divide per zero.
            if (maxD - minD < 0.001f) {
                minD -= 1f
                maxD += 1f
            }
            var minV = minOf(
                points.minOf { it.speedMs },
                curve.expectedSpeedAt(minD),
                curve.expectedSpeedAt(maxD)
            )
            var maxV = maxOf(
                points.maxOf { it.speedMs },
                curve.expectedSpeedAt(minD),
                curve.expectedSpeedAt(maxD)
            )
            if (maxV - minV < 0.5f) {
                val pad = maxOf((maxV - minV) / 2f, 0.5f)
                minV -= pad
                maxV += pad
            }

            val topPad = 6.dp.toPx()
            val bottomPad = 26.dp.toPx()

            // Le etichette verticali si misurano **prima** di disegnare: la
            // colonna di sinistra si allarga quanto la più larga, così i
            // numeri non finiscono sopra i tiri. Tetto al 35% della larghezza
            // per non lasciare il grafico senza area per i dati.
            val yTicks = axisTicks(minV, maxV)
            val yStep = if (yTicks.size >= 2) yTicks[1] - yTicks[0] else 1f
            val yDigits = tickDecimals(yStep)
            val yLabels = yTicks.map { speed ->
                textMeasurer.measure(
                    AnnotatedString(format.number(speed, yDigits)),
                    axisTextStyle
                )
            }
            val widestYLabel = (yLabels.maxOfOrNull { it.size.width } ?: 0).toFloat()
            val leftPad = (widestYLabel + 6.dp.toPx())
                .coerceAtMost(size.width * 0.35f)
            val plotWidth = size.width - leftPad
            val plotHeight = size.height - topPad - bottomPad
            val xInset = 8.dp.toPx()
            val yInset = 12.dp.toPx()

            fun sx(distanceM: Float): Float =
                leftPad + xInset +
                    (distanceM - minD) / (maxD - minD) * (plotWidth - 2 * xInset).coerceAtLeast(1f)

            fun sy(speedMs: Float): Float =
                size.height - bottomPad - yInset -
                    (speedMs - minV) / (maxV - minV) * (plotHeight - 2 * yInset).coerceAtLeast(1f)

            drawLine(
                axisColor,
                Offset(leftPad, size.height - bottomPad),
                Offset(size.width, size.height - bottomPad),
                strokeWidth = 2f
            )
            drawLine(
                axisColor,
                Offset(leftPad, topPad),
                Offset(leftPad, size.height - bottomPad),
                strokeWidth = 2f
            )

            // Tacche della velocità: senza numeri l'asse verticale sarebbe una
            // scala muta, e quanto un tiro è lontano dalla curva si potrebbe
            // solo dedurre dalla riga di testo sotto.
            yTicks.forEachIndexed { index, speed ->
                val y = sy(speed)
                drawLine(
                    color = axisColor.copy(alpha = 0.18f),
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                drawLine(
                    color = axisColor,
                    start = Offset(leftPad - 3.dp.toPx(), y),
                    end = Offset(leftPad, y),
                    strokeWidth = 2f
                )
                val label = yLabels[index]
                val top = (y - label.size.height / 2f)
                    .coerceIn(topPad, size.height - bottomPad - label.size.height)
                drawText(
                    label,
                    topLeft = Offset(leftPad - 4.dp.toPx() - label.size.width, top)
                )
            }

            // Tacche della distanza, "tonde" (5, 10, 15) e nell'unità scelta:
            // in iarde il valore è convertito, non etichettato "m".
            val ticks = axisTicks(minD, maxD)
            val tickStep = if (ticks.size >= 2) ticks[1] - ticks[0] else 1f
            val tickDigits = tickDecimals(tickStep)
            ticks.forEach { distance ->
                val x = sx(distance)
                drawLine(
                    color = axisColor.copy(alpha = 0.18f),
                    start = Offset(x, topPad),
                    end = Offset(x, size.height - bottomPad),
                    strokeWidth = 1f
                )
                drawLine(
                    color = axisColor,
                    start = Offset(x, size.height - bottomPad),
                    end = Offset(x, size.height - bottomPad + 3.dp.toPx()),
                    strokeWidth = 2f
                )
                val label = textMeasurer.measure(
                    AnnotatedString(
                        format.number(format.distanceValue(distance), tickDigits)
                    ),
                    axisTextStyle
                )
                val left = (x - label.size.width / 2f)
                    .coerceIn(0f, size.width - label.size.width)
                drawText(
                    label,
                    topLeft = Offset(left, size.height - bottomPad + 4.dp.toPx())
                )
            }

            // Curva attesa: campionata, perché è un'esponenziale e non una
            // retta. Con pochi tratti àngoli fra i segmenti si vedrebbero.
            val curvePath = Path()
            repeat(CURVE_SEGMENTS + 1) { index ->
                val distance = minD + (maxD - minD) * index / CURVE_SEGMENTS
                val point = Offset(sx(distance), sy(curve.expectedSpeedAt(distance)))
                if (index == 0) curvePath.moveTo(point.x, point.y)
                else curvePath.lineTo(point.x, point.y)
            }
            drawPath(curvePath, color = pointColor, style = Stroke(width = 3f))

            points.forEach { p ->
                val center = Offset(sx(p.distanceM), sy(p.speedMs))
                val radius = (if (p.outlier) 6.dp else 5.dp).toPx()
                // Trattino dal tiro all'asse: lega il pallino alla sua
                // distanza senza stampare un'etichetta accanto a ogni punto
                // (due tiri vicini si sovrapporrebbero).
                drawLine(
                    color = axisColor.copy(alpha = 0.5f),
                    start = center,
                    end = Offset(center.x, size.height - bottomPad),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                val fill: Color = if (p.discarded) discardedColor else pointColor
                drawCircle(color = fill, radius = radius, center = center)
                if (p.outlier) {
                    // L'indiziato ha anche il cerchio: il rosso da solo si
                    // confonde con un pixel dello sfondo.
                    drawCircle(
                        color = outlierColor,
                        radius = radius + 4.dp.toPx(),
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        val outliers = curve.outliers
        // Le distanze degli indiziati, non solo il numero: il consiglio è
        // "rifai il tiro a quelle distanze", e senza dirle bisognerebbe
        // cercarle una per una nella lista.
        val outlierDistances = outliers
            .map { format.distance(it.distanceM, 0) }
            .distinct()
            .joinToString(", ")
        Text(
            text = if (outliers.isEmpty()) {
                stringResource(
                    R.string.audio_calibration_chart_ok,
                    format.number(activePoints.size, 0),
                    format.distance(curve.maxDeviationM, 1)
                )
            } else {
                stringResource(
                    R.string.audio_calibration_chart_outliers,
                    outlierDistances,
                    format.distance(outliers.maxOf { abs(it.deviationM) }, 1)
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (outliers.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            } else {
                outlierColor
            }
        )

        // "Rifai il tiro" era un consiglio: qui diventa un pulsante che
        // riempie il campo della distanza, e basta premere "Misura il tiro".
        // Un pulsante per distanza, perché i tiri da rifare possono essere
        // più d'uno e si rifanno uno alla volta.
        val toRedo = outliers
            .filterNot { it.discarded }
            .distinctBy { it.distanceM }
            .sortedBy { it.distanceM }
        if (toRedo.isNotEmpty()) {
            // FlowRow e non Row: con tre o quattro distanze i pulsanti non
            // stanno su una riga sola di un telefono stretto.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toRedo.forEach { point ->
                    TextButton(
                        onClick = { onRedoDistance(point) },
                        contentPadding = RedoButtonPadding
                    ) {
                        Text(
                            stringResource(
                                R.string.audio_calibration_chart_redo,
                                format.distance(point.distanceM, 0)
                            )
                        )
                    }
                }
            }
        }
    }
}
