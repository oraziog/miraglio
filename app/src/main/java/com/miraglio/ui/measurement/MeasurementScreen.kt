package com.miraglio.ui.measurement

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.data.places.PlaceSaveState
import com.miraglio.domain.format.ItalianFormat
import com.miraglio.domain.location.MeasurementRules
import com.miraglio.ui.places.PlaceShareBar
import com.miraglio.ui.places.ShareIntents
import com.miraglio.ui.theme.LocalOutdoorMode
import com.miraglio.ui.theme.OnPanel
import com.miraglio.ui.theme.OutdoorAmber
import com.miraglio.ui.theme.OutdoorGreen
import com.miraglio.ui.theme.OutdoorRed
import com.miraglio.ui.theme.PanelContainer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlaces: () -> Unit = {},
    viewModel: MeasurementViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasPermission = granted
        if (granted) {
            viewModel.onPermissionGranted()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            viewModel.onPermissionGranted()
        }
    }

    // Ogni secondo: oltre allo stato del GPS serve a far rivalutare l'eta' dei
    // fix, cosi' un fix che smette di arrivare non resta "buono" a schermo.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshGpsStatus()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verifica distanza", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToPlaces) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = "Luoghi salvati",
                            tint = MaterialTheme.colorScheme.onPrimary
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
        ) {
            if (!uiState.gpsEnabled) {
                GpsWarningBanner()
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!hasPermission) {
                PermissionPrompt()
            } else {
                when (uiState.phase) {
                    MeasurementPhase.IDLE -> IdleContent(
                        gpsEnabled = uiState.gpsEnabled,
                        fixAccuracyM = uiState.fixAccuracyM,
                        fixReadyForStart = uiState.fixReadyForStart,
                        estimatedDistance = uiState.estimatedDistanceM,
                        onStart = viewModel::startMeasurement
                    )
                    MeasurementPhase.TRACKING -> TrackingContent(
                        distanceM = uiState.currentDistanceM,
                        walkedM = uiState.walkedDistanceM,
                        waitingForFix = uiState.waitingForFix,
                        fixAccuracy = uiState.fixAccuracyM,
                        fixProvider = uiState.fixProvider,
                        fixUsable = uiState.fixUsable,
                        canFinish = uiState.canFinish,
                        message = uiState.message,
                        finishWarningErrorM = uiState.finishWarningErrorM,
                        onFinish = viewModel::finishMeasurement,
                        onConfirmFinish = viewModel::confirmFinishMeasurement,
                        onDismissFinishWarning = viewModel::dismissFinishWarning,
                        onCancel = viewModel::reset
                    )
                    MeasurementPhase.DONE -> DoneContent(
                        measuredDistanceM = uiState.finalDistanceM,
                        finalErrorM = uiState.finalErrorM,
                        estimatedDistanceM = uiState.estimatedDistanceM,
                        walkedM = uiState.walkedDistanceM,
                        shareMessage = uiState.shareMessage,
                        qrContent = uiState.qrContent,
                        saveState = viewModel.placeSaveState.collectAsState().value,
                        onShare = {
                            uiState.shareMessage?.let { ShareIntents.shareText(context, it) }
                        },
                        onSave = viewModel::saveGpsPlace,
                        onNewMeasurement = viewModel::reset
                    )
                }
            }
        }
    }
}

@Composable
private fun GpsWarningBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                "GPS disattivato. Attiva la posizione (GPS) per misurare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun PermissionPrompt() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Servono i permessi di posizione per la misura",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun IdleContent(
    gpsEnabled: Boolean,
    fixAccuracyM: Float?,
    fixReadyForStart: Boolean,
    estimatedDistance: Float?,
    onStart: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = "Premi MISURA: la partenza si blocca sul fix del momento, " +
                "poi spostati fino al punto finale",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        BigActionButton(
            text = "MISURA",
            containerColor = MaterialTheme.colorScheme.primary,
            onClick = onStart,
            enabled = gpsEnabled
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when {
                !gpsEnabled -> "GPS assente"
                // La partenza vuole un fix di adesso entro ±10 m: dirlo prima
                // evita di armare la misura e restare fermi senza capire perche'.
                fixReadyForStart ->
                    "Fix GPS disponibile (±${ItalianFormat.meters(fixAccuracyM ?: 0f)})"
                fixAccuracyM != null ->
                    "Fix da migliorare: ora ±${ItalianFormat.meters(fixAccuracyM)} " +
                        "(serve entro ±${ItalianFormat.meters(MeasurementRules.START_MAX_ACCURACY_M, 0)}), " +
                        "all'aperto"
                else -> "In attesa del primo fix satellitare (all'aperto)..."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        estimatedDistance?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Stima ultima distanza: ${ItalianFormat.meters(it, 2)}",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TrackingContent(
    distanceM: Float,
    walkedM: Float,
    waitingForFix: Boolean,
    fixAccuracy: Float?,
    fixProvider: String?,
    fixUsable: Boolean,
    canFinish: Boolean,
    message: String?,
    finishWarningErrorM: Float?,
    onFinish: () -> Unit,
    onConfirmFinish: () -> Unit,
    onDismissFinishWarning: () -> Unit,
    onCancel: () -> Unit
) {
    // La chiusura e' stata chiesta, ma l'errore stimato sui due fix supera la
    // soglia: prima di registrare la distanza si chiede se vale la pena
    // aspettare un fix satellitare migliore.
    finishWarningErrorM?.let { errorM ->
        AlertDialog(
            onDismissRequest = onDismissFinishWarning,
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Errore della misura elevato") },
            text = {
                Text(
                    "L'errore della misura è maggiore di " +
                        "${ItalianFormat.meters(MeasurementViewModel.FINISH_WARN_ERROR_METERS, 0)} " +
                        "(stimato ±${ItalianFormat.meters(errorM)}). " +
                        "Vuoi aspettare un fix dal satellite per un errore minore?"
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmFinish) { Text("Continua") }
            },
            dismissButton = {
                TextButton(onClick = onDismissFinishWarning) { Text("Aspetta") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = if (waitingForFix) {
                "In attesa del fix di partenza"
            } else {
                "Muoviti fino al paglione"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (waitingForFix) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "La partenza vuole un fix satellitare di adesso entro " +
                    "±${ItalianFormat.meters(MeasurementRules.START_MAX_ACCURACY_M, 0)}" +
                    (fixAccuracy?.let { " — ora ±${ItalianFormat.meters(it)}" } ?: "") +
                    ". Resta in cielo aperto.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!waitingForFix) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = ItalianFormat.meters(distanceM),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Percorso a piedi: ${ItalianFormat.meters(walkedM)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        message?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        BigActionButton(
            text = "FINO A QUI",
            containerColor = MaterialTheme.colorScheme.tertiary,
            onClick = onFinish,
            // Si chiude solo con un fix di adesso: senza, la distanza sarebbe
            // quella di un fix vecchio e non del punto in cui sei.
            enabled = canFinish
        )
        fixAccuracy?.let { accuracy ->
            // Entro soglia il numero e' verde; sopra (o con un fix che non
            // arriva piu') diventa rosso lampeggiante per dire "non fidarti
            // ancora di questo numero", invece di mostrare un dato credibile
            // solo in apparenza.
            val insufficient = !fixUsable || accuracy > GPS_ACCURACY_WARN_METERS
            var blinkOn by remember(insufficient) { mutableStateOf(true) }
            LaunchedEffect(insufficient) {
                if (!insufficient) return@LaunchedEffect
                while (true) {
                    delay(GPS_BLINK_MS)
                    blinkOn = !blinkOn
                }
            }
            val alertedColor = GPS_BAD_FIX_COLOR.copy(alpha = if (blinkOn) 1f else 0.15f)

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Precisione GPS: ±${ItalianFormat.meters(accuracy)}${providerSuffix(fixProvider)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (insufficient) {
                    alertedColor
                } else {
                    // Fix affidabile entro la soglia: verde, per distinguerlo a
                    // colpo d'occhio dai casi in cui il numero non e' credibile.
                    GPS_GOOD_FIX_COLOR
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (insufficient) {
                Text(
                    text = if (fixUsable) {
                        "Fix scadente: con questa precisione la misura può " +
                            "sbagliare di oltre ±${ItalianFormat.meters(GPS_ACCURACY_WARN_METERS, 0)}. " +
                            "Attendi in cielo aperto."
                    } else {
                        "Fix vecchio o scadente: la misura si chiude solo con un " +
                            "fix satellitare di adesso, entro " +
                            "±${ItalianFormat.meters(MeasurementRules.TRACK_MAX_ACCURACY_M, 0)}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Annulla")
        }
    }
}

@Composable
private fun DoneContent(
    measuredDistanceM: Float?,
    finalErrorM: Float?,
    estimatedDistanceM: Float?,
    walkedM: Float,
    shareMessage: String?,
    qrContent: String?,
    saveState: PlaceSaveState,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNewMeasurement: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (measuredDistanceM == null) {
            Text(
                "Misura non disponibile",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "Distanza misurata",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ItalianFormat.meters(measuredDistanceM),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Percorso a piedi: ${ItalianFormat.meters(walkedM)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // L'errore stimato accompagna sempre il numero finale: la misura
            // resta registrata anche sopra la soglia, ma l'incertezza va
            // dichiarata (e viene poi salvata e condivisa con lo stesso ±).
            finalErrorM?.let { errorM ->
                val poorPrecision = errorM > TOLERANCE_METERS
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Errore stimato: ±${ItalianFormat.meters(errorM)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (poorPrecision) FontWeight.Bold else FontWeight.Normal,
                    color = if (poorPrecision) {
                        OUT_OF_TOLERANCE_COLOR
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (poorPrecision) {
                    Text(
                        text = "Precisione limitata: il valore può sbagliare di diversi metri.",
                        style = MaterialTheme.typography.labelSmall,
                        color = OUT_OF_TOLERANCE_COLOR,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            estimatedDistanceM?.let { estimated ->
                val delta = measuredDistanceM - estimated
                val withinTolerance = kotlin.math.abs(delta) <= TOLERANCE_METERS
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        // In outdoor le tinte pastello non esistono piu': la
                        // card diventa nera con testo giallo, come i pannelli
                        // sopra l'immagine, cosi' la distinzione la fanno le
                        // icone/righe colorate e non il fondo.
                        containerColor = if (LocalOutdoorMode.current) {
                            PanelContainer
                        } else if (withinTolerance) {
                            Color(0xFFE8F5E9)
                        } else {
                            Color(0xFFFFEBEE)
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // La card ha sfondo pastello chiaro: il content color
                        // ereditato era un grigio senza contrasto.
                        Text(
                            text = "Stima dell'app: ${ItalianFormat.meters(estimated)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnPanel
                        )
                        Text(
                            text = "Scarto: ${ItalianFormat.signed(delta)} m",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = OnPanel
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (withinTolerance) GPS_GOOD_FIX_COLOR else OUT_OF_TOLERANCE_COLOR
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = if (withinTolerance) {
                                    "Entro ±3 m ✓"
                                } else {
                                    "Fuori da ±3 m ✗"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (withinTolerance) GPS_GOOD_FIX_COLOR else OUT_OF_TOLERANCE_COLOR
                            )
                        }
                    }
                }
            } ?: run {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Usa prima Tiro Live per avere una stima da confrontare.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Salva/Condividi/QR del luogo: senza fix GPS Condividi resta disabilitata
        // e il QR non e' disponibile (il messaggio di avviso viene da monte).
        Spacer(modifier = Modifier.height(16.dp))
        PlaceShareBar(
            saveState = saveState,
            hasPin = qrContent != null,
            qrContent = qrContent,
            shareMessage = shareMessage,
            onShareClick = onShare,
            onSaveClick = onSave
        )

        Spacer(modifier = Modifier.height(16.dp))
        BigActionButton(
            text = "NUOVA MISURA",
            containerColor = MaterialTheme.colorScheme.primary,
            onClick = onNewMeasurement
        )
    }
}

@Composable
private fun BigActionButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = containerColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

private const val TOLERANCE_METERS = 3f

/**
 * Sopra questa precisione (raggio di confidenza del fix, in metri) il fix
 * diventa rosso lampeggiante. All'aperto un fix satellitare scende a ~3 m:
 * la soglia sta appena sopra, cosi' un fix normale resta verde e lampeggia
 * solo cio' che davvero non e' credibile (rete, GNSS senza lock).
 */
private const val GPS_ACCURACY_WARN_METERS = 4f

/** Periodo di lampeggio della precisione insufficiente. */
private const val GPS_BLINK_MS = 500L

/**
 * Arancione del rigo "fuori tolleranza": attenzione, non errore bloccante.
 * In outdoor passa a una tinta piu' luminosa, che regge sul fondo nero.
 */
private val OUT_OF_TOLERANCE_COLOR: Color
    @Composable get() = if (LocalOutdoorMode.current) OutdoorAmber else Color(0xFFF57C00)

/**
 * Verde di un fix GPS entro soglia: il numero mostrato e' credibile.
 * Il verde scuro normale sul nero non arriva alla soglia di contrasto.
 */
private val GPS_GOOD_FIX_COLOR: Color
    @Composable get() = if (LocalOutdoorMode.current) OutdoorGreen else Color(0xFF2E7D32)

/** Rosso lampeggiante di un fix GPS oltre soglia, piu' brillante in outdoor. */
private val GPS_BAD_FIX_COLOR: Color
    @Composable get() = if (LocalOutdoorMode.current) OutdoorRed else Color(0xFFD32F2F)

/** Origine del fix, per capire da dove viene l'accuratezza mostrata. */
private fun providerSuffix(provider: String?): String = when (provider) {
    LocationManager.GPS_PROVIDER -> " (satellite)"
    LocationManager.NETWORK_PROVIDER -> " (rete)"
    else -> ""
}