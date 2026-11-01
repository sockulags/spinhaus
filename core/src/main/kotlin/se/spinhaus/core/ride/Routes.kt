package se.spinhaus.core.ride

import kotlin.math.PI
import kotlin.math.sin

/** A fictional route defined by an elevation profile. */
class Route(
    val id: String,
    val name: String,
    val nameSv: String,
    /** (distance m, elevation m) points, distance strictly increasing, first at 0. */
    val points: List<Pair<Double, Double>>,
    val loop: Boolean,
) {
    val lengthM: Double = points.last().first
    val ascentM: Double = points.zipWithNext().sumOf { (a, b) -> maxOf(0.0, b.second - a.second) }

    private fun wrap(d: Double): Double = if (loop && lengthM > 0) ((d % lengthM) + lengthM) % lengthM else d.coerceIn(0.0, lengthM)

    fun elevationAt(distanceM: Double): Double {
        val d = wrap(distanceM)
        val i = indexAt(d)
        if (i >= points.lastIndex) return points.last().second
        val (d0, e0) = points[i]
        val (d1, e1) = points[i + 1]
        if (d1 <= d0) return e0
        return e0 + (e1 - e0) * (d - d0) / (d1 - d0)
    }

    fun gradeAt(distanceM: Double): Double {
        val d = wrap(distanceM)
        val i = indexAt(d)
        if (i >= points.lastIndex) return 0.0
        val (d0, e0) = points[i]
        val (d1, e1) = points[i + 1]
        return if (d1 > d0) (e1 - e0) / (d1 - d0) * 100.0 else 0.0
    }

    fun lapOf(distanceM: Double): Int = if (loop && lengthM > 0) (distanceM / lengthM).toInt() + 1 else 1
    fun isFinished(distanceM: Double) = !loop && distanceM >= lengthM

    private fun indexAt(d: Double): Int {
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (points[mid].first <= d) lo = mid else hi = mid - 1
        }
        return lo
    }
}

object Routes {
    /** Builds a profile from (length m, grade %) sections. */
    private fun sections(vararg s: Pair<Double, Double>, start: Double = 20.0): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        var d = 0.0
        var e = start
        pts.add(0.0 to e)
        for ((len, grade) in s) {
            // subdivide every 100 m so smoothing works nicely
            val n = maxOf(1, (len / 100).toInt())
            repeat(n) {
                d += len / n
                e += len / n * grade / 100
                pts.add(d to e)
            }
        }
        return pts
    }

    private fun smooth(pts: List<Pair<Double, Double>>, passes: Int = 2): List<Pair<Double, Double>> {
        var cur = pts
        repeat(passes) {
            cur = cur.mapIndexed { i, p ->
                if (i == 0 || i == cur.lastIndex) p else p.first to (cur[i - 1].second + p.second * 2 + cur[i + 1].second) / 4
            }
        }
        return cur
    }

    private fun rolling(length: Double, amp: Double, waves: Int): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        val n = (length / 50).toInt()
        for (i in 0..n) {
            val d = length * i / n
            val x = d / length
            val e = 30 + amp * sin(2 * PI * waves * x) + amp * 0.4 * sin(2 * PI * (waves * 2.7) * x + 1.3)
            pts.add(d to e)
        }
        // ensure loop closes
        pts[pts.lastIndex] = length to pts[0].second
        return pts
    }

    val flat = Route("flat", "Flatlands Loop", "Slättslingan", listOf(0.0 to 10.0, 5000.0 to 12.0, 10000.0 to 10.0), loop = true)

    val rolling = Route("rolling", "Rolling Hills", "Böljande kullar", rolling(18000.0, 25.0, 6), loop = true)

    val coastal = Route("coastal", "Coastal Road", "Kustvägen", rolling(30000.0, 12.0, 9), loop = true)

    val longClimb = Route(
        "climb", "The Long Climb", "Den långa klättringen",
        smooth(sections(2000.0 to 1.0, 1500.0 to 4.0, 3000.0 to 7.0, 2000.0 to 8.5, 2500.0 to 6.5, 1000.0 to 9.0, 500.0 to 3.0)),
        loop = false,
    )

    val hillRepeats = Route(
        "repeats", "Hill Repeats", "Backintervaller",
        smooth(sections(1000.0 to 0.5, 1000.0 to 6.0, 1000.0 to -6.0, 500.0 to 0.0, 1000.0 to 6.0, 1000.0 to -6.0, 500.0 to 0.0,
            1000.0 to 6.0, 1000.0 to -6.0, 500.0 to 0.0, 1000.0 to 6.0, 1000.0 to -6.0, 500.0 to 0.0, 1000.0 to 6.0, 1000.0 to -6.0, 1000.0 to -0.5), 1),
        loop = true,
    )

    val all = listOf(flat, rolling, coastal, longClimb, hillRepeats)

    fun byId(id: String?): Route? = all.firstOrNull { it.id == id }
}
