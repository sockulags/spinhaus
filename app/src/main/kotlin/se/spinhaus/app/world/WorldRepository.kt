package se.spinhaus.app.world

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.spinhaus.app.ble.BleLog
import se.spinhaus.core.ride.Route
import java.io.File
import java.time.LocalTime

@Serializable
data class WorldClimb(val start: Double, val end: Double, val gain: Double, val length: Double, val avgGrade: Double, val category: String = "")

/** The part of the renderer's init JSON that the app uses. */
@Serializable
data class WorldInit(
    val name: String,
    val step: Double,
    val n: Int,
    val total: Double,
    val ascent: Double = 0.0,
    val realTerrain: Boolean = false,
    val buildings: Int = 0,
    val climbs: List<WorldClimb> = emptyList(),
    val ele: List<Float> = emptyList(),
)

data class WorldRoute(val id: String, val name: String, val region: String, val builtIn: Boolean)

data class PreparedWorld(val initJson: String, val init: WorldInit, val route: Route, val quality: WorldQuality)

/** Quality presets: chunks drawn around the rider and pixel-ratio cap. */
enum class WorldQuality(val viewChunks: Int, val pixelRatio: Double) { LOW(3, 1.0), MEDIUM(4, 1.5), HIGH(6, 2.0) }

class WorldRepository(private val context: Context, private val log: BleLog) {
    private val json = Json { ignoreUnknownKeys = true }
    private val gpxDir get() = File(context.filesDir, "world/gpx").apply { mkdirs() }

    val available: Boolean get() = WorldNative.available

    val classics = listOf(
        WorldRoute("alpe-dhuez", "Alpe d'Huez", "Franska Alperna · från Bourg d'Oisans", true),
        WorldRoute("ventoux", "Mont Ventoux", "Provence · från Bédoin", true),
        WorldRoute("tourmalet", "Col du Tourmalet", "Pyrenéerna · från Luz-Saint-Sauveur", true),
        WorldRoute("mortirolo", "Passo del Mortirolo", "Italienska Alperna · från Mazzo", true),
        WorldRoute("zoncolan", "Monte Zoncolan", "Karniska Alperna · från Ovaro", true),
        WorldRoute("box-hill", "Box Hill", "Surrey · sicksackvägen", true),
    )

    val demo = WorldRoute("demo", "Demo-slingan", "Påhittad värld · fungerar utan internet", true)

    fun imported(): List<WorldRoute> = gpxDir.listFiles { f -> f.name.startsWith("user-") && f.extension == "gpx" }
        ?.sortedByDescending { it.lastModified() }
        ?.map { WorldRoute(it.nameWithoutExtension, readName(it) ?: it.nameWithoutExtension.removePrefix("user-"), "Egen rutt", false) }
        ?: emptyList()

    private fun readName(f: File): String? = runCatching {
        Regex("<name>([^<]{1,80})</name>").find(f.readText().take(4000))?.groupValues?.get(1)?.trim()
    }.getOrNull()

    fun isDownloaded(id: String) = id == "demo" || File(gpxDir, "$id.gpx").exists()

    /** Saves an imported GPX and returns its id. */
    fun importGpx(name: String, text: String): String {
        require(text.contains("<trkpt") || text.contains("<rtept")) { "Not a GPX route" }
        val safe = name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "route" }
        val id = "user-$safe-${System.currentTimeMillis() % 100000}"
        File(gpxDir, "$id.gpx").writeText(text)
        return id
    }

    fun delete(id: String) {
        File(gpxDir, "$id.gpx").delete()
    }

    private suspend fun gpxFor(id: String): String? = withContext(Dispatchers.IO) {
        if (id == "demo") return@withContext null
        val f = File(gpxDir, "$id.gpx")
        if (!f.exists()) {
            val gpx = WorldNative.planClassic(id)
            f.writeText(gpx)
        }
        f.readText()
    }

    /** Downloads (if needed) and builds the world. Takes seconds; runs off the main thread. */
    suspend fun prepare(route: WorldRoute, quality: WorldQuality, online: Boolean = true): PreparedWorld = withContext(Dispatchers.IO) {
        val gpx = gpxFor(route.id)
        val now = LocalTime.now()
        val initJson = try {
            WorldNative.prepare(gpx ?: "", route.name, route.id, context.filesDir.absolutePath, online, now.hour + now.minute / 60.0, quality.viewChunks)
        } finally {
            WorldNative.drainLog().lines().filter { it.isNotBlank() }.forEach { log.log("world", it) }
        }
        val init = json.decodeFromString(WorldInit.serializer(), initJson)
        // The ride engine follows the same (smoothed) elevation profile as the world.
        val pts = ArrayList<Pair<Double, Double>>(init.ele.size)
        init.ele.forEachIndexed { i, e -> pts.add(i * init.step to e.toDouble()) }
        if (pts.size < 2) pts.addAll(listOf(0.0 to 0.0, init.total.coerceAtLeast(1.0) to 0.0))
        val r = Route(route.id, init.name, init.name, pts, loop = false)
        PreparedWorld(initJson, init, r, quality)
    }
}
