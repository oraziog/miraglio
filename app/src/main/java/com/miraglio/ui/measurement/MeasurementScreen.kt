package com.miraglio.ui.measurement

import android.Manifest
import android.content.pm.PackageManager
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
import com.miraglio.ui.places.PlaceShareBar
import com.miraglio.ui.places.ShareIntents
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

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshGpsStatus()
            delay(5000)
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
                        hasFix = uiState.fixAccuracyM != null,
                        estimatedDistance = uiState.estimatedDistanceM,
                        gpsEnabled = uiState.gpsEnabled,
                        onStart = viewModel::startMeasurement
                    )
                    MeasurementPhase.TRACKING -> TrackingContent(
                        distanceM = uiState.currentDistanceM,
                        walkedM = uiState.walkedDistanceM,
                        waitingForFix = uiState.waitingForFix,
                        fixAccuracy = uiState.fixAccuracyM,
                        message = uiState.message,
                        onFinish = viewModel::finishMeasurement,
                        onCancel = viewModel::reset
                    )
                    MeasurementPhase.DONE -> DoneContent(
                        measuredDistanceM = uiState.finalDistanceM,
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
    hasFix: Boolean,
    estimatedDistance: Float?,
    gpsEnabled: Boolean,
    onStart: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = "Premi misura e spostati nel punto finale",
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
                hasFix -> "Fix GPS disponibile"
                else -> "In attesa del primo fix GPS (all'aperto)..."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        estimatedDistance?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Stima ultima distanza: $it m",
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
    message: String?,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = if (waitingForFix) "In attesa del fix GPS..." else "Muoviti fino al paglione",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (!waitingForFix) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${"%.1f".format(distanceM)} m",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Percorsi: ${"%.1f".format(walkedM)} m",
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
            enabled = !waitingForFix
        )
        fixAccuracy?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Precisione GPS: ±${"%.1f".format(it)} m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
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
                text = "${"%.1f".format(measuredDistanceM)} m",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Percorso a piedi: ${"%.1f".format(walkedM)} m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            estimatedDistanceM?.let { estimated ->
                val delta = measuredDistanceM - estimated
                val withinTolerance = kotlin.math.abs(delta) <= TOLERANCE_METERS
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (withinTolerance) {
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
                        Text(
                            text = "Stima dell'app: ${"%.1f".format(estimated)} m",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Scarto: ${if (delta >= 0f) "+" else ""}${"%.1f".format(delta)} m",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (withinTolerance) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
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
                                color = if (withinTolerance) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
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