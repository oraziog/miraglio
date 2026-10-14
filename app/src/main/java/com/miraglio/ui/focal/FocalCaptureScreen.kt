package com.miraglio.ui.focal

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import com.miraglio.ui.capture.LineOrientation
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocalCaptureScreen(
    onNavigateBack: () -> Unit,
    viewModel: FocalCaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    val frozenBitmap by viewModel.frozenBitmap.collectAsState()
    val savedPoints by viewModel.savedPoints.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val lensLabel by viewModel.lensLabel.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrazione focale camera", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
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
        ) {
            val bmp = frozenBitmap
            if (state.frozen && bmp != null && bmp.width > 0 && bmp.height > 0) {
                // Fermo immagine IN CIMA: la foto occupa tutta la larghezza nel suo
                // aspect (niente bande sopra/sotto), e il pannello sta ai suoi piedi.
                FocalLinesOverlay(
                    bitmap = bmp,
                    orientation = state.orientation,
                    lineA = state.lineA,
                    lineB = state.lineB,
                    onAxisTap = viewModel::registerAxisTap,
                    onPanCommit = viewModel::panLines,
                    onSpreadCommit = viewModel::spreadLines,
                    onMoveFirst = viewModel::moveFirstLine,
                    onMoveSecond = viewModel::moveSecondLine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                )

                // Pannello parametri subito dopo il fermo immagine.
                FocalInputPanel(
                    state = state,
                    impliedFocal = viewModel.currentImpliedFocal(),
                    savedTotal = savedPoints.size + state.savedCount,
                    zoomRatio = zoomRatio,
                    lensLabel = lensLabel,
                    onSizeChanged = viewModel::setSizeInputCm,
                    onDistanceChanged = viewModel::setDistanceInput,
                    onCapture = viewModel::freezeFrame,
                    onRetake = viewModel::unfreeze,
                    onSave = viewModel::savePoint,
                    onZoomReset = viewModel::resetZoom,
                    onCycleLens = viewModel::cycleLens,
                    onToggleOrientation = viewModel::toggleOrientation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .imePadding()
                )

                // Giace in basso: tutto il resto dello schermo resta vuoto.
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // Anteprima live in alto, controlli sotto di essa.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (!hasPermission) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Permesso camera richiesto")
                        }
                    } else {
                        FocalLivePreview(
                            viewModel = viewModel,
                            lifecycleOwner = lifecycleOwner,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                FocalInputPanel(
                    state = state,
                    impliedFocal = viewModel.currentImpliedFocal(),
                    savedTotal = savedPoints.size + state.savedCount,
                    zoomRatio = zoomRatio,
                    lensLabel = lensLabel,
                    onSizeChanged = viewModel::setSizeInputCm,
                    onDistanceChanged = viewModel::setDistanceInput,
                    onCapture = viewModel::freezeFrame,
                    onRetake = viewModel::unfreeze,
                    onSave = viewModel::savePoint,
                    onZoomReset = viewModel::resetZoom,
                    onCycleLens = viewModel::cycleLens,
                    onToggleOrientation = viewModel::toggleOrientation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .imePadding()
                )
            }
        }
    }
}

@Composable
private fun FocalLivePreview(
    viewModel: FocalCaptureViewModel,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    // Preview a tutto schermo, come nella schermata Misura: CameraX sceglie la
    // risoluzione sull'aspect dello schermo, quindi niente bande laterali.
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                viewModel.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    context = ctx
                )
            }
        },
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    viewModel.zoomByFactor(zoomChange)
                }
            }
    )
}

@Composable
private fun FocalLinesOverlay(
    bitmap: Bitmap,
    orientation: LineOrientation,
    lineA: Float?,
    lineB: Float?,
    onAxisTap: (Float) -> Unit,
    onPanCommit: (Float) -> Unit,
    onSpreadCommit: (Float) -> Unit,
    onMoveFirst: (Float) -> Unit,
    onMoveSecond: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var panAcc by remember { mutableStateOf(0f) }
    var spreadAcc by remember { mutableStateOf(0f) }
    var firstAcc by remember { mutableStateOf(0f) }
    var secondAcc by remember { mutableStateOf(0f) }
    LaunchedEffect(lineA, lineB) {
        panAcc = 0f
        spreadAcc = 0f
    }
    // Linee sempre presenti in mezzo al fotogramma: se lo stato non le fornisce,
    // si usa il centro. Niente filtri condizionali nel disegno.
    val baseA = lineA ?: (0.5f - FROZEN_GAP / 2f)
    val baseB = lineB ?: (0.5f + FROZEN_GAP / 2f)
    val dispA = (baseA + panAcc + firstAcc - spreadAcc / 2f).coerceIn(0f, 1f)
    val dispB = (baseB + panAcc + secondAcc + spreadAcc / 2f).coerceIn(0f, 1f)
    val horizontal = orientation == LineOrientation.HORIZONTAL

    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val scale = if (bw > 0f && bh > 0f && bitmap.width > 0 && bitmap.height > 0) {
            min(bw / bitmap.width, bh / bitmap.height)
        } else {
            1f
        }
        val displayW = bitmap.width * scale
        val displayH = bitmap.height * scale
        val offsetX = (bw - displayW) / 2f
        val offsetY = (bh - displayH) / 2f

        val dispARef = rememberUpdatedState(dispA)
        val dispBRef = rememberUpdatedState(dispB)

        // Un'unica superficie di gesto, valida per linee orizzontali E verticali
        // (le strisce laterali da 64dp erano irraggiungibili per le verticali):
        // - trascinamento lungo l'asse vicino a una linea -> muove solo quella
        // - trascinamento lungo l'asse nella fascia di bordo opposta -> allarga/restringe
        // - trascinamento lungo l'asse altrove -> sposta entrambe (pan)
        // - tap -> centra l'apertura sul punto toccato (invariato)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(horizontal, displayW, displayH) {
                    val px = 48.dp.toPx()
                    if (horizontal) {
                        detectVerticalDragGestures(
                            onDragStart = { panAcc = 0f; spreadAcc = 0f; firstAcc = 0f; secondAcc = 0f },
                            onDragEnd = {
                                if (firstAcc != 0f) onMoveFirst(firstAcc)
                                else if (secondAcc != 0f) onMoveSecond(secondAcc)
                                else if (spreadAcc != 0f) onSpreadCommit(spreadAcc)
                                else if (panAcc != 0f) onPanCommit(panAcc)
                                panAcc = 0f; spreadAcc = 0f; firstAcc = 0f; secondAcc = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                val dH = displayH
                                if (dH <= 0f) return@detectVerticalDragGestures
                                val yNorm = (change.position.y - offsetY) / dH
                                val a = dispARef.value
                                val b = dispBRef.value
                                val d = dragAmount / dH
                                when {
                                    abs(yNorm - a) * dH <= px -> firstAcc += d
                                    abs(yNorm - b) * dH <= px -> secondAcc += d
                                    change.position.x >= size.width - px -> spreadAcc += d
                                    else -> panAcc += d
                                }
                            }
                        )
                    } else {
                        detectHorizontalDragGestures(
                            onDragStart = { panAcc = 0f; spreadAcc = 0f; firstAcc = 0f; secondAcc = 0f },
                            onDragEnd = {
                                if (firstAcc != 0f) onMoveFirst(firstAcc)
                                else if (secondAcc != 0f) onMoveSecond(secondAcc)
                                else if (spreadAcc != 0f) onSpreadCommit(spreadAcc)
                                else if (panAcc != 0f) onPanCommit(panAcc)
                                panAcc = 0f; spreadAcc = 0f; firstAcc = 0f; secondAcc = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                val dW = displayW
                                if (dW <= 0f) return@detectHorizontalDragGestures
                                val xNorm = (change.position.x - offsetX) / dW
                                val a = dispARef.value
                                val b = dispBRef.value
                                val d = dragAmount / dW
                                when {
                                    abs(xNorm - a) * dW <= px -> firstAcc += d
                                    abs(xNorm - b) * dW <= px -> secondAcc += d
                                    change.position.y >= size.height - px -> spreadAcc += d
                                    else -> panAcc += d
                                }
                            }
                        )
                    }
                }
                .pointerInput(horizontal, displayW, displayH, offsetX, offsetY) {
                    detectTapGestures { tap ->
                        if (horizontal && displayH > 0f) {
                            onAxisTap(((tap.y - offsetY) / displayH).coerceIn(0f, 1f))
                        } else if (!horizontal && displayW > 0f) {
                            onAxisTap(((tap.x - offsetX) / displayW).coerceIn(0f, 1f))
                        }
                    }
                }
        )

        // Nessun bottone in overlay: il cambio asse (H/V) sta nel pannello sotto l'immagine.

        // FOTO + LINEE in un unico Canvas: prima si disegna la foto (sfondo),
        // subito dopo le due linee parallele sopra di essa. Nessun altro elemento
        // intermedio può coprirle: restano sempre davanti.
        // key() forza la ricreazione del Canvas a ogni cambio di posizione/apertura.
        key(dispA, dispB, horizontal, displayW, displayH, offsetX, offsetY) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                // 1) Foto in background, adattata (FIT) e centrata.
                if (displayW > 0f && displayH > 0f) {
                    drawImage(
                        image = bitmap.asImageBitmap(),
                        dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                        dstSize = IntSize(displayW.roundToInt(), displayH.roundToInt())
                    )
                }

                // 2) Linee parallele davanti alla foto (verde e blu), con bordo scuro
                //    per restare visibili anche su sfondi chiari.
                listOf(dispA to Color(0xFF64DD17), dispB to Color(0xFF2979FF))
                    .forEach { (norm, color) ->
                        if (horizontal && displayH > 0f) {
                            val y = offsetY + norm * displayH
                            drawLine(Color(0xCC000000), Offset(0f, y), Offset(size.width, y), strokeWidth = 7f)
                            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
                            drawCircle(color, 10.dp.toPx(), Offset(32.dp.toPx(), y))
                            drawCircle(color, 10.dp.toPx(), Offset(size.width - 32.dp.toPx(), y))
                        } else if (!horizontal && displayW > 0f) {
                            val x = offsetX + norm * displayW
                            drawLine(Color(0xCC000000), Offset(x, 0f), Offset(x, size.height), strokeWidth = 7f)
                            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                            drawCircle(color, 10.dp.toPx(), Offset(x, 32.dp.toPx()))
                            drawCircle(color, 10.dp.toPx(), Offset(x, size.height - 32.dp.toPx()))
                        }
                    }
                if (dispB > dispA) {
                    if (horizontal && displayH > 0f) {
                        val yA = offsetY + dispA * displayH
                        val yB = offsetY + dispB * displayH
                        drawLine(Color(0xCCFFFFFF), Offset(64.dp.toPx(), yA), Offset(64.dp.toPx(), yB), 2f)
                    } else if (!horizontal && displayW > 0f) {
                        val xA = offsetX + dispA * displayW
                        val xB = offsetX + dispB * displayW
                        drawLine(Color(0xCCFFFFFF), Offset(xA, 64.dp.toPx()), Offset(xB, 64.dp.toPx()), 2f)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocalInputPanel(
    state: FocalCaptureUiState,
    impliedFocal: Float?,
    savedTotal: Int,
    zoomRatio: Float,
    lensLabel: String?,
    onSizeChanged: (String) -> Unit,
    onDistanceChanged: (String) -> Unit,
    onCapture: () -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    onZoomReset: () -> Unit,
    onCycleLens: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontal = state.orientation == LineOrientation.HORIZONTAL
    val axisLabel = if (horizontal) "Altezza" else "Larghezza"
    val focalLabel = if (horizontal) "fy" else "fx"

    Card(
        modifier = if (state.frozen) modifier.height(200.dp) else modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xC7000000))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!state.frozen) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(onClick = onCapture, modifier = Modifier.weight(1f)) {
                        Text("Scatta fermo immagine")
                    }
                    lensLabel?.let {
                        TextButton(onClick = onCycleLens) { Text(it) }
                    }
                    TextButton(onClick = onZoomReset) {
                        Text("${"%.1f".format(zoomRatio)}x")
                    }
                }
            } else {
                // Riga 1: quale focale stai calibrando (fy = linee orizzontali) + cambio asse.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Calibra: $focalLabel  ($axisLabel)",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onToggleOrientation) {
                        Text(
                            if (horizontal) "↔ Passa a larghezza"
                            else "↕ Passa ad altezza",
                            color = Color(0xFFFFD54F)
                        )
                    }
                }

                // Riga 2: distanza + dimensione nota + salva + ripeti.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.distanceInput,
                        onValueChange = onDistanceChanged,
                        label = { Text("Distanza (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.sizeInputCm,
                        onValueChange = onSizeChanged,
                        label = { Text("$axisLabel (cm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onSave) {        Icon(
            Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = "Salva punto",
                            tint = Color.White,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(8.dp)
                        )
                    }
                    TextButton(onClick = onRetake) {
                        Text("Ripeti")
                    }
                }

                // Riga 3: pixel misurati, punti salvati, focale implicita a 1x.
                val px = state.measuredPixels()
                Text(
                    text = buildString {
                        append("Pixel: ${px?.toInt() ?: "-"}")
                        append("  \u2022  Punti: $savedTotal")
                        if (impliedFocal != null) {
                            append("  \u2022  $focalLabel \u2248 ${impliedFocal.toInt()} px (1\u00d7)")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )

                // Riga 4: esempio concreto del flusso distanza + altezza -> fy.
                Text(                    text = "Esempio: paglione alto 130 cm a 30 m \u2192 linee orizzontali " + "sui bordi sup/inf \u2192 salva. Se ottieni fy \u2248 683 px e la " +
                        "teorica era 400 px, la calibrazione \u00e8 1.6\u00d7 pi\u00f9 lunga.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB0BEC5)
                )
            }

            state.statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFD54F)
                )
            }
        }
    }
}

/** Apertura di default delle due linee parallele nel fermo immagine. */
private const val FROZEN_GAP = 0.25f
