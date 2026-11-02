package se.spinhaus.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.spinhaus.core.ride.ControllerMapping
import se.spinhaus.core.ride.GearTable

@Serializable
enum class TileType {
    POWER_3S, POWER, TARGET, PERCENT_OF_TARGET, HEART_RATE, HR_ZONE, CADENCE, LAP_TIME, TOTAL_TIME,
    INTERVAL_REMAINING, NEXT_INTERVAL, SPEED, DISTANCE, GRADE, GEAR, NORMALIZED_POWER, AVG_POWER,
    KJ, KCAL, WATTS_PER_KG, TSS, CLOCK, AVG_HR, MAX_POWER,
}

@Serializable
enum class TileSize { LARGE, MEDIUM, SMALL }

@Serializable
data class TileSpec(val type: TileType, val size: TileSize = TileSize.MEDIUM)

@Serializable
data class DashboardConfig(val pages: List<List<TileSpec>> = Presets.standard) {
    companion object Presets {
        val minimal: List<List<TileSpec>> = listOf(
            listOf(
                TileSpec(TileType.POWER_3S, TileSize.LARGE),
                TileSpec(TileType.HEART_RATE, TileSize.LARGE),
                TileSpec(TileType.INTERVAL_REMAINING, TileSize.LARGE),
            ),
        )
        val standard: List<List<TileSpec>> = listOf(
            listOf(
                TileSpec(TileType.POWER_3S, TileSize.LARGE),
                TileSpec(TileType.TARGET, TileSize.LARGE),
                TileSpec(TileType.HEART_RATE, TileSize.LARGE),
                TileSpec(TileType.CADENCE, TileSize.MEDIUM),
                TileSpec(TileType.INTERVAL_REMAINING, TileSize.MEDIUM),
                TileSpec(TileType.TOTAL_TIME, TileSize.MEDIUM),
                TileSpec(TileType.SPEED, TileSize.SMALL),
                TileSpec(TileType.DISTANCE, TileSize.SMALL),
                TileSpec(TileType.GEAR, TileSize.SMALL),
                TileSpec(TileType.KJ, TileSize.SMALL),
            ),
            listOf(
                TileSpec(TileType.NORMALIZED_POWER, TileSize.MEDIUM),
                TileSpec(TileType.AVG_POWER, TileSize.MEDIUM),
                TileSpec(TileType.TSS, TileSize.MEDIUM),
                TileSpec(TileType.AVG_HR, TileSize.MEDIUM),
                TileSpec(TileType.WATTS_PER_KG, TileSize.MEDIUM),
                TileSpec(TileType.KCAL, TileSize.MEDIUM),
                TileSpec(TileType.CLOCK, TileSize.SMALL),
                TileSpec(TileType.MAX_POWER, TileSize.SMALL),
            ),
        )
        val nerd: List<List<TileSpec>> = listOf(
            listOf(
                TileSpec(TileType.POWER_3S, TileSize.LARGE),
                TileSpec(TileType.TARGET, TileSize.MEDIUM),
                TileSpec(TileType.PERCENT_OF_TARGET, TileSize.MEDIUM),
                TileSpec(TileType.HEART_RATE, TileSize.MEDIUM),
                TileSpec(TileType.HR_ZONE, TileSize.SMALL),
                TileSpec(TileType.CADENCE, TileSize.MEDIUM),
                TileSpec(TileType.INTERVAL_REMAINING, TileSize.MEDIUM),
                TileSpec(TileType.NEXT_INTERVAL, TileSize.MEDIUM),
                TileSpec(TileType.NORMALIZED_POWER, TileSize.SMALL),
                TileSpec(TileType.TSS, TileSize.SMALL),
                TileSpec(TileType.WATTS_PER_KG, TileSize.SMALL),
                TileSpec(TileType.KJ, TileSize.SMALL),
                TileSpec(TileType.TOTAL_TIME, TileSize.SMALL),
                TileSpec(TileType.SPEED, TileSize.SMALL),
                TileSpec(TileType.GEAR, TileSize.SMALL),
                TileSpec(TileType.GRADE, TileSize.SMALL),
            ),
        )
    }
}

@Serializable
enum class SoundMode { OFF, BEEPS, VOICE }

@Serializable
enum class SoundEvent { INTERVAL_CHANGE, COUNTDOWN, HALFWAY, WORKOUT_DONE, CONNECTION_LOST, LOW_CADENCE, TEXT_MESSAGES, SHIFT_CLICK }

@Serializable
data class SoundSettings(
    val mode: SoundMode = SoundMode.VOICE,
    val volume: Float = 0.8f,
    val events: Set<SoundEvent> = SoundEvent.entries.toSet() - SoundEvent.SHIFT_CLICK,
)

@Serializable
enum class Units { METRIC, IMPERIAL }

@Serializable
data class ProfileSettings(
    val dashboard: DashboardConfig = DashboardConfig(),
    val sound: SoundSettings = SoundSettings(),
    val mapping: ControllerMapping = ControllerMapping(),
    val gears: GearTable = GearTable(),
    val units: Units = Units.METRIC,
    val vibrateOnShift: Boolean = true,
    val showGraph: Boolean = true,
    val activePlan: se.spinhaus.core.plan.ActivePlan? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(s: String): ProfileSettings = if (s.isBlank()) ProfileSettings() else try {
            json.decodeFromString(serializer(), s)
        } catch (e: Exception) {
            ProfileSettings()
        }
        fun encode(p: ProfileSettings): String = json.encodeToString(serializer(), p)
    }
}

val ProfileEntity.settings: ProfileSettings get() = ProfileSettings.decode(settingsJson)
fun ProfileEntity.withSettings(s: ProfileSettings) = copy(settingsJson = ProfileSettings.encode(s))
