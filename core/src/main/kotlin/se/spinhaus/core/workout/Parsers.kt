package se.spinhaus.core.workout

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt

class WorkoutParseException(message: String) : Exception(message)

data class ParseResult(val workout: Workout, val warnings: List<String>)

object WorkoutImporter {
    /** Detects format by file name / content and parses. */
    fun import(fileName: String, content: String): ParseResult {
        val lower = fileName.lowercase()
        val trimmed = content.trimStart()
        return when {
            lower.endsWith(".zwo") || trimmed.startsWith("<") -> ZwoParser.parse(content, fileName)
            lower.endsWith(".erg") || lower.endsWith(".mrc") || trimmed.startsWith("[COURSE HEADER]", true) ->
                ErgMrcParser.parse(content, fileName)
            lower.endsWith(".json") -> ParseResult(WorkoutJson.decode(content).copy(id = newId(), source = WorkoutSource.IMPORTED), emptyList())
            else -> throw WorkoutParseException("Unknown workout format: $fileName")
        }
    }

    fun newId(): String = "imp-" + UUID.randomUUID().toString()
}

object ZwoParser {
    fun parse(xml: String, fileName: String = "workout.zwo"): ParseResult {
        val warnings = ArrayList<String>()
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        factory.isExpandEntityReferences = false
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw WorkoutParseException("Invalid ZWO XML: ${e.message}")
        }
        val root = doc.documentElement
        fun childText(tag: String): String? {
            val nodes = root.getElementsByTagName(tag)
            return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
        }
        val name = childText("name")?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')
        val description = childText("description") ?: ""
        val author = childText("author")
        val tags = ArrayList<String>()
        val tagNodes = root.getElementsByTagName("tag")
        for (i in 0 until tagNodes.length) {
            (tagNodes.item(i) as? Element)?.getAttribute("name")?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
        }
        val workoutNodes = root.getElementsByTagName("workout")
        if (workoutNodes.length == 0) throw WorkoutParseException("No <workout> element")
        val wEl = workoutNodes.item(0) as Element
        val segments = ArrayList<Segment>()
        val children = wEl.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            fun d(attr: String): Double? = el.getAttribute(attr).takeIf { it.isNotBlank() }?.toDoubleOrNull()
            fun n(attr: String): Int? = d(attr)?.roundToInt()
            val texts = parseTexts(el)
            val dur = n("Duration") ?: 0
            val cad = n("Cadence")
            when (el.tagName.lowercase()) {
                "warmup", "cooldown", "ramp" -> {
                    var lo = d("PowerLow") ?: d("Power") ?: 0.5
                    var hi = d("PowerHigh") ?: d("Power") ?: lo
                    if (el.tagName.equals("cooldown", true) && lo < hi) {
                        val t = lo; lo = hi; hi = t
                    }
                    segments.add(Segment.Ramp(dur, lo, hi, cad, texts))
                }
                "steadystate", "solidstate" -> {
                    val p = d("Power") ?: run {
                        val lo = d("PowerLow"); val hi = d("PowerHigh")
                        if (lo != null && hi != null) (lo + hi) / 2 else lo ?: hi
                    } ?: 0.5
                    segments.add(Segment.Steady(dur, p, cad, texts))
                }
                "intervalst" -> {
                    val rep = n("Repeat") ?: 1
                    val onP = d("OnPower") ?: avg(d("PowerOnLow"), d("PowerOnHigh")) ?: 1.0
                    val offP = d("OffPower") ?: avg(d("PowerOffLow"), d("PowerOffHigh")) ?: 0.5
                    segments.add(
                        Segment.Intervals(
                            rep, n("OnDuration") ?: 60, onP, n("OffDuration") ?: 60, offP,
                            cad, n("CadenceResting"), texts,
                        ),
                    )
                }
                "freeride" -> segments.add(Segment.Free(dur, cad, texts))
                "maxeffort" -> segments.add(Segment.MaxEffort(dur, null, texts))
                "textevent", "textnotification" -> {}
                else -> warnings.add("Ignored element <${el.tagName}>")
            }
        }
        if (segments.isEmpty()) throw WorkoutParseException("Workout has no segments")
        val absolute = segments.any { s ->
            when (s) {
                is Segment.Steady -> s.power > 5
                is Segment.Ramp -> s.from > 5 || s.to > 5
                is Segment.Intervals -> s.onPower > 5
                else -> false
            }
        }
        return ParseResult(
            Workout(
                id = WorkoutImporter.newId(), name = name, description = description,
                category = guessCategory(segments, absolute), segments = segments, tags = tags,
                author = author, source = WorkoutSource.IMPORTED, absolutePower = absolute,
            ),
            warnings,
        )
    }

    private fun avg(a: Double?, b: Double?): Double? = if (a != null && b != null) (a + b) / 2 else a ?: b

    private fun parseTexts(el: Element): List<TextEvent> {
        val out = ArrayList<TextEvent>()
        val kids = el.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i) as? Element ?: continue
            if (!k.tagName.equals("textevent", true) && !k.tagName.equals("textnotification", true)) continue
            val off = (k.getAttribute("timeoffset").ifBlank { k.getAttribute("TimeOffset") }).toDoubleOrNull()?.roundToInt() ?: 0
            val msg = k.getAttribute("message").ifBlank { k.getAttribute("text") }
            val dur = k.getAttribute("duration").toDoubleOrNull()?.roundToInt() ?: 10
            if (msg.isNotBlank()) out.add(TextEvent(off, msg, dur))
        }
        return out
    }

    fun guessCategory(segments: List<Segment>, absolute: Boolean): WorkoutCategory {
        if (absolute) return WorkoutCategory.CUSTOM
        val w = Workout("x", "x", segments = segments)
        val est = WorkoutStats.estimate(w)
        val maxOn = segments.maxOfOrNull {
            when (it) {
                is Segment.Steady -> it.power
                is Segment.Intervals -> it.onPower
                is Segment.Ramp -> maxOf(it.from, it.to)
                else -> 0.0
            }
        } ?: 0.0
        return when {
            est.durationS <= 30 * 60 -> WorkoutCategory.SHORT
            maxOn >= 1.3 -> WorkoutCategory.ANAEROBIC
            maxOn >= 1.08 -> WorkoutCategory.VO2MAX
            maxOn >= 0.95 -> WorkoutCategory.THRESHOLD
            maxOn >= 0.86 -> WorkoutCategory.SWEET_SPOT
            maxOn >= 0.76 -> WorkoutCategory.TEMPO
            maxOn >= 0.56 -> WorkoutCategory.ENDURANCE
            else -> WorkoutCategory.RECOVERY
        }
    }
}

/** Parses .erg (absolute watts) and .mrc (percent of FTP) course files. */
object ErgMrcParser {
    fun parse(text: String, fileName: String = "workout.erg"): ParseResult {
        val warnings = ArrayList<String>()
        val lines = text.lines().map { it.trim() }
        var inHeader = false
        var inData = false
        var percent = fileName.lowercase().endsWith(".mrc")
        var name: String? = null
        var description = ""
        val points = ArrayList<Pair<Double, Double>>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith(";")) continue
            when {
                line.equals("[COURSE HEADER]", true) -> { inHeader = true; continue }
                line.equals("[END COURSE HEADER]", true) -> { inHeader = false; continue }
                line.equals("[COURSE DATA]", true) -> { inData = true; continue }
                line.equals("[END COURSE DATA]", true) -> { inData = false; continue }
                line.startsWith("[") -> { inHeader = false; inData = false; continue }
            }
            if (inHeader) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("DESCRIPTION") -> description = line.substringAfter("=").trim()
                    upper.startsWith("FILE NAME") -> name = line.substringAfter("=").trim()
                    upper.contains("MINUTES") && upper.contains("PERCENT") -> percent = true
                    upper.contains("MINUTES") && upper.contains("WATTS") -> percent = false
                }
            } else if (inData) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val m = parts[0].toDoubleOrNull()
                    val v = parts[1].toDoubleOrNull()
                    if (m != null && v != null) points.add(m to v) else warnings.add("Bad line: $line")
                }
            }
        }
        if (points.size < 2) throw WorkoutParseException("Not enough course data points")
        val segments = ArrayList<Segment>()
        for (i in 0 until points.size - 1) {
            val (m0, v0) = points[i]
            val (m1, v1) = points[i + 1]
            val dur = ((m1 - m0) * 60).roundToInt()
            if (dur <= 0) continue
            val a = if (percent) v0 / 100.0 else v0
            val b = if (percent) v1 / 100.0 else v1
            segments.add(if (abs(a - b) < 1e-6) Segment.Steady(dur, a) else Segment.Ramp(dur, a, b))
        }
        val merged = mergeSteady(segments)
        return ParseResult(
            Workout(
                id = WorkoutImporter.newId(),
                name = name?.substringBeforeLast('.')?.ifBlank { null } ?: fileName.substringBeforeLast('.'),
                description = description,
                category = ZwoParser.guessCategory(merged, !percent),
                segments = merged,
                source = WorkoutSource.IMPORTED,
                absolutePower = !percent,
            ),
            warnings,
        )
    }

    private fun mergeSteady(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (s is Segment.Steady && last is Segment.Steady && abs(last.power - s.power) < 1e-6) {
                out[out.size - 1] = last.copy(durationS = last.durationS + s.durationS)
            } else out.add(s)
        }
        return out
    }
}

/** Writes a workout as Zwift .zwo XML (for sharing/backup). */
object ZwoWriter {
    fun write(w: Workout): String {
        val sb = StringBuilder()
        sb.append("<workout_file>\n")
        sb.append("  <author>").append(esc(w.author ?: "Spinhaus")).append("</author>\n")
        sb.append("  <name>").append(esc(w.name)).append("</name>\n")
        sb.append("  <description>").append(esc(w.description)).append("</description>\n")
        sb.append("  <sportType>bike</sportType>\n  <workout>\n")
        fun cad(c: Int?) = if (c != null) " Cadence=\"$c\"" else ""
        fun f(d: Double) = "%.3f".format(java.util.Locale.US, d)
        for (s in w.segments) {
            when (s) {
                is Segment.Steady -> sb.append("    <SteadyState Duration=\"${s.durationS}\" Power=\"${f(s.power)}\"${cad(s.cadence)}")
                is Segment.Ramp -> sb.append("    <Ramp Duration=\"${s.durationS}\" PowerLow=\"${f(s.from)}\" PowerHigh=\"${f(s.to)}\"${cad(s.cadence)}")
                is Segment.Intervals -> sb.append(
                    "    <IntervalsT Repeat=\"${s.repeat}\" OnDuration=\"${s.onS}\" OffDuration=\"${s.offS}\" OnPower=\"${f(s.onPower)}\" OffPower=\"${f(s.offPower)}\"${cad(s.onCadence)}" +
                        (s.offCadence?.let { " CadenceResting=\"$it\"" } ?: ""),
                )
                is Segment.Free -> sb.append("    <FreeRide Duration=\"${s.durationS}\" FlatRoad=\"1\"${cad(s.cadence)}")
                is Segment.MaxEffort -> sb.append("    <MaxEffort Duration=\"${s.durationS}\"")
            }
            if (s.texts.isEmpty()) sb.append("/>\n") else {
                sb.append(">\n")
                for (t in s.texts) sb.append("      <textevent timeoffset=\"${t.offsetS}\" message=\"${esc(t.message)}\"/>\n")
                sb.append("    </").append(
                    when (s) {
                        is Segment.Steady -> "SteadyState"; is Segment.Ramp -> "Ramp"; is Segment.Intervals -> "IntervalsT"
                        is Segment.Free -> "FreeRide"; is Segment.MaxEffort -> "MaxEffort"
                    },
                ).append(">\n")
            }
        }
        sb.append("  </workout>\n</workout_file>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
