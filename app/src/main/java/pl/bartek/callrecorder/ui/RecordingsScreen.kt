package pl.bartek.callrecorder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.bartek.callrecorder.App
import pl.bartek.callrecorder.data.AnalysisStatus
import pl.bartek.callrecorder.data.Direction
import pl.bartek.callrecorder.data.Recording
import pl.bartek.callrecorder.service.CallRecorderService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(activity: MainActivity, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val all by remember { App.instance.db.recordings().observeAll() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val serviceOn = remember { CallRecorderService.isEnabled(activity) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { activity.importAudio(it) }
    }

    val list = if (query.isBlank()) all else all.filter { r ->
        listOfNotNull(r.title, r.contactName, r.phoneNumber, r.summaryJson, r.transcriptJson)
            .any { it.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rozmowy") },
                actions = {
                    IconButton(onClick = { picker.launch("audio/*") }) { Icon(Icons.Default.FileUpload, "Importuj nagranie") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ustawienia") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!serviceOn) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Nagrywanie nieaktywne", fontWeight = FontWeight.Bold)
                            Text("Dokończ konfigurację: uprawnienia i usługa dostępności.", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onSettings) { Text("Konfiguruj") }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Szukaj w rozmowach, ustaleniach, osobach…") },
                    singleLine = true,
                )
            }
            if (list.isEmpty()) item {
                Text(
                    if (all.isEmpty()) "Brak nagrań. Rozmowy będą nagrywane automatycznie, a po zakończeniu pojawi się tu transkrypcja i podsumowanie."
                    else "Brak wyników",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(list, key = { it.id }) { RecordingRow(it) { onOpen(it.id) } }
        }
    }
}

@Composable
private fun RecordingRow(r: Recording, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (r.direction) {
                    Direction.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
                    Direction.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                    else -> Icons.Default.Mic
                },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.contactName ?: r.phoneNumber ?: if (r.direction == Direction.IMPORTED) "Nagranie zaimportowane" else "Nieznany numer",
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                r.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    "${Fmt.date(r.startedAt)} · ${Fmt.duration(r.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(r.status)
        }
    }
}

@Composable
fun StatusBadge(s: AnalysisStatus) {
    val (label, color) = when (s) {
        AnalysisStatus.PENDING -> "w kolejce" to MaterialTheme.colorScheme.outline
        AnalysisStatus.TRANSCRIBING -> "transkrypcja…" to MaterialTheme.colorScheme.tertiary
        AnalysisStatus.SUMMARIZING -> "analiza…" to MaterialTheme.colorScheme.tertiary
        AnalysisStatus.DONE -> "" to MaterialTheme.colorScheme.primary
        AnalysisStatus.ERROR -> "błąd" to MaterialTheme.colorScheme.error
        AnalysisStatus.NO_KEYS -> "brak kluczy" to MaterialTheme.colorScheme.error
    }
    if (label.isNotEmpty()) Text(label, color = color, style = MaterialTheme.typography.labelSmall)
}
