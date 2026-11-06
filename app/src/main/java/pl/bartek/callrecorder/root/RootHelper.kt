package pl.bartek.callrecorder.root

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tryb root: instaluje aplikację jako systemową (priv-app) przez moduł Magisk / KernelSU / APatch.
 * Aplikacja systemowa z uprawnieniem CAPTURE_AUDIO_OUTPUT może używać źródła VOICE_CALL,
 * czyli nagrywać obie strony rozmowy bezpośrednio z linii (słuchawka, głośnik, Bluetooth).
 */
object RootHelper {

    const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
    private const val MODULE_DIR_TMP = "/data/local/tmp/callrecorder-module.zip"

    /** Czy aplikacja ma już uprawnienie do nagrywania linii (moduł zainstalowany i telefon uruchomiony ponownie). */
    fun hasLineCapture(ctx: Context): Boolean =
        ctx.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED

    data class Result(val ok: Boolean, val log: String)

    /** Uruchamia polecenie jako root (wywoła okno zgody menedżera roota). */
    fun su(cmd: String, timeoutSec: Long = 120): Result = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        reader.start()
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroy()
        reader.join(2000)
        Result(finished && p.exitValue() == 0, out.toString().trim())
    } catch (e: Exception) {
        Result(false, "Brak dostępu root (su): ${e.message}")
    }

    fun isRootAvailable(): Boolean = su("id", 30).let { it.ok && it.log.contains("uid=0") }

    /** Buduje zip modułu z bieżącego APK (moduł zawsze zawiera aktualną wersję aplikacji). */
    fun buildModuleZip(ctx: Context): File {
        val out = File(ctx.cacheDir, "callrecorder-module.zip")
        val apk = File(ctx.applicationInfo.sourceDir)
        ZipOutputStream(out.outputStream()).use { zip ->
            fun asset(name: String, path: String) {
                zip.putNextEntry(ZipEntry(path))
                ctx.assets.open("magisk/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            asset("module.prop", "module.prop")
            asset("customize.sh", "customize.sh")
            asset("update-binary", "META-INF/com/google/android/update-binary")
            asset("updater-script", "META-INF/com/google/android/updater-script")
            asset("privapp.xml", "system/etc/permissions/privapp-permissions-pl.bartek.callrecorder.xml")
            zip.putNextEntry(ZipEntry("system/priv-app/CallRecorder/CallRecorder.apk"))
            apk.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return out
    }

    /** Instaluje moduł przez dostępny menedżer roota. Po sukcesie wymagany restart telefonu. */
    fun installModule(ctx: Context): Result {
        val zip = runCatching { buildModuleZip(ctx) }.getOrElse { return Result(false, "Nie udało się zbudować modułu: ${it.message}") }
        val z = MODULE_DIR_TMP
        val script = """
            cp '${zip.absolutePath}' $z && chmod 644 $z || exit 3
            if command -v magisk >/dev/null 2>&1; then
              echo '> Magisk'; magisk --install-module $z; r=${'$'}?
            elif [ -x /data/adb/ksud ]; then
              echo '> KernelSU'; /data/adb/ksud module install $z; r=${'$'}?
            elif [ -x /data/adb/apd ]; then
              echo '> APatch'; /data/adb/apd module install $z; r=${'$'}?
            else
              echo 'Nie znaleziono Magisk / KernelSU / APatch'; r=2
            fi
            rm -f $z
            exit ${'$'}r
        """.trimIndent()
        return su(script, 300)
    }

    fun reboot(): Result = su("svc power reboot || reboot", 30)
}
