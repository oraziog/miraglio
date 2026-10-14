package com.miraglio.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.data.focal.FocalPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFocalCapture: () -> Unit = {},
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val focalPoints by viewModel.focalPoints.collectAsState()
    val focal by viewModel.focal.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrazione", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FocalCalibrationCard(
                fxPx = focal.first,
                fyPx = focal.second,
                theoreticalPx = viewModel.theoreticalFocalPx,
                points = focalPoints,
                onAddPoint = onNavigateToFocalCapture,
                onDeletePoint = viewModel::deleteFocalPoint
            )
        }
    }
}

@Composable
private fun FocalCalibrationCard(
    fxPx: Float?,
    fyPx: Float?,
    theoreticalPx: Float,
    points: List<FocalPoint>,
    onAddPoint: () -> Unit,
    onDeletePoint: (Long) -> Unit
) {
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
                Text("Calibrazione focale camera", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onAddPoint) {
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi punto calibrazione")
                }
            }
            val fxLabel = fxPx?.let { "${it.toInt()} px" } ?: "teorica ${theoreticalPx.toInt()} px"
            val fyLabel = fyPx?.let { "${it.toInt()} px" } ?: "teorica ${theoreticalPx.toInt()} px"
            Text(
                text = "fx: $fxLabel — fy: $fyLabel",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Senza punti la focale è teorica (FOV diagonale 67°). Aggiungi punti " +
                    "fotografando un bersaglio noto a distanza nota: d = dimensione × focale / pixel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            if (points.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Aggiungi punto calibrazione (${points.size})",
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
                            text = "${p.distanceM} m · ${p.pixels.toInt()} px · " +
                                "${(p.knownSizeM * 100).toInt()} cm · " +
                                (if (p.axis == "H") "orizz" else "vert") +
                                (if (p.zoom != 1f) " · ${"%.1f".format(p.zoom)}x" else "") +
                                " · f≈${p.impliedFocalPx.toInt()} px",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeletePoint(p.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Elimina punto",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Aggiungi punto calibrazione",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onAddPoint() }
                )
            }
        }
    }
}
