package app.wallshift.shared

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Tiny files shared between the app process and the separate `:wallpaper` process.
 *
 * The live wallpaper runs in its own lightweight process (no Compose, no DataStore, no
 * coroutines), so the Home screen never waits on the app. The two sides talk through these
 * files (watched with inotify via FileObserver, so no polling) plus rare explicit broadcasts.
 * Every write is atomic (temp file + rename), so a reader never sees half a value.
 */
object SharedState {
    /** Image id the Home screen shows. Written by the app, watched by the engine. */
    const val HOME = "home_current"
    /** Marker: an automatic change is waiting for unlock. */
    const val PENDING = "pending_change"
    /** Marker: the lock screen should get the latest image at the next screen-off. */
    const val LOCK_PENDING = "lock_pending"
    /** pid of the wallpaper process while one of its engines draws the Home screen. */
    const val ENGINE = "engine_alive"
    /** Single taps the engine received from the Home screen (diagnostics). */
    const val HOME_TAPS = "home_taps"
    /** Marker: double tap is switched off in the app. */
    const val DOUBLE_TAP_OFF = "double_tap_off"
    /** The next image ids in shuffle order (comma-separated); the engine preloads the first. */
    const val UPCOMING = "upcoming"
    /** pid of the wallpaper process while one of its engines draws the lock screen. */
    const val ENGINE_LOCK = "engine_lock"

    fun dir(context: Context): File = File(context.filesDir, "state").also { if (!it.isDirectory) it.mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    fun read(context: Context, name: String): String? =
        runCatching { file(context, name).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } }.getOrNull()

    fun write(context: Context, name: String, value: String) {
        val target = file(context, name)
        val tmp = File(target.parentFile, ".$name.tmp")
        runCatching {
            tmp.writeText(value)
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    fun exists(context: Context, name: String) = file(context, name).exists()

    fun readList(context: Context, name: String): List<String> =
        read(context, name)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    fun mark(context: Context, name: String, on: Boolean) {
        if (on) { if (!exists(context, name)) write(context, name, "1") } else file(context, name).delete()
    }

    /**
     * True while the wallpaper process that wrote [marker] ([ENGINE] or [ENGINE_LOCK]) is alive.
     * Checked against this app's own running processes (official API, works on every ROM), so a
     * marker left behind by a killed process — or any foreign pid — never counts.
     */
    fun engineAlive(context: Context, marker: String = ENGINE): Boolean {
        val pid = read(context, marker)?.toIntOrNull() ?: return false
        if (pid <= 1) return false
        val procs = runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
        }.getOrNull()
        if (!procs.isNullOrEmpty()) return procs.any { it.pid == pid }
        // No process list available: fall back to the pid's command line.
        return runCatching {
            File("/proc/$pid/cmdline").readText().startsWith(context.packageName)
        }.getOrDefault(false)
    }

    // Image files (same layout as WallpaperStore), usable without the app's object graph.
    fun imageFile(context: Context, id: String) = File(context.filesDir, "walls/$id.jpg")
    fun thumbFile(context: Context, id: String) = File(context.filesDir, "thumbs/$id.webp")

    /** Newest image on disk, for an engine that has no id yet. */
    fun newestImageId(context: Context): String? =
        File(context.filesDir, "walls").listFiles { f -> f.name.endsWith(".jpg") }
            ?.maxByOrNull { it.lastModified() }?.name?.removeSuffix(".jpg")
}
