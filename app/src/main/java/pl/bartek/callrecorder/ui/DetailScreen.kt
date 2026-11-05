package pl.bartek.callrecorder.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.bartek.callrecorder.App
import pl.bartek.callrecorder.analysis.AnalysisWorker
import pl.bartek.callrecorder.analysis.AssemblyAiClient
import pl.bartek.callrecorder.analysis.Utterance
import pl.bartek.callrecorder.data.AnalysisStatus
import pl.bartek.callrecorder.data.Recording
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by remember(id) { App.instance.db.recordings().observe(id) }.collectAsState(initial = null)
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val player = remember(id) { PlayerState() }
    DisposableEffect(id) { onDispose { player.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rec?.contactName ?: rec?.phoneNumber ?: "Rozmowa") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
                actions = {
                    val r = rec
                    if (r != null) {
                        IconButton(onClick = { AnalysisWorker.enqueue(ctx, r.id) }) { Icon(Icons.Default.Refresh, "Analizuj ponownie") }
                        IconButton(onClick = { share(ctx, r) }) { Icon(Icons.Default.Share, "Udostępnij") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Usuń") }
                    }
                },
            )
        },
    ) { pad ->
        val r = rec ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "${Fmt.date(r.startedAt)} · ${Fmt.duration(r.durationMs)}" + (r.audioSource?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Player(r.filePath, player)
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Podsumowanie") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transkrypcja") })
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                StatusInfo(r)
                val summary = remember(r.summaryJson) { r.summaryJson?.let { runCatching { JSONObject(it) }.getOrNull() } }
                if (tab == 0) {
                    if (summary != null) SummaryView(summary)
                } else {
                    val utterances = remember(r.transcriptJson) { AssemblyAiClient.fromJson(r.transcriptJson) }
                    TranscriptView(utterances, summary?.optJSONObject("mowcy")) { player.seekTo(it) }
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Usunąć nagranie?") },
        text = { Text("Nagranie, transkrypcja i podsumowanie zostaną trwale usunięte.") },
        confirmButton = {
            TextButton(onClick = {
                val r = rec ?: return@TextButton
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        File(r.filePath).delete()
                        App.instance.db.recordings().delete(r)
                    }
                    onBack()
                }
            }) { Text("Usuń") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
    )
}

@Composable
private fun StatusInfo(r: Recording) {
    val msg = when (r.status) {
        AnalysisStatus.PENDING -> "Czeka na analizę (wymaga internetu)…"
        AnalysisStatus.TRANSCRIBING -> "Trwa transkrypcja z rozpoznawaniem mówców…"
        AnalysisStatus.SUMMARIZING -> "Claude analizuje rozmowę…"
        AnalysisStatus.ERROR -> "Błąd analizy: ${r.error}"
        AnalysisStatus.NO_KEYS -> "Brak kluczy API – uzupełnij je w Ustawieniach i kliknij ⟳."
        AnalysisStatus.DONE -> null
    } ?: return
    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r.status in setOf(AnalysisStatus.PENDING, AnalysisStatus.TRANSCRIBING, AnalysisStatus.SUMMARIZING)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            msg + if (r.status == AnalysisStatus.PENDING && r.error != null) "\n${r.error}" else "",
            color = if (r.status == AnalysisStatus.ERROR || r.status == AnalysisStatus.NO_KEYS) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ podsumowanie

@Composable
private fun SummaryView(s: JSONObject) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            s.optString("tytul").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            s.optString("temat").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Section("Podsumowanie") { Text(s.optString("podsumowanie")) }
            Bullets("Kluczowe punkty", s.optJSONArray("kluczowe_punkty"))
            Bullets("Ustalenia", s.optJSONArray("ustalenia"))
            s.optJSONArray("zadania")?.takeIf { it.length() > 0 }?.let { arr ->
                Section("Zadania / działania") {
                    for (i in 0 until arr.length()) {
                        val z = arr.optJSONObject(i) ?: continue
                        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("☐ " + z.optString("co"), fontWeight = FontWeight.Medium)
                                val meta = listOf(
                                    z.optString("kto").takeIf { it.isNotBlank() && it != "null" }?.let { "Kto: $it" },
                                    z.optString("termin").takeIf { it.isNotBlank() && it != "null" }?.let { "Termin: $it" },
                                ).filterNotNull().joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            s.optJSONArray("terminy")?.takeIf { it.length() > 0 }?.let { arr ->
                Section("Terminy") {
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        Text("📅 ${t.optString("kiedy")} – ${t.optString("co")}")
                    }
                }
            }
            Bullets("Otwarte kwestie", s.optJSONArray("otwarte_kwestie"))
            s.optString("ton").takeIf { it.isNotBlank() && it != "null" }?.let { Section("Charakter rozmowy") { Text(it) } }
            s.optString("uwagi").takeIf { it.isNotBlank() && it != "null" }?.let {
                Text("ℹ️ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Bullets(title: String, arr: JSONArray?) {
    if (arr == null || arr.length() == 0) return
    Section(title) {
        for (i in 0 until arr.length()) Text("• " + arr.optString(i), modifier = Modifier.padding(vertical = 2.dp))
    }
}

// ------------------------------------------------------------------ transkrypcja

private val speakerColors = listOf(Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF059669), Color(0xFFD97706), Color(0xFF7C3AED))

@Composable
private fun TranscriptView(list: List<Utterance>, names: JSONObject?, onSeek: (Long) -> Unit) {
    if (list.isEmpty()) return
    val speakers = list.map { it.speaker }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { u ->
            val color = speakerColors[speakers.indexOf(u.speaker).coerceAtLeast(0) % speakerColors.size]
            val name = names?.optString(u.speaker)?.takeIf { it.isNotBlank() } ?: "Mówca ${u.speaker}"
            Row(Modifier.fillMaxWidth().clickable { onSeek(u.startMs) }) {
                Spacer(Modifier.width(4.dp).height(40.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "$name · ${Fmt.duration(u.startMs)}",
                        color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    )
                    SelectionContainer { Text(u.text) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ odtwarzacz

private class PlayerState {
    var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
    var position by mutableFloatStateOf(0f)
    var duration by mutableFloatStateOf(1f)

    fun ensure(path: String): MediaPlayer? {
        mp?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { this@PlayerState.playing = false }
            }
        }.getOrNull()?.also { mp = it; duration = it.duration.toFloat().coerceAtLeast(1f) }
    }

    fun seekTo(ms: Long) {
        val p = mp ?: return
        p.seekTo(ms.toInt())
        position = ms.toFloat()
        if (!p.isPlaying) { p.start(); playing = true }
    }

    fun release() {
        mp?.release(); mp = null; playing = false
    }
}

@Composable
private fun Player(path: String, st: PlayerState) {
    LaunchedEffect(path) { withContext(Dispatchers.IO) { st.ensure(path) } }
    LaunchedEffect(st.playing) {
        while (st.playing) {
            st.position = (st.mp?.currentPosition ?: 0).toFloat()
            delay(300)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = {
            val p = st.ensure(path) ?: return@FilledIconButton
            if (p.isPlaying) { p.pause(); st.playing = false } else { p.start(); st.playing = true }
        }, shape = CircleShape) {
            Icon(if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Odtwórz")
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = st.position.coerceIn(0f, st.duration),
            onValueChange = { st.position = it; st.mp?.seekTo(it.toInt()) },
            valueRange = 0f..st.duration,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(Fmt.duration(st.position.toLong()), style = MaterialTheme.typography.labelSmall)
    }
}

private fun share(ctx: android.content.Context, r: Recording) {
    val s = r.summaryJson?.let { runCatching { JSONObject(it) }.getOrNull() }
    val text = buildString {
        appendLine("Rozmowa: ${r.contactName ?: r.phoneNumber ?: "?"} – ${Fmt.date(r.startedAt)}")
        if (s != null) {
            appendLine(s.optString("tytul")); appendLine()
            appendLine(s.optString("podsumowanie")); appendLine()
            fun list(title: String, key: String) {
                val a = s.optJSONArray(key) ?: return
                if (a.length() == 0) return
                appendLine("$title:")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i)
                    appendLine("- " + (o?.let { "${it.optString("co")} (${it.optString("kto")}, ${it.optString("termin")})" } ?: a.optString(i)))
                }
                appendLine()
            }
            list("Ustalenia", "ustalenia"); list("Zadania", "zadania"); list("Otwarte kwestie", "otwarte_kwestie")
        }
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(r.filePath))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Udostępnij rozmowę"))
}
