package com.miraglio.ui.capture

import com.miraglio.BuildConfig

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.domain.format.ItalianFormat
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.domain.vision.DetectionBox
import com.miraglio.ui.places.PlaceShareBar
import com.miraglio.ui.places.ShareIntents
import com.miraglio.ui.theme.LocalOutdoorMode
import com.miraglio.ui.theme.OnPanel
import com.miraglio.ui.theme.OutdoorWhite
import com.miraglio.ui.theme.OutdoorYellow
import com.miraglio.ui.theme.PanelContainer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs
import kotlin.math.min

/** Verde fosforo dei bersagli riconosciuti, condiviso da fermo immagine e preview. */
private val FosforoGreen: Color = Color(0xFF64DD17)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onNavigateToCalibration: () -> Unit = {},
    onNavigateToMeasurement: () -> Unit = {},
    onNavigateToPlaces: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureState by viewModel.captureState.collectAsState()
    val heightInputCm by viewModel.heightInputCm.collectAsState()
    val widthInputCm by viewModel.widthInputCm.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val lineOrientation by viewModel.lineOrientation.collectAsState()
    val liveSnap by viewModel.liveSnap.collectAsState()
    val detectionBoxes by viewModel.detectionBoxes.collectAsState()
    val lensLabel by viewModel.lensLabel.collectAsState()
    val measureDirection by viewModel.measureDirection.collectAsState()
    val measureDistanceInput by viewModel.measureDistanceInput.collectAsState()
    val result = captureState.result
    val frozenBitmap by viewModel.frozenBitmap.collectAsState()
    val frozen = frozenBitmap != null
    // Stato del bottone in barra: la preferenza vera la legge MainActivity.
    val outdoorMode by viewModel.outdoorMode.collectAsState()
    val placeSaveState by viewModel.placeSaveState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showModeGuide by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.rebindCamera()
                // Ritorno dal background: BackgroundPowerController ha spento
                // sensori e GPS; qui li riaccendo (rientrando sulla rotta la
                // composizione riparte gia' da sola, ma da background si resta
                // sulla stessa composizione: serve l'evento ON_RESUME).
                viewModel.startSensors()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (BuildConfig.VERSION_NAME.isNotEmpty()) "Miraglio v${BuildConfig.VERSION_NAME}" else "Miraglio",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // Menu a tre righe: sostituisce le icone sparse in barra.
                    // Da qui si raggiungono Verifica distanza, Luoghi,
                    // Calibrazione e la guida "Come si usa".
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Verifica distanza") },
                                leadingIcon = {
                                    Icon(Icons.Default.LocationOn, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToMeasurement()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Luoghi memorizzati") },
                                leadingIcon = {
                                    Icon(Icons.Default.Place, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToPlaces()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Calibrazione") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToCalibration()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Impostazioni") },
                                leadingIcon = {
                                    Icon(Icons.Default.Tune, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Come si usa") },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    showModeGuide = true
                                }
                            )
                        }
                    }
                },
                actions = {
                    // Toggle a un tocco: la modalita' outdoor si accende e si
                    // spegne da qui, senza passare dalle Impostazioni.
                    IconButton(onClick = viewModel::toggleOutdoorMode) {
                        Icon(
                            imageVector = if (outdoorMode) {
                                Icons.Default.DarkMode
                            } else {
                                Icons.Default.LightMode
                            },
                            contentDescription = if (outdoorMode) {
                                "Disattiva modalita' outdoor"
                            } else {
                                "Attiva modalita' outdoor (alto contrasto)"
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Area immagine in alto: tutta la zona disponibile sopra i controlli
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            if (hasPermission) {
                val frozenImage = viewModel.frozenBitmap.collectAsState().value
                if (frozenImage != null) {
                    LineeFrozenOverlay(
                        bitmap = frozenImage,
                        boxes = detectionBoxes,
                        orientation = lineOrientation,
                        topNorm = captureState.lineTopNorm,
                        bottomNorm = captureState.lineBottomNorm,
                        hasLines = captureState.lineTopNorm != null &&
                            captureState.lineBottomNorm != null,
                        onTapAxis = viewModel::registerLinesTap,
                        onPanCommit = viewModel::panLines,
                        onSpreadCommit = viewModel::spreadLines,
                        onMoveFirst = viewModel::moveFirstLine,
                        onMoveSecond = viewModel::moveSecondLine,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ShowCameraPreview(
                        viewModel = viewModel,
                        lifecycleOwner = lifecycleOwner,
                        onZoomFactor = viewModel::zoomByFactor
                    )
                    // Snap live: linee sul miglior box YOLO mentre inquadri, prima dello scatto
                    liveSnap?.let { snap ->
                        LiveSnapOverlay(
                            snap = snap,
                            orientation = lineOrientation,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Permesso camera richiesto")
                        androidx.compose.material3.Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        ) {
                            Text("Richiedi permesso")
                        }
                    }
                }
            }

            if (!frozen) {
                OverlayReticle(modifier = Modifier.fillMaxSize())
            }

            if (!frozen) {
                ControlsOverlay(
                    lensLabel = lensLabel,
                    onCycleLens = viewModel::cycleLens,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
            } // chiude area immagine in alto

            // Controlli sotto l'immagine
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .imePadding()
            ) {
                if (frozen) {
                    // Altezza FISSA per i controlli in modalita' fermo immagine:
                    // passare da "Misurazione distanza" a "Misurazione altezza"
                    // non deve ridimensionare l'area immagine sopra (sembrava
                    // uno zoom del fermo immagine).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FrozenControlsHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FrozenModeButtons(
                            direction = measureDirection,
                            orientation = lineOrientation,
                            onToggleDirection = viewModel::toggleMeasureDirection,
                            onToggleOrientation = viewModel::toggleLineOrientation,
                            modifier = Modifier.fillMaxWidth()
                        )
                        MeasureModePanel(
                            direction = measureDirection,
                            orientation = lineOrientation,
                            heightInputCm = heightInputCm,
                            widthInputCm = widthInputCm,
                            distanceInput = measureDistanceInput,
                            measuredSize = captureState.measuredSizeM,
                            distResult = result,
                            pitchDegrees = captureState.pitchAngleDegrees,
                            hasLines = captureState.lineTopNorm != null &&
                                captureState.lineBottomNorm != null,
                            onHeightTextChanged = viewModel::setHeightInputCm,
                            onWidthTextChanged = viewModel::setWidthInputCm,
                            onDistanceChanged = viewModel::setMeasureDistanceInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                } else {
                    ResultPanel(
                        result = result,
                        bearingDegrees = captureState.bearingDegrees
                    )
                }
                if (frozen && result != null) {
                    // Luoghi: salva lo scatto (foto+misura+GPS), condividi il
                    // messaggio con pin Maps, mostra il QR del bersaglio.
                    // Solo a fermo immagine: senza foto non c'e' niente da salvare.
                    val qrContent = viewModel.cameraQrContent()
                    val shareMessage = viewModel.cameraShareMessage()
                    PlaceShareBar(
                        saveState = placeSaveState,
                        hasPin = qrContent != null,
                        qrContent = qrContent,
                        shareMessage = shareMessage,
                        onShareClick = {
                            val message = viewModel.cameraShareMessage()
                            if (message != null) {
                                val imageUri = viewModel.cameraShareImageUri()
                                if (imageUri != null) {
                                    ShareIntents.shareImageAndText(context, imageUri, message)
                                } else {
                                    ShareIntents.shareText(context, message)
                                }
                            }
                        },
                        onSaveClick = viewModel::saveCameraPlace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
                CaptureActionBar(
                    frozen = frozen,
                    zoomRatio = zoomRatio,
                    onLineeCapture = viewModel::freezeAndAnalyze,
                    onResetLines = viewModel::resetLines,
                    onFreezeToggle = {
                        if (frozen) viewModel.unfreeze() else viewModel.freezeFrame()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }

    if (showModeGuide) {
        AlertDialog(
            onDismissRequest = { showModeGuide = false },
            title = { Text("Come misurare?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "1) Pizzica con due dita per zoomare sul bersaglio.\n\n" +
                        "2) Premi Scatta e rileva: le linee si agganciano da sole al bersaglio.\n\n" +
                        "3) Tocca per riselezionare, trascina a SX per spostare, a DX per " +
                        "allargare/stringere.\n\n" +
                        "Scrivi l'altezza nota del bersaglio in cm: è essenziale per la precisione.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showModeGuide = false }) {
                    Text("Ho capito")
                }
            }
        )
    }
}

@Composable
private fun ShowCameraPreview(
    viewModel: CaptureViewModel,
    lifecycleOwner: LifecycleOwner,
    onZoomFactor: (Float) -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startSensors()
        onDispose {
            viewModel.stopSensors()
        }
    }

    val modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoomChange, _ ->
                onZoomFactor(zoomChange)
            }
        }

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
    )
}

@Composable
private fun LineeFrozenOverlay(
    bitmap: Bitmap,
    boxes: List<DetectionBox>,
    orientation: LineOrientation,
    topNorm: Float?,
    bottomNorm: Float?,
    hasLines: Boolean,
    onTapAxis: (Float) -> Unit,
    onPanCommit: (Float) -> Unit,
    onSpreadCommit: (Float) -> Unit,
    onMoveFirst: (Float) -> Unit,
    onMoveSecond: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Colori delle linee sopra l'immagine: in outdoor la primaria diventa
    // giallo fluorescente e la secondaria bianca, perche' verde e blu
    // sull'immagine al sole perdono contrasto.
    val primaryLine = if (LocalOutdoorMode.current) OutdoorYellow else FosforoGreen
    val secondaryLine = if (LocalOutdoorMode.current) OutdoorWhite else Color(0xFF2979FF)

    // Stato locale del trascinamento: fluido a 60fps, commit al ViewModel solo al rilascio.
    // firstAcc/secondAcc: spostamento della SINGOLA linea afferrata vicino al suo tratto.
    // mutableFloatStateOf (non mutableStateOf): questi accumulatori vengono
    // riscritti a ogni evento di trascinamento, e lo stato float specializzato
    // evita un'allocazione di boxing per ogni scrittura.
    var panAcc by remember { mutableFloatStateOf(0f) }
    var spreadAcc by remember { mutableFloatStateOf(0f) }
    var firstAcc by remember { mutableFloatStateOf(0f) }
    var secondAcc by remember { mutableFloatStateOf(0f) }
    val dispTop = topNorm?.let { (it + panAcc + firstAcc - spreadAcc / 2f).coerceIn(0f, 1f) }
    val dispBottom = bottomNorm?.let { (it + panAcc + secondAcc + spreadAcc / 2f).coerceIn(0f, 1f) }
    val horizontal = orientation == LineOrientation.HORIZONTAL

    BoxWithConstraints(
        modifier = modifier.background(Color.Black)
    ) {
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

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Fermo immagine linee",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Ref stabili per i valori geometrici: i lambda di pointerInput(Unit) li leggono
        // sempre aggiornati senza che il detector venga ricreato ad ogni recompose.
        val displayWRef = rememberUpdatedState(displayW)
        val displayHRef = rememberUpdatedState(displayH)
        val offsetXRef  = rememberUpdatedState(offsetX)
        val offsetYRef = rememberUpdatedState(offsetY)
        val dispTopRef = rememberUpdatedState(dispTop)
        val dispBottomRef = rememberUpdatedState(dispBottom)

        // Nota: il delta di trascinamento è in pixel fisici, mentre topNorm/bottomNorm
        // sono frazioni dell'asse immagine disegnato (displayW/displayH). La conversione
        // va quindi fatta con displayW/displayH, non con bw/bh (estensione schermo).

        // Un'unica superficie di gesto, valida per linee orizzontali E verticali:
        // - trascinamento lungo l'asse di misura vicino a una linea -> muove solo quella
        // - trascinamento lungo l'asse nella fascia di bordo opposta -> allarga/restringe
        // - trascinamento lungo l'asse altrove -> sposta entrambe (pan)
        // - tap -> centra l'apertura sul punto toccato (invariato)
        // key = orientamento: al toggle il detector va RICREATO (altrimenti resterebbe
        // quello dell'asse precedente, bug del trascinamento morto).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(horizontal) {
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
                                val dH = displayHRef.value
                                if (dH <= 0f) return@detectVerticalDragGestures
                                val yNorm = (change.position.y - offsetYRef.value) / dH
                                val t = dispTopRef.value ?: return@detectVerticalDragGestures
                                val b = dispBottomRef.value ?: return@detectVerticalDragGestures
                                val d = dragAmount / dH
                                when {
                                    abs(yNorm - t) * dH <= px -> firstAcc += d
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
                                val dW = displayWRef.value
                                if (dW <= 0f) return@detectHorizontalDragGestures
                                val xNorm = (change.position.x - offsetXRef.value) / dW
                                val t = dispTopRef.value ?: return@detectHorizontalDragGestures
                                val b = dispBottomRef.value ?: return@detectHorizontalDragGestures
                                val d = dragAmount / dW
                                when {
                                    abs(xNorm - t) * dW <= px -> firstAcc += d
                                    abs(xNorm - b) * dW <= px -> secondAcc += d
                                    change.position.y >= size.height - px -> spreadAcc += d
                                    else -> panAcc += d
                                }
                            }
                        )
                    }
                }
                .pointerInput(horizontal) {
                    detectTapGestures { tapOffset ->
                        val dW = displayWRef.value
                        val dH = displayHRef.value
                        val oX = offsetXRef.value
                        val oY = offsetYRef.value
                        if (horizontal && dH > 0f) {
                            onTapAxis(((tapOffset.y - oY) / dH).coerceIn(0f, 1f))
                        } else if (!horizontal && dW > 0f) {
                            onTapAxis(((tapOffset.x - oX) / dW).coerceIn(0f, 1f))
                        }
                    }
                }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            // Bersagli riconosciuti al fermo immagine: un riquadro verde fosforo
            // attorno a ciascuno. Il riquadro segue l'estensione reale del
            // rilevamento (per il paglione circolare e' praticamente quadrato).
            if (displayW > 0f && displayH > 0f) {
                boxes.forEach { box ->
                    val l = offsetX + box.left.coerceIn(0f, 1f) * displayW
                    val t = offsetY + box.top.coerceIn(0f, 1f) * displayH
                    val r = offsetX + box.right.coerceIn(0f, 1f) * displayW
                    val b = offsetY + box.bottom.coerceIn(0f, 1f) * displayH
                    if (r > l && b > t) {
                        drawRect(
                            color = primaryLine,
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
            listOf(dispTop to primaryLine, dispBottom to secondaryLine)
                .filter { it.first != null }
                .forEach { (norm, color) ->
                    if (horizontal && displayH > 0f) {
                        val y = offsetY + norm!! * displayH
                        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
                        drawCircle(color, 10.dp.toPx(), Offset(32.dp.toPx(), y))
                        drawCircle(color, 10.dp.toPx(), Offset(size.width - 32.dp.toPx(), y))
                    } else if (!horizontal && displayW > 0f) {
                        val x = offsetX + norm!! * displayW
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                        drawCircle(color, 10.dp.toPx(), Offset(x, 32.dp.toPx()))
                        drawCircle(color, 10.dp.toPx(), Offset(x, size.height - 32.dp.toPx()))
                    }
                }
            if (dispTop != null && dispBottom != null && dispBottom > dispTop) {
                if (horizontal && displayH > 0f) {
                    val yTop = offsetY + dispTop * displayH
                    val yBottom = offsetY + dispBottom * displayH
                    drawLine(Color(0xCCFFFFFF), Offset(64.dp.toPx(), yTop), Offset(64.dp.toPx(), yBottom), 2f)
                } else if (!horizontal && displayW > 0f) {
                    val xA = offsetX + dispTop * displayW
                    val xB = offsetX + dispBottom * displayW
                    drawLine(Color(0xCCFFFFFF), Offset(xA, 64.dp.toPx()), Offset(xB, 64.dp.toPx()), 2f)
                }
            }
        }
    }
}

@Composable
private fun LiveSnapOverlay(
    snap: LiveSnap,
    orientation: LineOrientation,
    modifier: Modifier = Modifier
) {
    val horizontal = orientation == LineOrientation.HORIZONTAL
    // Linee dello snap live: stesso criterio del fermo immagine.
    val primaryLine = if (LocalOutdoorMode.current) OutdoorYellow else FosforoGreen
    val secondaryLine = if (LocalOutdoorMode.current) OutdoorWhite else Color(0xFF2979FF)
    BoxWithConstraints(modifier = modifier) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val fw = snap.frameWidth
        val fh = snap.frameHeight
        val scale = if (bw > 0f && bh > 0f && fw > 0 && fh > 0) {
            min(bw / fw, bh / fh)
        } else {
            1f
        }
        val displayW = fw * scale
        val displayH = fh * scale
        val offsetX = (bw - displayW) / 2f
        val offsetY = (bh - displayH) / 2f

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val box = snap.box
                        // Quadrato YOLO: perimetro attorno al box primario (paglione o targa)
                        val dl = offsetX + box.left.coerceIn(0f, 1f) * displayW
                        val dt = offsetY + box.top.coerceIn(0f, 1f) * displayH
                        val dr = offsetX + box.right.coerceIn(0f, 1f) * displayW
                        val db = offsetY + box.bottom.coerceIn(0f, 1f) * displayH
                        if (dr > dl && db > dt) {
                            drawRect(
                                color = primaryLine,
                                topLeft = Offset(dl, dt),
                                size = Size(dr - dl, db - dt),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                        val rl = offsetX + box.left.coerceIn(0f, 1f) * displayW
                        val rt = offsetY + box.top.coerceIn(0f, 1f) * displayH
                        val rw = (box.right.coerceIn(0f, 1f) - box.left.coerceIn(0f, 1f)) * displayW
                        val rh = (box.bottom.coerceIn(0f, 1f) - box.top.coerceIn(0f, 1f)) * displayH
                        if (rw > 0f && rh > 0f) {
                            drawRect(
                                color = primaryLine,
                                topLeft = Offset(rl, rt),
                                size = Size(rw, rh),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
            if (horizontal && displayH > 0f) {
                val yTop = offsetY + box.top.coerceIn(0f, 1f) * displayH
                val yBottom = offsetY + box.bottom.coerceIn(0f, 1f) * displayH
                drawLine(primaryLine, Offset(0f, yTop), Offset(size.width, yTop), 3f)
                drawLine(secondaryLine, Offset(0f, yBottom), Offset(size.width, yBottom), 3f)
            } else if (!horizontal && displayW > 0f) {
                val xLeft = offsetX + box.left.coerceIn(0f, 1f) * displayW
                val xRight = offsetX + box.right.coerceIn(0f, 1f) * displayW
                drawLine(primaryLine, Offset(xLeft, 0f), Offset(xLeft, size.height), 3f)
                drawLine(secondaryLine, Offset(xRight, 0f), Offset(xRight, size.height), 3f)
            }
        }
    }
}

@Composable
private fun ControlsOverlay(
    lensLabel: String?,
    onCycleLens: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xC7000000))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            lensLabel?.let {
                TextButton(onClick = onCycleLens) { Text(it) }
            }
        }
    }
}

@Composable
private fun CaptureActionBar(
    frozen: Boolean,
    zoomRatio: Float,
    onLineeCapture: () -> Unit,
    onResetLines: () -> Unit,
    onFreezeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xC7000000))
    ) {
        if (!frozen) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onLineeCapture,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Scatta fermo immagine", style = MaterialTheme.typography.titleMedium)
                }
                // Fattore di moltiplicazione zoom corrente, a destra del bottone
                Surface(
                    color = Color(0x66000000),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${ItalianFormat.number(zoomRatio, 1)}x",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                TextButton(
                    onClick = onResetLines,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Azzera linee")
                }
                FilledTonalButton(
                    onClick = onFreezeToggle,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Live")
                }
            }
        }
    }
}

@Composable
private fun OverlayReticle(
    modifier: Modifier = Modifier
) {
    // In outdoor la crocetta di mira passa al giallo fluorescente: e' il
    // riferimento da vedere a colpo d'occhio sul verde del bosco.
    val crosshairColor = if (LocalOutdoorMode.current) OutdoorYellow else Color(0xCCFFFFFF)
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val crosses = 30.dp.toPx()

        drawLine(crosshairColor, Offset(centerX - crosses, centerY), Offset(centerX + crosses, centerY), strokeWidth = 2f)
        drawLine(crosshairColor, Offset(centerX, centerY - crosses), Offset(centerX, centerY + crosses), strokeWidth = 2f)
        drawCircle(crosshairColor.copy(alpha = 0.85f), radius = 40.dp.toPx(), center = Offset(centerX, centerY), style = Stroke(2f))
    }
}@Composable
private fun ResultPanel(
    result: MeasureResult?,
    modifier: Modifier = Modifier,
    bearingDegrees: Float? = null

) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = PanelContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (result != null) {
                    "Distanza: ${ItalianFormat.meters(result.distanceMeters)}"
                } else {
                    "Inquadra il bersaglio, poi scatta fermo immagine"
                },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = OnPanel
            )
            if (result != null) {
                Text(
                    text = "Angolo: ${ItalianFormat.number(result.pitchAngleDegrees, 1)}°",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnPanel
                )
                Text(
                    text = "Distanza compensata: ${ItalianFormat.meters(result.horizontalDistanceM)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnPanel
                )
                bearingDegrees?.let { bearing ->
                    Text(
                        text = "Direzione: ${ShareMessageComposer.compassLabel(bearing)} " +
                            "(${ItalianFormat.number(bearing, 0)}°)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnPanel
                    )
                }
            }
        }
    }
}

/**
 * Colori dei campi del pannello inferiore: nero su sfondo chiaro nell'uso
 * normale, giallo fluorescente su nero in modalita' outdoor.
 */
@Composable
private fun panelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OnPanel,
    unfocusedTextColor = OnPanel,
    focusedLabelColor = OnPanel,
    unfocusedLabelColor = OnPanel,
    cursorColor = OnPanel
)

@Composable
private fun MeasureModePanel(
    direction: MeasureDirection,
    orientation: LineOrientation,
    heightInputCm: String,
    widthInputCm: String,
    distanceInput: String,
    measuredSize: Float?,
    distResult: MeasureResult?,
    pitchDegrees: Float?,
    hasLines: Boolean,
    onHeightTextChanged: (String) -> Unit,
    onWidthTextChanged: (String) -> Unit,
    onDistanceChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontal = orientation == LineOrientation.HORIZONTAL
    val sizeLabel = if (horizontal) "Altezza" else "Larghezza"
    val angleSuffix = if (pitchDegrees != null) {
        "  \u2022  Angolo: ${ItalianFormat.number(pitchDegrees, 1)}°"
    } else {
        ""
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelContainer)
    ) {
        Column(
            modifier = Modifier
                // Padding e spaziatura ridotti: le tre voci del pannello
                // (campo, misura, "Distanza compensata:") devono stare tutte
                // nell'altezza fissa del contenitore, senza scorrere.
                .padding(8.dp)
                // L'altezza del pannello e' fissata dal contenitore: con font
                // grandi il contenuto scorre invece de' crescere.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (direction) {
                MeasureDirection.DISTANZA -> {
                    OutlinedTextField(
                        value = if (horizontal) heightInputCm else widthInputCm,
                        onValueChange = if (horizontal) onHeightTextChanged else onWidthTextChanged,
                        label = { Text(if (horizontal) "Altezza nota (cm)" else "Larghezza nota (cm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // Nessuna altezza forzata: si dimensiona Material.
                        modifier = Modifier.fillMaxWidth(),
                        colors = panelFieldColors()
                    )
                    Text(
                        text = buildString {
                            append(
                                if (distResult != null) {
                                    "Distanza: ${ItalianFormat.meters(distResult.distanceMeters)}"
                                } else {
                                    "Scatta e posiziona le linee sul bersaglio"
                                }
                            )
                            if (distResult != null) append(angleSuffix)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnPanel
                    )
                    if (distResult != null) {
                        Text(
                            text = "Distanza compensata: " +
                                ItalianFormat.meters(distResult.horizontalDistanceM),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnPanel
                        )
                    }
                }
                MeasureDirection.ALTEZZA -> {
                    OutlinedTextField(
                        value = distanceInput,
                        onValueChange = onDistanceChanged,
                        label = { Text("Distanza nota (m)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // Nessuna altezza forzata: si dimensiona Material.
                        modifier = Modifier.fillMaxWidth(),
                        colors = panelFieldColors()
                    )
                    Text(
                        text = buildString {
                            append(sizeResultText(sizeLabel, distanceInput, hasLines, measuredSize))
                            if (measuredSize != null) append(angleSuffix)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnPanel
                    )
                }
            }
        }
    }
}

@Composable
private fun FrozenModeButtons(
    direction: MeasureDirection,
    orientation: LineOrientation,
    onToggleDirection: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onToggleDirection,
            contentPadding = MeasureButtonPadding,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                if (direction == MeasureDirection.DISTANZA) "Misurazione distanza"
                else "Misurazione altezza",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
        FilledTonalButton(
            onClick = onToggleOrientation,
            contentPadding = MeasureButtonPadding
        ) {
            Icon(
                Icons.Default.Refresh,
                // Il bottone mostra l'AZIONE (cosa diventera'), non lo stato:
                // con linee orizzontali premendo si passa alle verticali.
                contentDescription = if (orientation == LineOrientation.HORIZONTAL) {
                    "Passa a linee verticali"
                } else {
                    "Passa a linee orizzontali"
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (orientation == LineOrientation.HORIZONTAL) "\u2192 Linee Verticali"
                else "\u2192 Linee Orizzontali",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

/** Altezza riservata ai controlli del fermo immagine (direzione + pannello). */
private val FROZEN_CONTROLS_HEIGHT = 200.dp

/**
 * In outdoor i testi sono ingranditi: il contenitore cresce nella stessa
 * misura, cosi' il pannello continua a stare tutto dentro (compresa la voce
 * "Distanza compensata:") senza scorrere.
 *
 * L'altezza resta FISSA per modalita': cambiare da distanza ad altezza non
 * ridimensiona l'immagine sopra, che era il motivo dell'altezza fissa.
 */
private val FrozenControlsHeight: Dp
    @Composable get() = if (LocalOutdoorMode.current) FROZEN_CONTROLS_HEIGHT * 1.3f else FROZEN_CONTROLS_HEIGHT

/**
 * Padding interno dei bottoni della misura: la riga deve restare bassa per
 * lasciare spazio al pannello, che deve mostrare anche la "Distanza compensata".
 */
private val MeasureButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

/*
 * I campi "valore noto" (altezza/larghezza o distanza) NON hanno un'altezza
 * forzata, ed e' una scelta deliberata dopo un difetto vero: con
 * Modifier.height(44,8.dp) - il 20% in meno del default, chiesto per stringere
 * il pannello - il valore digitato (preimpostato o appena scritto) spariva,
 * perche' il testo finiva fuori dall'altezza del campo.
 *
 * Il campo Material si dimensiona da solo: riserva 16.dp sopra e 16.dp sotto
 * piu' la riga di testo (il suo MinHeight di 56.dp e' 16 + 24 + 16 con
 * bodyLarge) e cresce quando la riga e' piu' alta, come in modalita' outdoor o
 * con i font di sistema grandi. Lasciando fare a lui il valore non puo' piu'
 * finire tagliato.
 */

private fun sizeResultText(
    sizeLabel: String,
    distanceInput: String,
    hasLines: Boolean,
    measuredSize: Float?
): String = when {
    distanceInput.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f } == null ->
        "Inserisci la distanza (m)"
    !hasLines -> "Posiziona le linee sui bordi dell'oggetto"
    measuredSize == null -> "Misura non disponibile"
    measuredSize >= 0.99f -> "$sizeLabel: ${ItalianFormat.meters(measuredSize, 2)}"
    else -> "$sizeLabel: ${ItalianFormat.number(measuredSize * 100, 1)} cm"
}

