package pl.bartek.callrecorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(activity: MainActivity) {
    AppTheme {
        val nav = rememberNavController()
        val pending = activity.openRecording.value
        LaunchedEffect(pending) {
            if (pending != null) {
                nav.navigate("detail/$pending") { launchSingleTop = true }
                activity.openRecording.value = null
            }
        }
        NavHost(navController = nav, startDestination = "list") {
            composable("list") {
                RecordingsScreen(
                    activity = activity,
                    onOpen = { nav.navigate("detail/$it") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DetailScreen(id = it.arguments?.getLong("id") ?: 0, onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

object Fmt {
    private val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl"))
    fun date(ms: Long): String = date.format(Date(ms))
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
