package com.miraglio.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miraglio.BuildConfig
import com.miraglio.R
import com.miraglio.domain.reticle.angularSize
import com.miraglio.domain.reticle.pixelsPerMoa
import com.miraglio.domain.reticle.reticleScale
import com.miraglio.domain.share.ShareMessageComposer
import com.miraglio.domain.vision.DetectionBox
import com.miraglio.ui.i18n.LocalDisplayFormat
import com.miraglio.ui.layout.AimLayout
import com.miraglio.ui.places.PlaceShareBar
import com.miraglio.ui.places.ShareIntents
import com.miraglio.ui.theme.LocalOutdoorMode
import com.miraglio.ui.theme.OnPanel
import com.miraglio.ui.theme.OutdoorWhite
import com.miraglio.ui.theme.OutdoorYellow
import com.miraglio.ui.theme.PanelContainer
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
    val heightInput by viewModel.heightInput.collectAsState()
    val widthInput by viewModel.widthInput.collectAsState()
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
    // Stato dei bottoni in barra: la preferenza vera del blocco rotazione la
    // legge MainActivity, che e' l'unica che puo' applicarla.
    val outdoorMode by viewModel.outdoorMode.collectAsState()
    val portraitLocked by viewModel.portraitLocked.collectAsState()
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
                        if (BuildConfig.VERSION_NAME.isNotEmpty()) {
                            stringResource(R.string.app_title_version, BuildConfig.VERSION_NAME)
                        } else {
                            stringResource(R.string.app_name)
                        },
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
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_open)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.measurement_title)) },
                                leadingIcon = {
                                    Icon(Icons.Default.LocationOn, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToMeasurement()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.places_title)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Place, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToPlaces()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calibration_title)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToCalibration()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Tune, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onNavigateToSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_how_to_use)) },
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
                    // Rotazione: un tocco per bloccare o liberare. Il blocco e'
                    // quello che l'app fa da sempre (manifest in portrait); qui
                    // si puo' togliere per guardare lo schermo in orizzontale.
                    // L'icona mostra lo STATO, la descrizione l'AZIONE.
                    IconButton(onClick = viewModel::togglePortraitLock) {
                        Icon(
                            imageVector = if (portraitLocked) {
                                Icons.Default.ScreenLockPortrait
                            } else {
                                Icons.Default.ScreenRotation
                            },
                            contentDescription = stringResource(
                                if (portraitLocked) R.string.capture_portrait_unlock
                                else R.string.capture_portrait_lock
                            )
                        )
                    }
                    // Toggle a un tocco: la modalita' outdoor si accende e si
                    // spegne da qui, senza passare dalle Impostazioni.
                    IconButton(onClick = viewModel::toggleOutdoorMode) {
                        Icon(
                            imageVector = if (outdoorMode) {
                                Icons.Default.DarkMode
                            } else {
                                Icons.Default.LightMode
                            },
                            contentDescription = stringResource(
                                if (outdoorMode) R.string.capture_outdoor_off
                                else R.string.capture_outdoor_on
                            )
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
        // In verticale i pannelli stanno sotto l'immagine; in orizzontale,
        // dove lo schermo e' basso e largo, le stanno accanto: schiacciare
        // l'inquadratura in una striscia alta due dita renderebbe inutile
        // proprio la cosa che si sta guardando.
        val landscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        AimLayout(
            landscape = landscape,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            image = { imageModifier ->
            // Area immagine: tutta la zona disponibile sopra (o accanto) ai controlli
            Box(modifier = imageModifier) {
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
                            reticleFocalPx = captureState.reticleFocalPx,
                            reticleTargetM = captureState.reticleTargetM,
                            reticleDistanceM = result?.distanceMeters,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.camera_permission_required))
                        androidx.compose.material3.Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        ) {
                            Text(stringResource(R.string.camera_request_permission))
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
            } // chiude area immagine
            },
            panels = { panelsModifier ->
            // Controlli: sotto l'immagine, o accanto in orizzontale
            Column(
                modifier = panelsModifier
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
                            // In verticale l'altezza e' FISSA (vedi la costante);
                            // in orizzontale il pannello prende lo spazio che
                            // resta sotto i pulsanti, che e' tutto quello che c'e'.
                            .then(
                                if (landscape) Modifier.weight(1f)
                                else Modifier.height(FrozenControlsHeight)
                            ),
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
                            heightInput = heightInput,
                            widthInput = widthInput,
                            distanceInput = measureDistanceInput,
                            measuredSize = captureState.measuredSizeM,
                            distResult = result,
                            pitchDegrees = captureState.pitchAngleDegrees,
                            shotMoved = captureState.shotMovedWarning,
                            hasLines = captureState.lineTopNorm != null &&
                                captureState.lineBottomNorm != null,
                            onHeightTextChanged = viewModel::setHeightInput,
                            onWidthTextChanged = viewModel::setWidthInput,
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
            } // chiude i pannelli
            }
        )
    }

    if (showModeGuide) {
        AlertDialog(
            onDismissRequest = { showModeGuide = false },
            title = {
                Text(stringResource(R.string.capture_how_to_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = stringResource(R.string.capture_how_to_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showModeGuide = false }) {
                    Text(stringResource(R.string.capture_how_to_ok))
                }
            }
        )
    }
}

/**
 * Tacche di riferimento sul mirino, in MOA e in pollici a 20 iarde.
 *
 * Si disegnano **sull'immagine** e non su un pannello perche' servono a
 * confrontare il bersaglio con il proprio mirino guardando nello stesso posto:
 * la scala e' una fila di tacche sul bordo sinistro, con il centro dell'immagine
 * come zero, e accanto c'e' scritto quanto vale ogni tacca e quanto copre il
 * bersaglio. Le due unita' sono sempre scritte insieme: i mirini sono tarati in
 * pollici a 20 iarde, i calcoli si fanno in MOA, e sono lo stesso angolo.
 *
 * Il passo delle tacche non e' fisso (vedi [reticleScale]): a 1x una tacca da
 * 1 MOA sarebbe un decimo di pixel. Meglio poche tacche dichiarate che una
 * scala fine che non misura niente.
 */
@Composable
private fun ReticleScaleLayer(
    focalPx: Float?,
    targetSizeM: Float?,
    distanceM: Float?,
    frameWidth: Int,
    frameHeight: Int,
    displayW: Float,
    displayH: Float,
    offsetY: Float,
    modifier: Modifier = Modifier
) {
    val focal = focalPx?.takeIf { it > 0f && it.isFinite() } ?: return
    val target = targetSizeM?.takeIf { it > 0f } ?: return
    val distance = distanceM?.takeIf { it > 0f } ?: return
    if (frameWidth <= 0 || frameHeight <= 0 || displayW <= 0f || displayH <= 0f) return

    val format = LocalDisplayFormat.current
    val density = LocalDensity.current
    val angolo = remember(target, distance) { angularSize(target, distance) } ?: return
    // Pixel di SCHERMO per MOA: la focale e' in pixel del frame di analisi,
    // quindi va riportata alla scala con cui il frame e' disegnato
    // (displayH / frameHeight).
    val pxPerMoa = remember(focal, displayH, frameHeight) {
        pixelsPerMoa(focal * (displayH / frameHeight))
    } ?: return
    val scala = remember(pxPerMoa, density) {
        reticleScale(pxPerMoa, with(density) { MIN_RETICLE_STEP.toPx() })
    } ?: return

    val centerY = offsetY + displayH / 2f
    val topY = offsetY
    val bottomY = offsetY + displayH
    // Quante tacche stanno nell'immagine, da una parte e dall'altra.
    val kMax = ((displayH / 2f) / scala.stepPx).toInt() + 1
    val passoTxt = format.number(scala.stepMoa.toFloat(), 0)
    val passoPolliciTxt = format.number(scala.stepInchesAt20Yards, 1)
    val bersaglioMoaTxt = format.number(angolo.moa, 0)
    val bersaglioPolliciTxt = format.number(angolo.inchesAt20Yards, 1)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        // Bordo scuro attorno al testo: le tacche finiscono sopra l'immagine,
        // che puo' essere chiara o scura.
        shadow = Shadow(color = Color(0xE6000000), blurRadius = 3f)
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dark = Color(0xCC000000)
            val light = Color(0xFFFFFFFF)
            val shortDx = 12.dp.toPx()
            val longDx = 22.dp.toPx()
            val centerDx = 30.dp.toPx()
            for (k in -kMax..kMax) {
                val y = centerY + k * scala.stepPx
                if (y < topY || y > bottomY) continue
                val dx = when {
                    k == 0 -> centerDx
                    k % LONG_TICK_EVERY == 0 -> longDx
                    else -> shortDx
                }
                // Sotto il tratto chiaro c'e' un tratto scuro: e' la stessa
                // soluzione delle linee di misura, e serve a restare leggibili
                // su un bersaglio bianco e su un bosco scuro.
                drawLine(dark, Offset(0f, y), Offset(dx, y), strokeWidth = 5f)
                drawLine(light, Offset(0f, y), Offset(dx, y), strokeWidth = 2f)
            }
        }
        // Etichette delle sole tacche lunghe: il numero che si conta a occhio.
        // Il valore e' la distanza dallo zero, in MOA (il segno no: la scala e'
        // simmetrica, come nei mirini).
        for (k in -kMax..kMax) {
            if (k % LONG_TICK_EVERY != 0) continue
            val y = centerY + k * scala.stepPx
            if (y < topY || y > bottomY) continue
            Text(
                text = format.number(abs(k * scala.stepMoa).toFloat(), 0),
                style = labelStyle,
                color = Color.White,
                modifier = Modifier.offset(
                    x = 26.dp,
                    y = with(density) { (y - topY).toDp() } - 7.dp
                )
            )
        }
        // Legenda: cosa vale una tacca e quanto copre il bersaglio, in
        // entrambe le unita'.
        Surface(
            color = Color(0xB3000000),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = stringResource(R.string.reticle_scale_label, passoTxt, passoPolliciTxt),
                    style = labelStyle,
                    color = Color.White
                )
                Text(
                    text = stringResource(
                        R.string.reticle_target_label,
                        bersaglioMoaTxt,
                        bersaglioPolliciTxt
                    ),
                    style = labelStyle,
                    color = if (LocalOutdoorMode.current) OutdoorYellow else FosforoGreen
                )
            }
        }
    }
}

/** Distanza minima fra due tacche sullo schermo: sotto si contano male. */
private val MIN_RETICLE_STEP = 26.dp

/** Ogni quante tacche si scrive il numero (e si allunga il tratto). */
private const val LONG_TICK_EVERY = 5

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
    // Azzeramento in un posto solo: serve a inizio gesto, a fine gesto e su
    // gesto ANNULLATO. Su un gesto annullato (secondo dito, gesto di sistema)
    // senza azzeramento le linee restano disegnate a offset dal valore vero
    // anche se il ViewModel non ha ricevuto nessuno spostamento.
    val resetDrag = { panAcc = 0f; spreadAcc = 0f; firstAcc = 0f; secondAcc = 0f }
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
            contentDescription = stringResource(R.string.capture_frozen_lines),
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
                            onDragStart = { resetDrag() },
                            onDragEnd = {
                                if (firstAcc != 0f) onMoveFirst(firstAcc)
                                else if (secondAcc != 0f) onMoveSecond(secondAcc)
                                else if (spreadAcc != 0f) onSpreadCommit(spreadAcc)
                                else if (panAcc != 0f) onPanCommit(panAcc)
                                resetDrag()
                            },
                            onDragCancel = { resetDrag() },
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
                            onDragStart = { resetDrag() },
                            onDragEnd = {
                                if (firstAcc != 0f) onMoveFirst(firstAcc)
                                else if (secondAcc != 0f) onMoveSecond(secondAcc)
                                else if (spreadAcc != 0f) onSpreadCommit(spreadAcc)
                                else if (panAcc != 0f) onPanCommit(panAcc)
                                resetDrag()
                            },
                            onDragCancel = { resetDrag() },
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

        // Niente tacche MOA sul fermo immagine: la misura la fa gia' la distanza
        // tra le due linee, e le tacche coprivano la foto proprio nel momento in
        // cui si guarda se le linee stanno sui bordi. In mira live restano (li'
        // servono a stimare a occhio, prima dello scatto).
    }
}

@Composable
private fun LiveSnapOverlay(
    snap: LiveSnap,
    orientation: LineOrientation,
    reticleFocalPx: Float?,
    reticleTargetM: Float?,
    reticleDistanceM: Float?,
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
            // Quadrato YOLO: perimetro attorno al box primario (paglione o targa).
            // Un solo rect: i due rami che c'erano prima (dl/dt/dr/db e poi
            // rl/rt/rw/rh) calcolavano lo stesso rettangolo e lo disegnavano
            // due volte, con lo stesso colore e lo stesso spessore.
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

        // Tacche del mirino anche in mira live (dove c'e' una distanza stimata:
        // riconoscimento o due tap). Senza distanza non si disegna niente, ed e'
        // il caso normale in modalita' LINEE prima del fermo immagine.
        ReticleScaleLayer(
            focalPx = reticleFocalPx,
            targetSizeM = reticleTargetM,
            distanceM = reticleDistanceM,
            frameWidth = snap.frameWidth,
            frameHeight = snap.frameHeight,
            displayW = displayW,
            displayH = displayH,
            offsetY = offsetY,
            modifier = Modifier.fillMaxSize()
        )
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
    val format = LocalDisplayFormat.current
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
                    Text(
                        stringResource(R.string.capture_freeze_frame),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                // Fattore di moltiplicazione zoom corrente, a destra del bottone
                Surface(
                    color = Color(0x66000000),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.zoom_level,
                            format.number(zoomRatio, 1)
                        ),
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
                    Text(stringResource(R.string.capture_reset_lines))
                }
                FilledTonalButton(
                    onClick = onFreezeToggle,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.capture_live))
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
    val format = LocalDisplayFormat.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = PanelContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (result != null) {
                    stringResource(
                        R.string.capture_distance,
                        format.distance(result.distanceMeters)
                    )
                } else {
                    stringResource(R.string.capture_aim_hint)
                },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = OnPanel
            )
            if (result != null) {
                Text(
                    text = stringResource(
                        R.string.capture_angle,
                        format.number(result.pitchAngleDegrees, 1)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnPanel
                )
                Text(
                    text = stringResource(
                        R.string.capture_compensated,
                        format.distance(result.horizontalDistanceM)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnPanel
                )
                bearingDegrees?.let { bearing ->
                    Text(
                        text = stringResource(
                            R.string.capture_direction,
                            stringResource(ShareMessageComposer.compassLabelRes(bearing)),
                            format.number(bearing, 0)
                        ),
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
internal fun MeasureModePanel(
    direction: MeasureDirection,
    orientation: LineOrientation,
    heightInput: String,
    widthInput: String,
    distanceInput: String,
    measuredSize: Float?,
    distResult: MeasureResult?,
    pitchDegrees: Float?,
    shotMoved: Boolean,
    hasLines: Boolean,
    onHeightTextChanged: (String) -> Unit,
    onWidthTextChanged: (String) -> Unit,
    onDistanceChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val format = LocalDisplayFormat.current
    val horizontal = orientation == LineOrientation.HORIZONTAL
    val sizeLabel = stringResource(
        if (horizontal) R.string.measure_height else R.string.measure_width
    )
    val angleSuffix = if (pitchDegrees != null) {
        // Spazi e separatore restano qui: AAPT toglie gli spazi ai bordi di un
        // valore in un file di stringhe.
        "  \u2022  " + stringResource(
            R.string.capture_angle_suffix,
            format.number(pitchDegrees, 1)
        )
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
            // In cima, sopra ogni numero: se il telefono si e' mosso fra la
            // mira e lo scatto, le linee sono su un'altra inquadratura e il
            // numero qui sotto puo' mentire. Il monito viene PRIMA del dato,
            // non dopo: deve leggerlo chi legge la distanza.
            if (shotMoved) {
                Text(
                    text = stringResource(R.string.capture_shot_moved_warning),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when (direction) {
                MeasureDirection.DISTANZA -> {
                    OutlinedTextField(
                        value = if (horizontal) heightInput else widthInput,
                        onValueChange = if (horizontal) onHeightTextChanged else onWidthTextChanged,
                        label = {
                            Text(
                                stringResource(
                                    if (horizontal) R.string.capture_known_height
                                    else R.string.capture_known_width,
                                    // L'unita' la decide il sistema scelto: il
                                    // campo si chiama "(cm)" o "(in)".
                                    format.sizeUnit
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // Nessuna altezza forzata: si dimensiona Material.
                        modifier = Modifier.fillMaxWidth(),
                        colors = panelFieldColors()
                    )
                    val distText = if (distResult != null) {
                        stringResource(
                            R.string.capture_distance,
                            format.distance(distResult.distanceMeters)
                        )
                    } else {
                        stringResource(R.string.capture_size_hint)
                    }
                    Text(
                        text = buildString {
                            append(distText)
                            if (distResult != null) append(angleSuffix)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnPanel
                    )
                    if (distResult != null) {
                        Text(
                            text = stringResource(
                                R.string.capture_compensated,
                                format.distance(distResult.horizontalDistanceM)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnPanel
                        )
                    }
                }
                MeasureDirection.ALTEZZA -> {
                    OutlinedTextField(
                        value = distanceInput,
                        onValueChange = onDistanceChanged,
                        label = {
                            Text(
                                stringResource(R.string.capture_known_distance, format.distanceUnit)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // Nessuna altezza forzata: si dimensiona Material.
                        modifier = Modifier.fillMaxWidth(),
                        colors = panelFieldColors()
                    )
                    val sizeText = sizeResultText(sizeLabel, distanceInput, hasLines, measuredSize)
                    Text(
                        text = buildString {
                            append(sizeText)
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
                stringResource(
                    if (direction == MeasureDirection.DISTANZA) R.string.capture_measure_distance
                    else R.string.capture_measure_height
                ),
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
                contentDescription = stringResource(
                    if (orientation == LineOrientation.HORIZONTAL) {
                        R.string.capture_switch_to_vertical
                    } else {
                        R.string.capture_switch_to_horizontal
                    }
                ),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(
                    if (orientation == LineOrientation.HORIZONTAL) R.string.capture_lines_vertical
                    else R.string.capture_lines_horizontal
                ),
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
internal val FrozenControlsHeight: Dp
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

@Composable
private fun sizeResultText(
    sizeLabel: String,
    distanceInput: String,
    hasLines: Boolean,
    measuredSize: Float?
): String {
    val format = LocalDisplayFormat.current
    return when {
        // La regola di cosa e' un numero valido (virgola, punto, positivo) sta
        // nel formattatore: qui si chiede solo se il campo e' compilato.
        format.parseDistance(distanceInput) == null ->
            stringResource(R.string.capture_enter_distance, format.distanceUnit)
        !hasLines -> stringResource(R.string.capture_place_lines)
        measuredSize == null -> stringResource(R.string.measurement_unavailable)
        // Sopra il metro la dimensione si legge in metri (o in pollici), sotto
        // in centimetri: l'unita' la sceglie il formattatore, e la stringa non
        // ne nomina nessuna.
        measuredSize >= 0.99f -> stringResource(
            R.string.capture_size_value,
            sizeLabel,
            format.objectSize(measuredSize)
        )
        else -> stringResource(
            R.string.capture_size_value,
            sizeLabel,
            format.objectSizeSmall(measuredSize)
        )
    }
}

