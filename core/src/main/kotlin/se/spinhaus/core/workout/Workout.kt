package se.spinhaus.core.workout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class WorkoutCategory {
    RECOVERY, ENDURANCE, TEMPO, SWEET_SPOT, THRESHOLD, VO2MAX, ANAEROBIC, CADENCE, SHORT, FTP_TEST, CUSTOM
}

@Serializable
enum class WorkoutSource { BUILT_IN, CUSTOM, IMPORTED }

/** A text message shown at [offsetS] seconds into the segment. */
@Serializable
data class TextEvent(val offsetS: Int, val message: String, val durationS: Int = 10)

/**
 * A workout segment. Power values are fractions of FTP (1.0 = 100 % FTP),
 * or absolute watts when the workout's [Workout.absolutePower] is true.
 */
@Serializable
sealed class Segment {
    abstract val texts: List<TextEvent>
    abstract fun durationS(): Int

    @Serializable
    @SerialName("steady")
    data class Steady(
        val durationS: Int,
        val power: Double,
        val cadence: Int? = null,
        override val texts: List<TextEvent> = emptyList(),
    ) : Segment() {
        override fun durationS() = durationS
    }

    @Serializable
    @SerialName("ramp")
    data class Ramp(
        val durationS: Int,
        val from: Double,
        val to: Double,
        val cadence: Int? = null,
        override val texts: List<TextEvent> = emptyList(),
    ) : Segment() {
        override fun durationS() = durationS
    }

    @Serializable
    @SerialName("intervals")
    data class Intervals(
        val repeat: Int,
        val onS: Int,
        val onPower: Double,
        val offS: Int,
        val offPower: Double,
        val onCadence: Int? = null,
        val offCadence: Int? = null,
        override val texts: List<TextEvent> = emptyList(),
    ) : Segment() {
        override fun durationS() = repeat * (onS + offS)
    }

    /** ERG off – rider controls effort with gears. */
    @Serializable
    @SerialName("free")
    data class Free(
        val durationS: Int,
        val cadence: Int? = null,
        override val texts: List<TextEvent> = emptyList(),
    ) : Segment() {
        override fun durationS() = durationS
    }

    /** ERG off, all-out effort (used by FTP tests and sprints). [guide] is a suggested target. */
    @Serializable
    @SerialName("max")
    data class MaxEffort(
        val durationS: Int,
        val guide: Double? = null,
        override val texts: List<TextEvent> = emptyList(),
    ) : Segment() {
        override fun durationS() = durationS
    }
}

@Serializable
data class Workout(
    val id: String,
    val name: String,
    val description: String = "",
    val category: WorkoutCategory = WorkoutCategory.CUSTOM,
    val segments: List<Segment>,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val source: WorkoutSource = WorkoutSource.CUSTOM,
    val absolutePower: Boolean = false,
    /** Name localisation: "sv" → Swedish name, etc. */
    val localizedNames: Map<String, String> = emptyMap(),
    val localizedDescriptions: Map<String, String> = emptyMap(),
    val ftpTest: FtpTestKind? = null,
) {
    val totalDurationS: Int get() = segments.sumOf { it.durationS() }

    fun displayName(lang: String) = localizedNames[lang] ?: name
    fun displayDescription(lang: String) = localizedDescriptions[lang] ?: description
}

@Serializable
enum class FtpTestKind { RAMP, RAMP_SHORT, TWENTY_MIN, TWO_BY_EIGHT }

object WorkoutJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "type"
    }

    fun encode(w: Workout): String = json.encodeToString(Workout.serializer(), w)
    fun decode(s: String): Workout = json.decodeFromString(Workout.serializer(), s)

    fun encodeSegments(segments: List<Segment>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Segment.serializer()), segments)

    fun decodeSegments(s: String): List<Segment> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Segment.serializer()), s)
}
