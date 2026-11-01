package se.spinhaus.app.ride

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import se.spinhaus.app.data.SoundEvent
import se.spinhaus.app.data.SoundMode
import se.spinhaus.app.data.SoundSettings
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.tr
import se.spinhaus.core.ride.RideEvent
import se.spinhaus.core.workout.StepKind
import java.util.Locale

/** Beeps and spoken cues during a ride. */
class AudioCues(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    var settings = SoundSettings()
    var language = "sv"
    var ftp = 200
    var absolute = false

    fun prepare(settings: SoundSettings, language: String) {
        this.settings = settings
        this.language = language
        if (settings.mode == SoundMode.OFF) return
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, (settings.volume * 100).toInt().coerceIn(10, 100)) }.getOrNull()
        if (settings.mode == SoundMode.VOICE && tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                applyLanguage()
            }
        } else applyLanguage()
    }

    private fun applyLanguage() {
        val t = tts ?: return
        if (!ttsReady) return
        val loc = if (language == "sv") Locale("sv", "SE") else Locale.UK
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.ENGLISH)
    }

    fun release() {
        tts?.shutdown(); tts = null; ttsReady = false
        tone?.release(); tone = null
    }

    private fun enabled(e: SoundEvent) = settings.mode != SoundMode.OFF && e in settings.events

    private fun beep(long: Boolean = false) {
        tone?.startTone(if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (long) 400 else 150)
    }

    private fun say(text: String) {
        if (settings.mode != SoundMode.VOICE) return
        val t = tts ?: return
        if (!ttsReady) return
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume) }
        t.speak(text, TextToSpeech.QUEUE_ADD, params, "spin-${System.nanoTime()}")
    }

    fun connectionLost(device: String) {
        if (!enabled(SoundEvent.CONNECTION_LOST)) return
        beep(true)
        say(tr(language, "$device disconnected", "$device tappade anslutningen"))
    }

    fun connectionRestored(device: String) {
        if (!enabled(SoundEvent.CONNECTION_LOST)) return
        say(tr(language, "$device connected", "$device ansluten"))
    }

    fun handle(e: RideEvent) {
        when (e) {
            is RideEvent.Countdown -> if (enabled(SoundEvent.COUNTDOWN)) beep()
            is RideEvent.StepStarted -> if (enabled(SoundEvent.INTERVAL_CHANGE)) {
                beep(true)
                val s = e.step
                val dur = Fmt.minutes(s.durationS).let { if (s.durationS < 60) "${s.durationS} " + tr(language, "seconds", "sekunder") else it }
                val text = when (s.kind) {
                    StepKind.FREE -> tr(language, "Free ride, $dur", "Fri cykling, $dur")
                    StepKind.MAX -> tr(language, "All out, $dur", "Max, $dur")
                    StepKind.RAMP -> tr(language, "Ramp, $dur", "Ramp, $dur")
                    StepKind.STEADY -> {
                        val w = s.from?.let { if (absolute) it.toInt() else (it * ftp).toInt() }
                        tr(language, "$dur at $w watts", "$dur på $w watt")
                    }
                }
                say(text)
            }
            RideEvent.Halfway -> if (enabled(SoundEvent.HALFWAY)) say(tr(language, "Halfway there", "Halvvägs"))
            RideEvent.WorkoutComplete -> if (enabled(SoundEvent.WORKOUT_DONE)) {
                beep(true); say(tr(language, "Workout complete. Great job!", "Passet är klart. Bra jobbat!"))
            }
            RideEvent.RampTestEnded -> if (enabled(SoundEvent.WORKOUT_DONE)) say(tr(language, "Test complete. Cool down now.", "Testet är klart. Varva ner nu."))
            RideEvent.LowCadence -> if (enabled(SoundEvent.LOW_CADENCE)) say(tr(language, "Cadence up", "Öka kadensen"))
            is RideEvent.Text -> if (enabled(SoundEvent.TEXT_MESSAGES)) say(e.message)
            is RideEvent.Shifted -> if (enabled(SoundEvent.SHIFT_CLICK)) tone?.startTone(ToneGenerator.TONE_CDMA_PIP, 40)
            RideEvent.GoalReached -> if (enabled(SoundEvent.WORKOUT_DONE)) say(tr(language, "Goal reached!", "Målet är nått!"))
            else -> {}
        }
    }
}
