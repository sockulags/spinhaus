package se.spinhaus.app.ble

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of Bluetooth traffic and events, exportable for troubleshooting. */
class BleLog(private val maxLines: Int = 20000) {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(last: Int = 300): List<String> = lines.toList().takeLast(last)

    @Synchronized
    fun export(context: Context, header: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "spinhaus-log-$stamp.txt")
        f.bufferedWriter().use { w ->
            w.write(header); w.newLine(); w.newLine()
            for (l in lines) { w.write(l); w.newLine() }
        }
        return f
    }
}
