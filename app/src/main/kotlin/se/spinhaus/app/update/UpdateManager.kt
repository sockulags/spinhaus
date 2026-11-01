package se.spinhaus.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val versionName: String, val versionCode: Int, val apkUrl: String, val notes: String) : UpdateState()
    data class Downloading(val versionName: String, val progress: Float) : UpdateState()
    data class ReadyToInstall(val versionName: String, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * Checks GitHub Releases for a newer APK, downloads it and hands it to the Android installer.
 * Release tags are "v0.1.<versionCode>".
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val prefs = context.getSharedPreferences("spinhaus-update", Context.MODE_PRIVATE)

    val installedVersionCode: Long
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    val installedVersionName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Checks at most every [minIntervalMs] unless [force]. */
    fun check(force: Boolean = false, minIntervalMs: Long = 6 * 3600 * 1000L) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        val last = prefs.getLong("lastCheck", 0L)
        if (!force && System.currentTimeMillis() - last < minIntervalMs && s !is UpdateState.Idle) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val r = withContext(Dispatchers.IO) { fetchLatest() }
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (r != null && r.versionCode > installedVersionCode) r else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchLatest(): UpdateState.Available? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(body).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val asset = json["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk") } ?: return null
            val url = (asset as JsonObject)["browser_download_url"]?.jsonPrimitive?.content ?: return null
            val notes = json["body"]?.jsonPrimitive?.content ?: ""
            return UpdateState.Available(tag.removePrefix("v"), code, url, notes)
        } finally {
            conn.disconnect()
        }
    }

    fun download() {
        val a = _state.value as? UpdateState.Available ?: return
        _state.value = UpdateState.Downloading(a.versionName, 0f)
        scope.launch {
            _state.value = try {
                val f = withContext(Dispatchers.IO) { downloadFile(a) }
                UpdateState.ReadyToInstall(a.versionName, f)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (_state.value is UpdateState.ReadyToInstall) install()
        }
    }

    private fun downloadFile(a: UpdateState.Available): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "spinhaus-${a.versionName}.apk")
        var url = URL(a.apkUrl)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0 && done - lastReport > 256 * 1024) {
                        lastReport = done
                        _state.value = UpdateState.Downloading(a.versionName, done.toFloat() / total)
                    }
                }
            }
        }
        conn.disconnect()
        return out
    }

    /** Opens the system installer (or the "install unknown apps" permission screen first). */
    fun install() {
        val r = _state.value as? UpdateState.ReadyToInstall ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", r.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.UpToDate
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/sockulags/spinhaus/releases/latest"
    }
}
