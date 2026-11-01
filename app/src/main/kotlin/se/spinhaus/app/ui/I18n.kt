package se.spinhaus.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import se.spinhaus.core.workout.WorkoutCategory
import java.util.Locale

/** Active UI language ("sv" or "en"), set from the selected profile. */
val LocalLanguage = compositionLocalOf { "sv" }

/** Inline bilingual string. Usage: `t("Workouts", "Pass")`. */
@Composable
@ReadOnlyComposable
fun t(en: String, sv: String): String = if (LocalLanguage.current == "sv") sv else en

fun tr(lang: String, en: String, sv: String): String = if (lang == "sv") sv else en

fun categoryName(c: WorkoutCategory, lang: String): String = when (c) {
    WorkoutCategory.RECOVERY -> tr(lang, "Recovery", "Återhämtning")
    WorkoutCategory.ENDURANCE -> tr(lang, "Endurance", "Uthållighet")
    WorkoutCategory.TEMPO -> tr(lang, "Tempo", "Tempo")
    WorkoutCategory.SWEET_SPOT -> tr(lang, "Sweet spot", "Sweet spot")
    WorkoutCategory.THRESHOLD -> tr(lang, "Threshold", "Tröskel")
    WorkoutCategory.VO2MAX -> tr(lang, "VO2max", "VO2max")
    WorkoutCategory.ANAEROBIC -> tr(lang, "Anaerobic", "Anaerob")
    WorkoutCategory.CADENCE -> tr(lang, "Cadence", "Kadens")
    WorkoutCategory.SHORT -> tr(lang, "Short", "Korta")
    WorkoutCategory.FTP_TEST -> tr(lang, "FTP tests", "FTP-tester")
    WorkoutCategory.CUSTOM -> tr(lang, "My workouts", "Mina pass")
}

object Fmt {
    /** 75 → "1:15", 3725 → "1:02:05". */
    fun duration(totalS: Int): String {
        val s = totalS.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Human duration: "45 min", "1 h 30 min". */
    fun minutes(totalS: Int): String {
        val m = (totalS + 30) / 60
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun distance(m: Double, imperial: Boolean): String =
        if (imperial) "%.1f mi".format(Locale.US, m / 1609.344) else "%.1f km".format(Locale.US, m / 1000)

    fun distanceValue(m: Double, imperial: Boolean): String =
        if (imperial) "%.2f".format(Locale.US, m / 1609.344) else "%.2f".format(Locale.US, m / 1000)

    fun speedValue(kmh: Double, imperial: Boolean): String =
        if (imperial) "%.1f".format(Locale.US, kmh / 1.609344) else "%.1f".format(Locale.US, kmh)

    fun one(d: Double): String = "%.1f".format(Locale.US, d)
    fun two(d: Double): String = "%.2f".format(Locale.US, d)
}
