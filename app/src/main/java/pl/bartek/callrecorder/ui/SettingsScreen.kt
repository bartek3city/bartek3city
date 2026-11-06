package pl.bartek.callrecorder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.bartek.callrecorder.root.RootHelper
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import pl.bartek.callrecorder.data.Settings
import pl.bartek.callrecorder.service.CallRecorderService

private val PERMISSIONS = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        CallRecorderService.instance?.refreshListener()
    }

    val refreshTick = refresh // odczyt stanu → odświeżenie statusów po powrocie z ustawień systemu
    check(refreshTick >= 0)
    val permsOk = PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val accOk = CallRecorderService.isEnabled(ctx)
    val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    val keysOk = settings.assemblyAiKey.isNotBlank() && settings.anthropicKey.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Konfiguracja", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Step(
                done = permsOk, title = "1. Uprawnienia",
                desc = "Mikrofon, stan telefonu, rejestr połączeń (numer i kierunek), kontakty, powiadomienia.",
                action = "Nadaj",
            ) { permLauncher.launch(PERMISSIONS) }

            Step(
                done = accOk, title = "2. Usługa dostępności",
                desc = "Ustawienia → Dostępność → Zainstalowane aplikacje → „Rejestrator Rozmów” → Włącz. " +
                    "Umożliwia nagrywanie w trakcie rozmowy i przycisk REC nad ekranem dzwonienia.\n" +
                    "Jeśli przełącznik jest wyszarzony (Android 13+, aplikacja spoza Sklepu Play): " +
                    "Informacje o aplikacji → ⋮ → „Zezwól na ustawienia z ograniczonym dostępem”.",
                action = "Otwórz",
                secondary = "Info o aplikacji" to {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                },
            ) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }

            Step(
                done = batteryOk, title = "3. Bez ograniczeń baterii",
                desc = "Żeby system nie usypiał usługi i analizy w tle. Wybierz „Rejestrator Rozmów” → Bez ograniczeń.",
                action = "Otwórz",
            ) { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

            Step(
                done = keysOk, title = "4. Klucze API (poniżej)",
                desc = "AssemblyAI – transkrypcja z podziałem na mówców; Anthropic – analiza i podsumowanie (Claude).",
                action = null,
            ) {}

            HorizontalDivider()
            Text("Nagrywanie z linii (obie strony)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LineCaptureSection(settings)

            HorizontalDivider()
            Text("Nagrywanie", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Toggle("Nagrywaj automatycznie każdą rozmowę", "Start po odebraniu / nawiązaniu połączenia", settings.autoRecord) { settings.autoRecord = it }
            Toggle("Przycisk REC na ekranie dzwonienia", "Pływający przycisk start/stop w trakcie rozmowy (można go przesuwać)", settings.showOverlay) { settings.showOverlay = it }
            Toggle(
                "Włączaj głośnik podczas rozmowy",
                "Znacznie poprawia nagranie głosu rozmówcy, gdy telefon nie pozwala nagrywać linii (większość telefonów z Androidem 10+)",
                settings.autoSpeaker,
            ) { settings.autoSpeaker = it }
            Toggle("Analizuj automatycznie po rozmowie", "Transkrypcja + podsumowanie zaraz po zakończeniu połączenia", settings.autoAnalyze) { settings.autoAnalyze = it }

            Dropdown(
                label = "Źródło dźwięku",
                options = listOf(
                    "AUTO" to "Automatycznie (zalecane)",
                    "VOICE_CALL" to "Linia rozmowy (obie strony – jeśli telefon pozwala)",
                    "VOICE_RECOGNITION" to "Mikrofon – rozpoznawanie mowy",
                    "VOICE_COMMUNICATION" to "Mikrofon – komunikacja (redukcja echa)",
                    "MIC" to "Mikrofon – surowy",
                ),
                selected = settings.audioSource,
            ) { settings.audioSource = it }

            HorizontalDivider()
            Text("Analiza", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Dropdown(
                label = "Język rozmów",
                options = listOf("pl" to "Polski", "en" to "Angielski", "de" to "Niemiecki", "auto" to "Wykrywaj automatycznie"),
                selected = settings.language,
            ) { settings.language = it }

            TextSetting("Twoje imię (do rozpoznania, kto jest kim)", settings.myName) { settings.myName = it }
            TextSetting("Klucz API AssemblyAI", settings.assemblyAiKey, secret = true) { settings.assemblyAiKey = it; refresh++ }
            TextSetting("Klucz API Anthropic (Claude)", settings.anthropicKey, secret = true) { settings.anthropicKey = it; refresh++ }
            TextSetting("Model Claude", settings.claudeModel) { settings.claudeModel = it }

            Text(
                "Nagrania i wyniki analizy są przechowywane wyłącznie na telefonie. Do analizy plik audio jest wysyłany do AssemblyAI, " +
                    "a tekst transkrypcji do Anthropic. Pamiętaj o przepisach dot. nagrywania rozmów – dobrą praktyką jest poinformowanie rozmówcy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(
    done: Boolean, title: String, desc: String, action: String?,
    secondary: Pair<String, () -> Unit>? = null, onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall)
                if (!done && (action != null || secondary != null)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    action?.let { Button(onClick = onClick) { Text(it) } }
                    secondary?.let { (label, fn) -> OutlinedButton(onClick = fn) { Text(label) } }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
    }
}

@Composable
private fun TextSetting(label: String, initial: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(selected) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == current }?.second ?: current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { current = key; onSelect(key); expanded = false })
            }
        }
    }
}


@Composable
private fun LineCaptureSection(settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lineOk = remember { RootHelper.hasLineCapture(ctx) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<RootHelper.Result?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (lineOk) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                    tint = if (lineOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (lineOk) "Tryb root aktywny – nagrywanie bezpośrednio z linii (VOICE_CALL)"
                    else "Tryb root nieaktywny",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Wymaga telefonu z rootem (Magisk, KernelSU lub APatch). Aplikacja instaluje się jako systemowa " +
                    "z uprawnieniem CAPTURE_AUDIO_OUTPUT i nagrywa obie strony rozmowy we wszystkich trybach " +
                    "(słuchawka, głośnik, Bluetooth). Po instalacji modułu uruchom telefon ponownie. " +
                    "Uwaga: rootowanie Samsunga trwale wyłącza Knox (Samsung Wallet, Folder Bezpieczny, Samsung Pass).",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!lineOk) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        log = withContext(Dispatchers.IO) { RootHelper.installModule(ctx) }
                        busy = false
                    }
                }) { Text(if (busy) "Instalowanie…" else "Zainstaluj moduł root") }
            }
        }
    }

    Toggle(
        "Używaj wbudowanego rejestratora rozmów (bez roota)",
        "Samsung: Telefon → ⋮ → Ustawienia → Nagrywaj połączenia → Automatyczne nagrywanie: włącz. " +
            "Po każdej rozmowie aplikacja pobierze to nagranie (obie strony z linii) i je przeanalizuje; " +
            "własne nagrywanie z mikrofonu jest wtedy wyłączone.",
        settings.useSystemRecorder,
    ) { settings.useSystemRecorder = it }

    log?.let { r ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text(if (r.ok) "Moduł zainstalowany" else "Instalacja nieudana") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (r.ok) Text("Uruchom telefon ponownie, aby aktywować nagrywanie z linii.\n")
                    Text(r.log.ifBlank { "(brak komunikatów)" }, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                if (r.ok) TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { RootHelper.reboot() }
                }) { Text("Uruchom ponownie") }
                else TextButton(onClick = { log = null }) { Text("OK") }
            },
            dismissButton = { if (r.ok) TextButton(onClick = { log = null }) { Text("Później") } },
        )
    }
}
