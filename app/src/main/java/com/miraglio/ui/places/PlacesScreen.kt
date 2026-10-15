package com.miraglio.ui.places

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.miraglio.data.places.PlaceKind
import com.miraglio.data.places.PlaceRecord
import com.miraglio.domain.format.ItalianFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    onNavigateBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel()
) {
    val places by viewModel.places.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Luoghi memorizzati", fontWeight = FontWeight.Bold) },
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
        if (places.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Nessun luogo salvato",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Salva uno scatto da Tiro Live o una misura da Verifica distanza",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(places, key = { it.id }) { record ->
                    PlaceListItem(
                        record = record,
                        thumbnail = viewModel,
                        onClick = { onOpenPlace(record.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceListItem(
    record: PlaceRecord,
    thumbnail: PlacesViewModel,
    onClick: () -> Unit
) {
    var thumb by remember(record.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id) {
        thumb = thumbnail.thumbnail(record)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            val currentThumb = thumb
            if (currentThumb != null) {
                Image(
                    bitmap = currentThumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp)
                )
            } else {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val distance = record.distanceM
                    ?: record.horizontalDistanceM
                    ?: record.walkedM
                Text(
                    text = buildString {
                        append(if (record.kind == PlaceKind.GPS) "Misura GPS" else "Scatto")
                        distance?.let { append("  ·  ${ItalianFormat.meters(it)}") }
                        // Precisione della misura GPS: il numero va bene
                        // anche se incerto, ma l'incertezza si dichiara.
                        record.gpsErrorM?.let {
                            append("  ·  ${com.miraglio.domain.share.ShareMessageComposer.accuracyLabel(it)}")
                        }
                        record.bearingDeg?.let {
                            append("  ·  ${com.miraglio.domain.share.ShareMessageComposer.compassLabel(it)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
