package se.spinhaus.app.ui.screens

import android.annotation.SuppressLint
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.world.PreparedWorld
import se.spinhaus.app.world.WorldNative
import se.spinhaus.app.world.WorldQuality
import se.spinhaus.app.world.WorldRoute
import se.spinhaus.core.ride.RideSnapshot
import se.spinhaus.core.ride.RideStatus
import java.io.ByteArrayInputStream
import java.util.Locale

@Composable
fun WorldSetupScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val repo = app.world
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(WorldQuality.MEDIUM) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val imported = remember(refresh) { repo.imported() }
    val msgLoading = t("Downloading terrain and map data and building the world… (the first time takes up to a minute)", "Laddar ner terräng och kartdata och bygger världen… (första gången kan ta upp till en minut)")

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "route.gpx"
                    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    repo.importGpx(name, text)
                }
            }.onFailure { error = it.message }
            refresh++
        }
    }

    fun start(route: WorldRoute) {
        busy = msgLoading
        scope.launch {
            try {
                val prepared = repo.prepare(route, quality)
                busy = null
                nav.startRide(profile, se.spinhaus.core.ride.RideMode.Free(prepared.route), route.name, "FREE", world = prepared)
            } catch (e: Throwable) {
                busy = null
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    ScreenScaffold(title = t("3D world", "3D-värld"), onBack = nav::back, actions = {
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(t("Import GPX", "Importera GPX")) }
    }) { pad ->
        if (!repo.available) {
            Text(t("The 3D world is not included in this build.", "3D-världen finns inte i den här versionen."), modifier = Modifier.padding(pad).padding(24.dp))
            return@ScreenScaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        t("Real places in 3D: the terrain, forests, villages and the road come from open map data. The trainer follows the real gradient – use your gears.",
                            "Riktiga platser i 3D: terräng, skog, byar och vägen kommer från öppna kartdata. Trainern följer den riktiga lutningen – använd växlarna."),
                        color = SpinColors.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("Graphics:", "Grafik:"), modifier = Modifier.padding(top = 8.dp))
                        for (q in WorldQuality.entries) FilterChip(quality == q, { quality = q }, {
                            Text(when (q) { WorldQuality.LOW -> t("Fast", "Snabb"); WorldQuality.MEDIUM -> t("Balanced", "Balanserad"); WorldQuality.HIGH -> t("Beautiful", "Snyggast") })
                        })
                    }
                }
            }
            items(repo.classics + imported + repo.demo, key = { it.id }) { r ->
                SpinCard(onClick = { start(r) }) {
                    Text(r.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (r.region == "Egen rutt") t("Your route", "Egen rutt") else r.region, color = SpinColors.Muted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (repo.isDownloaded(r.id)) t("Ready", "Klar att köra") else t("Downloads when you start", "Laddas ner när du startar"),
                        color = if (repo.isDownloaded(r.id)) SpinColors.Good else SpinColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!r.builtIn) TextButton(onClick = { repo.delete(r.id); refresh++ }) { Text(t("Remove", "Ta bort"), color = SpinColors.Bad) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    t("World generation by VeloPopuli (open source, GPLv3). Map data © OpenStreetMap contributors; terrain from Terrain Tiles (AWS Open Data); routes by BRouter.",
                        "Världen bygger på VeloPopuli (öppen källkod, GPLv3). Kartdata © OpenStreetMaps bidragsgivare; terräng från Terrain Tiles (AWS Open Data); rutter från BRouter."),
                    color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }

    busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(t("Building the world", "Bygger världen")) },
            text = {
                Row {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
            confirmButton = {},
        )
    }
    error?.let { e ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(t("Couldn't build the world", "Kunde inte bygga världen")) },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

/** Bridge object exposed to the page as window.SpinhausAndroid. */
private class WorldJs(val onReady: () -> Unit, val onStats: (String) -> Unit, val onError: (String) -> Unit, val log: (String) -> Unit) {
    @JavascriptInterface fun log(msg: String) = log.invoke(msg)
    @JavascriptInterface fun ready(json: String) = onReady()
    @JavascriptInterface fun error(msg: String) = onError(msg)
    @JavascriptInterface fun stats(json: String) = onStats(json)
}

/** The 3D world behind the ride screen. Feeds the rider's distance and speed into the page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorldView(app: AppContainer, world: PreparedWorld, snapshotProvider: () -> RideSnapshot?, cameraTick: Int, modifier: Modifier = Modifier) {
    var ready by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(webView, ready) {
        val wv = webView ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        while (true) {
            val s = snapshotProvider()
            if (s != null) {
                val moving = s.status == RideStatus.RUNNING && s.speedKmh > 0.5
                val js = String.format(Locale.US, "window.spinhaus&&window.spinhaus.setState({distance:%.2f,speed:%.3f,cadence:%d,moving:%b})",
                    s.distanceM, s.speedKmh / 3.6, s.cadence ?: 0, moving)
                wv.evaluateJavascript(js, null)
            }
            delay(100)
        }
    }
    LaunchedEffect(cameraTick) {
        if (cameraTick > 0) webView?.evaluateJavascript("window.spinhaus&&window.spinhaus.cycleCamera()", null)
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            runCatching { WorldNative.release() }
        }
    }

    Box(modifier.background(Color(0xFF0E1116))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .addPathHandler("/api/") { path ->
                        try {
                            val bytes = if (path == "init") world.initJson.toByteArray() else {
                                val parts = path.split('/')
                                WorldNative.blob(parts[0], parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0, parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
                            }
                            WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(bytes))
                        } catch (e: Throwable) {
                            app.bleLog.log("world", "api $path failed: ${e.message}")
                            WebResourceResponse("text/plain", "utf-8", 500, "Error", emptyMap(), ByteArrayInputStream((e.message ?: "error").toByteArray()))
                        }
                    }
                    .build()
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    setBackgroundColor(0xFF0E1116.toInt())
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                    }
                    addJavascriptInterface(
                        WorldJs(
                            onReady = { post { ready = true } },
                            onStats = { j -> post { stats = j } },
                            onError = { m -> post { failure = m }; app.bleLog.log("world", "page error: $m") },
                            log = { m -> app.bleLog.log("world", m) },
                        ),
                        "SpinhausAndroid",
                    )
                    loadUrl("https://appassets.androidplatform.net/assets/world/index.html?pr=${world.quality.pixelRatio}")
                    webView = this
                }
            },
        )
        if (!ready && failure == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(t("Building the world…", "Bygger världen…"), color = SpinColors.Muted)
            }
        }
        failure?.let { Text(it, color = SpinColors.Bad, modifier = Modifier.padding(24.dp)) }
        // Frame rate, for judging how the tablet copes.
        if (stats.isNotEmpty()) {
            val fps = Regex("\"fps\":(\\d+)").find(stats)?.groupValues?.get(1) ?: "?"
            Text(
                "$fps fps",
                color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Compact stats over the 3D world. */
@Composable
fun WorldHud(sn: RideSnapshot, total: Double, imperial: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HudBox("${sn.power3sW}", "W", SpinColors.powerZone(sn.powerZone), big = true)
        HudBox(sn.hr?.toString() ?: "–", "bpm", sn.hrZone?.let { SpinColors.hrZone(it) } ?: Color.White)
        HudBox(sn.cadence?.toString() ?: "–", "rpm", Color.White)
        HudBox(Fmt.speedValue(sn.speedKmh, imperial), if (imperial) "mph" else "km/h", Color.White)
        HudBox(Fmt.one(sn.gradePercent), "%", if (sn.gradePercent >= 6) SpinColors.Bad else if (sn.gradePercent >= 3) SpinColors.Warn else Color.White)
        HudBox(
            Fmt.distance((total - sn.distanceM).coerceAtLeast(0.0), imperial),
            t("to go", "kvar"), Color.White,
        )
        if (!sn.ergActive) HudBox("${sn.gear}", t("gear", "växel"), Color.White)
    }
}

@Composable
private fun HudBox(value: String, unit: String, color: Color, big: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC0E1116))
            .border(if (big) 2.dp else 0.dp, color.copy(alpha = if (big) 0.9f else 0f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        Text(value, style = (if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge), color = color)
        Spacer(Modifier.width(4.dp))
        Text(unit, color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp))
    }
}

