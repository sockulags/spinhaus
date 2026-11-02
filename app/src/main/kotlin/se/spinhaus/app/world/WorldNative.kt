package se.spinhaus.app.world

/** JNI bridge to the Rust world library (world/ in the repo, built on VeloPopuli). */
object WorldNative {
    /** False when the native library isn't in this build (then the 3D world is hidden). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("spinhaus_world"); true }.getOrDefault(false)
    }

    @JvmStatic external fun prepare(gpx: String, name: String, key: String, cacheDir: String, online: Boolean, hour: Double, viewChunks: Int): String
    @JvmStatic external fun blob(name: String, a: Double, b: Double): ByteArray
    @JvmStatic external fun planClassic(id: String): String
    @JvmStatic external fun release()
    @JvmStatic external fun drainLog(): String
}
